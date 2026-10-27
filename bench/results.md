| mode | change | median ms | p90 ms | fails | restarts |
|---|---|---|---|---|---|
| gradle-restart | cold start | 6636 | 6636 | 0/1 | - |
| gradle-restart | method body | 6494 | 6908 | 0/10 | - |
| devtools | cold start | 6274 | 6274 | 0/1 | - |
| devtools | method body | 2860 | 2871 | 0/10 | - |
| devtools | mapping path | 2844 | 2854 | 0/3 | - |
| devtools | new endpoint | 2835 | 2859 | 0/3 | - |
| devtools | new bean | 2845 | 2898 | 0/3 | - |
| devtools | @Value property | 2841 | 2861 | 0/3 | - |
| devtools | @ConfigurationProperties | 2853 | 2853 | 0/3 | - |
| devtools | framework property | 2842 | 2868 | 0/3 | - |
| hotswapagent | cold start | 4668 | 4668 | 0/1 | - |
| hotswapagent | method body | 1123 | 1188 | 0/10 | - |
| hotswapagent | mapping path | 2714 | 2807 | 0/3 | - |
| hotswapagent | new endpoint | 2764 | 2821 | 0/3 | - |
| hotswapagent | new bean | 2395 | 2395 | 0/3 | - |
| hotswapagent | @Value property | - | - | 3/3 | - |
| hotswapagent | @ConfigurationProperties | - | - | 3/3 | - |
| hotswapagent | framework property | - | - | 3/3 | - |

Setup as in `results-phase0-1.md` (spring-petclinic, Spring Boot 4.1, 16 cores, warm Gradle daemon).
Each change type has its own controller, so cases don't disturb each other. "restarts" counts jblaze restarts
during the case (jblaze modes only). gradle-restart only measures method bodies.

## Findings
- **jblaze on a stock JDK applies 6 of 7 change types live in 77–111 ms** (method body, mapping path, new
  endpoint, new bean, `@Value`, `@ConfigurationProperties`). DevTools takes ~2.85 s for each.
- **Restarts happen in place (Phase 4)**: framework properties restart on purpose, now in **578 ms** (DevTools'
  Restarter, app up in ~0.55 s) instead of 3.2 s for a fresh JVM. Cold start 3.6 s (C1-only JIT for the app).
- **Framework properties that are read per use now apply live** (agent allowlist): `logging.level.*` through
  the LoggingSystem in **88 ms** and `spring.web.error.include-message` (on PetClinic's error page, both ways)
  in **84 ms**, instead of a 0.6 s restart. Other framework config (`server.tomcat.*` here) still restarts on
  purpose: its values were read once at startup into objects like Tomcat's connector.
- JBR + HotswapAgent: in-place restarts take ~1.15 s and endpoint changes ~0.4–0.5 s, so the stock JDK is as fast
  or faster in every case here.
- HotswapAgent alone doesn't apply any property change (3/3 fails in each property case).
- Fixed race (Phase 3): new config values are computed on a copy of the property sources, so an app about to
  restart never serves new config with old beans.
- AppCDS was tried and dropped: the JVM refuses to archive with non-empty directories on the classpath.

## `jblaze test` (Phase 5), samples/multimodule
Edit a `core` class, rerun the affected app tests (`@SpringBootTest`, Spring context included):

| | per edit |
|---|---|
| `./gradlew :app:test --tests GreetControllerTest` (warm daemon, 5 runs) | 1808–1866 ms |
| `jblaze test`: compile + swap, then run 2 affected test classes | ~115–150 ms + 8–16 ms |

Spring contexts stay cached for body-only changes; after a config change they rebuild in ~100 ms in the warm
JVM. A first run (cold JVM, contexts built) took 1.6 s. On Maven PetClinic (`VetController*`): first run 2.1 s,
then 60 ms swap + 34 ms test run per edit.

Other change types and correctness: `samples/e2e.sh` and `samples/e2e-test.sh` (both pass).
| jblaze | cold start | 3881 | 3881 | 0/1 | - |
| jblaze | method body | 114 | 135 | 0/10 | 0/10 |
| jblaze | mapping path | 114 | 118 | 0/3 | 0/3 |
| jblaze | new endpoint | 87 | 100 | 0/3 | 0/3 |
| jblaze | new bean | 87 | 92 | 0/3 | 0/3 |
| jblaze | @Value property | 78 | 81 | 0/3 | 0/3 |
| jblaze | @ConfigurationProperties | 80 | 84 | 0/3 | 0/3 |
| jblaze | log level | 88 | 91 | 0/3 | 0/3 |
| jblaze | error detail (spring.web.error) | 84 | 84 | 0/3 | 0/3 |
| jblaze | framework property | 612 | 618 | 0/3 | 3/3 |
| jblaze-jbr | cold start | 4909 | 4909 | 0/1 | - |
| jblaze-jbr | method body | 111 | 140 | 0/10 | 0/10 |
| jblaze-jbr | mapping path | 414 | 479 | 0/3 | 0/3 |
| jblaze-jbr | new endpoint | 522 | 893 | 0/3 | 0/3 |
| jblaze-jbr | new bean | 87 | 89 | 0/3 | 0/3 |
| jblaze-jbr | @Value property | 80 | 97 | 0/3 | 0/3 |
| jblaze-jbr | @ConfigurationProperties | 79 | 81 | 0/3 | 0/3 |
| jblaze-jbr | log level | 77 | 82 | 0/3 | 0/3 |
| jblaze-jbr | error detail (spring.web.error) | 77 | 86 | 0/3 | 0/3 |
| jblaze-jbr | framework property | 1138 | 1405 | 0/3 | 3/3 |
