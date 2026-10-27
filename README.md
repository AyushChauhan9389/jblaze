# jblaze

A fast development loop for Spring Boot. `jblaze run` replaces `./gradlew bootRun` / `./mvnw spring-boot:run`:
every save is compiled in-process and applied to the running app, usually in ~100 ms, and when a change can't be
applied live the app restarts in place in ~0.5 s. `jblaze test` keeps a warm test JVM and reruns only the tests a
save affects, in milliseconds instead of seconds.

```
[jblaze] swap 86ms: swapped 1
[jblaze] swap 43ms: swapped 1; remapped OwnerController (6 endpoints)
[jblaze] swap 33ms: swapped 0; replaced OwnerController (7 endpoints)
[jblaze] swap 58ms: swapped 0; new bean visitService
[jblaze] swap 45ms: rebound app.page-size in ownerProps
[jblaze] swap 408ms: constant changed in Messages, recompiled everything; swapped 8
[jblaze] compile failed, app unchanged
[jblaze] restart (server.port is used by ...ServerProperties): in place, app up in 509ms
```

## Install and use

```sh
./install.sh                     # builds jblaze, links it into ~/.local/bin (or: ./install.sh <bin dir>)

cd your-spring-boot-project
jblaze run [app]                 # 'r' + Enter restarts in place, 'R' starts a fresh JVM
jblaze test [-m module] [Class*] # Enter reruns, 'a' runs all, 'r' restarts the test JVM
jblaze idea                      # adds "jblaze run" / "jblaze test" run configurations to .run/ for IntelliJ
jblaze <task or goal>...         # anything else goes to ./gradlew or ./mvnw
```

- **Gradle or Maven**, single- or multi-module, no build changes. `[app]`/`-m` pick a module when there are
  several (Gradle project path like `:app`, or Maven artifactId); by default the Spring Boot app.
- Gradle: jblaze injects an init script that builds every module once and reports classpaths and settings.
  Maven: two `mvn` runs (three for tests), then everything is in-process.
- The app runs like `bootRun`/`spring-boot:run` would, `developmentOnly` dependencies included: e.g. with
  `spring-boot-docker-compose` it starts your Compose services (`SPRING_DOCKER_COMPOSE_ENABLED=false` to skip).
- **Restarts** happen in place through Spring Boot DevTools: same JVM, only the app's classes reload. If the app
  doesn't have DevTools, jblaze adds the matching version (`JBLAZE_DEVTOOLS=off` to opt out); DevTools' own
  file watching, LiveReload and property defaults stay off. Without it, restarts start a fresh JVM (C1-only JIT,
  ~12% faster startup).
