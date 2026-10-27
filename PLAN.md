# jblaze: plan for Phase 0 and Phase 1

## Environment (checked)
- Empty repo, Temurin 21, Gradle via mise, no Maven. **Target: Gradle, Java 21, Spring Boot 3.x.** Maven comes later.

## Scope cut before writing code
**JetBrains Runtime + HotswapAgent already exist.** JBR ships HotswapAgent (`-XX:HotswapAgent=fatjar`), which already
does structural swaps, Spring bean reload and MVC mapping refresh. That covers most of Phases 2–3.
So Phase 0 benchmarks it as a second baseline. If it holds up, jblaze **uses** it for the in-JVM side and spends its own
effort where nothing exists today:
1. a warm compile loop with no Gradle on the hot path, and
2. `jblaze test` (warm test JVM plus affected-test selection).

Phase 0's numbers make this decision; it isn't made here.

## Repo layout
```
jblaze/
  settings.gradle.kts
  jblaze/        # CLI + daemon in one jar (Phase 1 runs them in one process)
  agent/         # -javaagent jar, JDK only, no deps
  bench/         # bench.sh + results.md
  demo/          # a copy of spring-petclinic (not committed, fetched by bench.sh)
```
ponytail: no separate daemon process in Phase 1. `jblaze run` lives for the whole session anyway, so javac stays warm.
Split it out in Phase 5, when `jblaze test` and `jblaze run` need to share one warm compiler.

---

## Phase 0: Benchmarks (2–3 days)

**One metric:** wall time from saving the file to the HTTP response showing the change.

`bench/bench.sh <mode>`:
1. Start the app and wait until `curl /` responds.
2. Loop N=10: rewrite a string literal in a controller → poll `curl` every 10 ms until the new string appears → record ms.
3. Print the median and p90 as a row in `results.md`.

Modes, all on spring-petclinic:
| Mode | How |
|---|---|
| `gradle-restart` | kill, then `./gradlew bootRun` |
| `devtools` | `bootRun` + DevTools, with `./gradlew classes --continuous` running in a second process |
| `hotswapagent` | JBR + HotswapAgent, with `classes --continuous` |
| `jblaze` | added after Phase 1 |

Also record a **change-type matrix**: method body, new endpoint, new bean, yml change, entity change, run by hand
against `devtools` and `hotswapagent`. This tells us which rows of the Spring table actually need new work.

A large real-world app: do you have one I can use? If not, PetClinic only for now.

**Done when:** `results.md` has numbers for the 3 baselines plus the matrix.

---

## Phase 1: Core loop (~2 weeks)

### Flow
```
jblaze run
  ├─ classpath: run a Gradle init script once → print runtimeClasspath + annotationProcessor path → cache in
  │             .jblaze/cp.txt, keyed on a hash of build.gradle*/settings.gradle*/gradle.properties
  ├─ initial build: ./gradlew classes (just once)
  ├─ launch app:  java -javaagent:agent.jar=port -cp build/classes:build/resources:<cp> <mainClass>
  ├─ watch src/main/java (java.nio WatchService, 20 ms debounce)
  └─ on change:
       compile changed files in-process (JavacTask, in-memory output, -processorpath for Lombok)
       write .class to build/classes            ← so a restart picks it up too
       send (name, bytes)[] to the agent         ← one localhost socket, length-prefixed frames
       agent: redefineClasses → "ok" | "unsupported: <reason>"
       ok          → print [swap 64ms]
       unsupported → kill + relaunch → print [restart 4.1s: schema change in OwnerController]
```

### Pieces
| Piece | Notes | Size estimate |
|---|---|---|
| `Classpath` | Gradle init script + cache. Skip Tooling API until the init script falls short. | ~60 lines |
| `Compiler` | `JavaCompiler.getTask` with a `ForwardingJavaFileManager` that captures output bytes. Reuse one compiler instance. Errors print like javac and stop there (no swap). | ~120 |
| `Watcher` | WatchService, recursive register, debounce. | ~60 |
| `AppProcess` | `ProcessBuilder` with `inheritIO`, launch/kill/relaunch, finding the main class (read `springBoot.mainClass`, else scan for `@SpringBootApplication`). | ~80 |
| `agent` | `premain` → ServerSocket on the port; on each batch, look up loaded classes by name (across all loaders), `redefineClasses`, reply. Classes not loaded yet need nothing, since the disk write covers them. | ~80 |
| `Main` | `run` / `restart`; any other command passes through to `./gradlew`. | ~40 |

Gotchas to handle in Phase 1:
- **DevTools must be off** under jblaze (its RestartClassLoader hides classes from us). Pass `-Dspring.devtools.restart.enabled=false`.
- **Inner/anonymous/lambda classes**: one source file → many `.class` files; send them all in one `redefineClasses` call.
  Lambda changes often alter synthetic method names, so a stock JDK rejects them → restart. Accept that in Phase 1, measure how often it happens.
- **Deleted files**: delete the `.class` files and restart.
- **Resources** (`src/main/resources`): copy them on change. Templates are live if `spring.thymeleaf.cache=false`, which jblaze sets.
- **Dependent files**: Phase 1 recompiles only the changed file. A signature change fails redefine → restart, which is correct, just slower. ABI hashing is Phase 2.

### Test (the one check)
`bench/bench.sh jblaze`, the Phase 0 harness, is the acceptance test. Plus one unit test for `Compiler`
(compile a string → bytes → `defineClass` → call the method) since that's the core logic.

**Done when:** editing a PetClinic controller method shows up in under 200 ms median in `bench.sh jblaze`, and a
signature change falls back to restart automatically and prints why.

### Order of work
1. `agent` + a hand-run test (compile by hand, push bytes over the socket, see the change). Proves the riskiest part first.
2. `Compiler` + unit test.
3. `Classpath`, `AppProcess`, `Watcher`, `Main`, wiring it up.
4. Run the benchmark, write the numbers.

---

## Status
- Phase 0 done: bench/results.md.
- Phase 1 done: method-body swap 114 ms median, restart fallback works. See bench/results-phase0-1.md.
- Phase 3 done: mapping changes, new beans, `@Value` and `@ConfigurationProperties` apply live in ~80–120 ms; framework config restarts. See bench/results.md and README.md.
- Phase 2 done (plus): ABI-based dependent recompiles, constants, Lombok/MapStruct, multi-module Gradle, Maven, live new endpoints on a stock JDK. See README.md.
- Phase 4 done: in-place restarts via DevTools (added when missing), ~0.55 s instead of ~3.2 s; C1-only JIT for fresh JVMs. AppCDS dropped (JVM refuses non-empty classpath dirs).
- Phase 5 done: `jblaze test` with a warm test JVM, cached Spring contexts, affected-test selection; ~12× faster than Gradle per edit on the sample.
- Phase 6 done: install.sh, `jblaze idea` (IntelliJ run configurations), README. No Gradle/Maven plugins (no build changes needed).

## Questions before starting
1. Gradle confirmed? (Only Gradle is installed.)
2. Is there a real app of yours to benchmark against besides PetClinic?
3. Are you OK with jblaze leaning on JBR + HotswapAgent for the Spring swap layer if Phase 0 shows it works?
