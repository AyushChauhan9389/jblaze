| mode | change | median ms | p90 ms | fails |
|---|---|---|---|---|
| gradle-restart | cold start | 9475 | 9475 | 0/1 |
| gradle-restart | method body | 6292 | 6999 | 0/10 |
| devtools | cold start | 6092 | 6092 | 0/1 |
| devtools | method body | 2848 | 2867 | 0/10 |
| devtools | new endpoint | 2831 | 2833 | 0/3 |
| devtools | new bean | 2850 | 2862 | 0/3 |
| devtools | property | 2812 | 2822 | 0/3 |
| hotswapagent | cold start | 4815 | 4815 | 0/1 |
| hotswapagent | method body | 1151 | 1237 | 0/10 |
| hotswapagent | new endpoint | 2824 | 2849 | 0/3 |
| hotswapagent | new bean | 2358 | 2457 | 0/3 |
| hotswapagent | property | - | - | 3/3 |

Extra: warm `./gradlew classes -q` after a one-line edit = **~650 ms** (5 runs, 640–681).

## Setup
spring-petclinic (Spring Boot 4.1), 16 cores, Gradle 9.4.1 (warm daemon), Temurin 21 / JBR 21.0.11 + HotswapAgent 2.0.3.
Time = file written → `curl` returns the new text. `devtools` and `hotswapagent` use `./gradlew classes --continuous`.
demo/ edits: wrapper pinned to the cached 9.4.1, toolchain 17 → 21 (only JDK installed).

## Findings
1. **Gradle is the bottleneck once the restart is gone.** HotswapAgent method body = 1150 ms, of which ~650 ms is the
   warm Gradle compile alone. The other ~500 ms is the `--continuous` watch delay plus the swap; I haven't split those yet.
2. **HotswapAgent does handle new endpoints and new beans** (no failures), but at ~2.4–2.8 s, which is no better than DevTools.
   Its Spring plugin reloads on a timer/batch, not instantly.
3. **HotswapAgent misses property changes** (`@Value` not rebound, 3/3 fails). DevTools gets them via restart.
4. **DevTools is a flat ~2.8 s for everything**: compile plus a restart of a small app. That grows with app size; HotswapAgent mostly doesn't.

## What this means for jblaze
- The biggest win is the **Phase 1 compile loop**: replacing ~1.1 s of Gradle + watch with in-process javac should take a
  method-body edit from 1150 ms toward ~100–200 ms. Rerun `bench.sh jblaze` to confirm.
- Run on **JBR + HotswapAgent** for the in-JVM side rather than building structural swap from scratch (Phase 2 shrinks).
- Phase 3 work that still needs doing: fast endpoint/bean refresh (HA takes 2.4–2.8 s) and property rebinding (HA doesn't do it).
- Not measured yet: entity changes, and a large real-world app (needed to see restart cost scale).
| jblaze | cold start | 3960 | 3960 | 0/1 |
| jblaze | method body | 114 | 117 | 0/10 |
| jblaze | new endpoint | 3252 | 3255 | 0/3 |
| jblaze | new bean | 3275 | 3339 | 0/3 |
| jblaze | property | 3284 | 3476 | 0/3 |
| jblaze-jbr | cold start | 5675 | 5675 | 0/1 |
| jblaze-jbr | method body | 131 | 226 | 0/10 |
| jblaze-jbr | new endpoint | 475 | 602 | 0/3 |
| jblaze-jbr | new bean | 4873 | 5011 | 0/3 |
| jblaze-jbr | property | 4801 | 4913 | 0/3 |

## Phase 1: jblaze (rows above)
- **Method body: 114 ms (stock JDK) / 131 ms (JBR)**, against 1151 ms HotswapAgent + Gradle and 2848 ms DevTools.
  The jblaze log shows 40–100 ms from save to swap reply; the rest is the bench's curl polling.
- **JBR + HotswapAgent new endpoint: 475 ms** (HotswapAgent alone: 2824 ms). jblaze swaps, HotswapAgent re-registers the mapping.
- Restarts (stock JDK: endpoint/bean/property; JBR: bean/property) take ~3.3 s stock and ~4.8 s on JBR.
  HotswapAgent makes startup ~1.5 s slower, so on JBR restarts cost more.
- HotswapAgent doesn't pick up new classes in jblaze's overlay dir, so new beans restart. That's Phase 3 (bean registration).