- Set `JBLAZE_JAVA` to a [JetBrains Runtime](https://github.com/JetBrains/JetBrainsRuntime) `java` to also apply
  structural changes to any class. If that runtime has HotswapAgent in `lib/hotswap/`, jblaze turns it on too.
  It costs ~1.3 s more startup; with in-place restarts the stock JDK is as fast or faster in every case measured.

Requires Java 21 to run jblaze; the app's own `--release` is respected.

## `jblaze run`: what happens on save

| Change | Stock JDK | JetBrains Runtime |
|---|---|---|
| Method body (any module) | swap | swap |
| `@GetMapping` etc. path/params on an existing method | swap + re-register the controller's mappings | swap (HotswapAgent re-maps) |
| New/removed method on a **controller** | replace the controller with a renamed copy | swap + re-map |
| New/removed method or field elsewhere | restart in place | swap |
| A class's signatures change | recompile the files that use it, across modules | same |
| A compile-time constant changes | recompile everything (javac inlines constants) | same |
| The change breaks another file | compile error, app unchanged | same |
| New `@Component`/`@Service`/`@Controller` in a scanned package | register the bean (and its mappings) | same |
| `application*.properties`/`.yml` key used by `@Value` field/setter | re-inject (relaxed names match) | same |
| … used by a JavaBean `@ConfigurationProperties` in your packages | rebind | same |
| `logging.level.*` (loggers, `root`, groups like `web`/`sql`; a removed key inherits again) | applied through Spring Boot's LoggingSystem | same |
| `spring.web.error.include-*` (Boot 3: `server.error.include-*`) | rebind in place: read on every error | same |
| … used by other framework config (`server.*`, `spring.datasource.*`, `spring.jackson.*`…), a constructor, a record, or nothing jblaze can find | restart in place (~0.55 s) | restart in place (~1.1 s) |
| Templates, static files | copied (Thymeleaf cache is off) | same |
| Other annotation changes (`@Transactional`, `@Value` on a field…), constructor changes, new fields on beans | restart in place | left to HotswapAgent's Spring plugin (restart if JBR has no HotswapAgent) |
| New `@Configuration`, `@ControllerAdvice`, `@Entity`, Spring Data repository; message bundles; deleted files | restart in place | restart in place |

Annotation processors run as in the real build: Lombok, and MapStruct with its generated implementations
regenerated and swapped.

## `jblaze test`

One test JVM for the whole session, started once. On each save:

1. Changed main and test sources compile in-process (dependents too, as above).
2. App classes are swapped into the test JVM. Spring's cached test contexts are kept when only method bodies
   changed, and dropped otherwise (annotations, new classes, config), so the next run builds fresh ones in the
   still-warm JVM. A swap the JVM refuses restarts the test JVM.
3. Affected tests run: those that reach a changed class through their references, plus every test that loads a
   Spring context when anything reached is Spring-managed (it can reach the change at runtime).

Test classes are loaded in the app's class loader, as in the build, so package-private access works. A changed
test class that's already loaded is defined again under a new name (`FooTest_jb2`) together with the tests
that use it, so new test methods need no restart; reports use the original names. The JUnit Platform launcher is
added when the build doesn't declare it. Class patterns (`OwnerController*`) limit the session.

## How it works

```
jblaze (cli/)                                     app JVM / test JVM
  Project / MavenImport: modules, classpaths,       -javaagent:agent.jar
    processors, test settings (once)                  ├─ Agent: socket, redefineClasses, annotation snapshot
  Watcher on every module's src dirs                  │    before/after, renamed controller copies,
  Builder: javac in-process per module                │    in-place restart (DevTools Restarter)
    staging → ABI check → dependents → overlay        ├─ spring/ (via spring.factories in the agent jar)
  build/jblaze/classes, test-classes first on cp      │    ContextCapture, SpringLayer, MvcMappings
  AgentLink: 'C' classes, 'R' resources,              └─ test/ TestRunner: JUnit in-process, renamed test
    'T' tests, 'X' restart in place                        generations, Spring test context reset
```

- **Builder**: changed files compile into a staging dir. jblaze compares each class's ABI (non-private
  signatures, supertypes, thrown exceptions, constants; read with `classfile/ClassFile`) with the version in use,
  recompiles the files that reference changed classes, and commits only if every round compiled. Files from a
  failed build stay pending until a build succeeds.
- **Overlay**: jblaze never writes into Gradle's or Maven's output. Its own dirs are wiped on every start, so
  deleted sources can't leave orphan classes behind.
- **Agent**: decides with the real Spring state, not text heuristics. It diffs each redefined class's runtime
  annotations, looks up which beans use a changed property, and checks proxies. New config values are computed
  on a copy of the property sources, so an app about to restart never serves new config with old beans. After an
  in-place restart, only classes of the live class loader are redefined.
- **Controller replacement** (stock JDK): when the JVM refuses a structural change to a controller that no bean
  depends on, the new version is renamed (`OwnerController_jblaze1`), defined in the same package and class
  loader, and takes over the bean name and mappings. Later edits to that file are renamed to the current copy.
- A change that arrives while the app is (re)starting waits for the context, then applies only what startup
  didn't already pick up.

## Development

```sh
./gradlew test jar         # agent + cli, 40 tests (Spring tests boot a real app; Lombok/MapStruct run for real)
samples/e2e.sh             # jblaze run on samples/multimodule: modules, Lombok, MapStruct, constants, errors, endpoints
samples/e2e-test.sh        # jblaze test on the same sample: selection, failing/fixed tests, new tests, contexts
bench/setup-demo.sh        # clones spring-petclinic into demo/ (the commit and edits the benchmarks used)
bench/bench.sh <mode> [n]  # timing on spring-petclinic in demo/
```

Modes: `gradle-restart`, `devtools`, `hotswapagent`, `jblaze`, `jblaze-jbr`. See `bench/results.md`.

No Gradle or Maven plugin: jblaze needs no build changes, so a plugin would only add setup.

## Known limits

- Maven: processor jars listed in `annotationProcessorPaths` are used without their own dependencies, from
  `~/.m2/repository`.
- `@Value` keys in environment-variable form (`${MY_KEY}`) aren't matched to `my.key` and restart instead.
- Beans that copy a property into derived state at construction keep the old derived value. `r` restarts.
- `processResources` filtering/expansion isn't applied to copied resources.
- `jblaze test`: tests whose Spring configuration names test classes by string (e.g. a `@ComponentScan` of a test
  package) can see both a test class and its renamed copy after an edit; `r` restarts the test JVM.
- `jblaze idea` writes IntelliJ "Shell Script" run configurations; not verified inside IntelliJ here.
- AppCDS isn't used: the JVM refuses to archive with non-empty directories on the classpath, which an app under
  development always has.
