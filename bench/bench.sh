#!/usr/bin/env bash
# Measures save -> visible-in-HTTP latency on demo/ (spring-petclinic), one change type at a time.
# usage: bench/bench.sh <gradle-restart|devtools|hotswapagent|jblaze|jblaze-jbr> [iterations]
set -euo pipefail
MODE=$1; N=${2:-10}; FEW=3; TIMEOUT=60
ROOT=$(cd "$(dirname "$0")/.." && pwd); cd "$ROOT/demo"
BENCH=src/main/java/org/springframework/samples/petclinic/bench
PROPS=src/main/resources/application.properties
URL=http://localhost:8080
LOG=$ROOT/bench/app-$MODE.log
RESULTS=$ROOT/bench/results.md
JBR=${JBR:-$(mise where java@jetbrains-21.0.11-b1163.116)}
MAIN=org.springframework.samples.petclinic.PetClinicApplication
CONT_PID=
JB_PID=

now() { date +%s%3N; }
app_pid() { ss -ltnpH 'sport = :8080' | grep -oP 'pid=\K\d+' | head -1 || true; }
wait_for() { # url, expected text ("!text": must not contain it) -> 0 when that holds within TIMEOUT
  local deadline=$(( $(now) + TIMEOUT * 1000 )) body
  while (( $(now) < deadline )); do
    body=$(curl -s --max-time 1 "$1" 2>/dev/null || true) # no -f: error pages are what some cases check
    if [[ $2 == '!'* ]]; then
      [[ -n $body && $body != *"${2:1}"* ]] && return 0
    else
      [[ $body == *"$2"* ]] && return 0
    fi
    sleep 0.01
  done
  return 1
}

# --- the bench sources: one controller per change type, so cases don't disturb each other ---

java_file() { # name, body (imports are shared)
  cat > $BENCH/$1.java <<EOF
package org.springframework.samples.petclinic.bench;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

$2
EOF
}

BODY=start
write_body() { java_file BodyController "@RestController
class BodyController {

	@GetMapping(\"/bench\")
	String bench() {
		return \"$BODY\";
	}

}"; }

write_mapping() { # $1 = path suffix
  java_file MappingController "@RestController
class MappingController {

	@GetMapping(\"/bench/m$1\")
	String moved() {
		return \"moved-$1\";
	}

}"; }

write_endpoints() { # $1 = how many extra endpoints
  local methods="" i
  for ((i = 1; i <= $1; i++)); do
    methods+="
	@GetMapping(\"/bench/new$i\")
	String new$i() {
		return \"new-$i\";
	}
"
  done
  java_file EndpointController "@RestController
class EndpointController {

	@GetMapping(\"/bench/e0\")
	String e0() {
		return \"e0\";
	}
$methods
}"; }

write_fixed() {
  java_file PropController "@RestController
class PropController {

	@Value(\"\${bench.prop:unset}\")
	String prop;

	private final BenchProps props;

	PropController(BenchProps props) {
		this.props = props;
	}

	@GetMapping(\"/bench/prop\")
	String prop() {
		return prop;
	}

	@GetMapping(\"/bench/cprop\")
	String cprop() {
		return props.getMsg();
	}

}"
  java_file BenchProps "@Component
@ConfigurationProperties(\"bench.cp\")
class BenchProps {

	private String msg = \"unset\";

	public String getMsg() {
		return msg;
	}

	public void setMsg(String msg) {
		this.msg = msg;
	}

}"; }

PROP=unset; CPROP=unset; FW=; LOGLEVEL=INFO; ERRMSG=never
write_props() { # one write, like an editor save
  local content
  content=$(cat "$ROOT/bench/.props.bak"
    echo "management.endpoint.env.show-values=ALWAYS"
    echo "bench.prop=$PROP"
    echo "bench.cp.msg=$CPROP"
    echo "logging.level.org.springframework.samples.petclinic.owner=$LOGLEVEL"
    echo "spring.web.error.include-message=$ERRMSG"
    [[ -z $FW ]] || echo "server.tomcat.max-swallow-size=$FW")
  printf '%s\n' "$content" > $PROPS
}

# --- the app under each mode ---

start_app() {
  case $MODE in
    gradle-restart|devtools) setsid ./gradlew bootRun -q > "$LOG" 2>&1 & ;;
    hotswapagent)
      [[ -n ${CP:-} ]] || CP=$(./gradlew -q -I "$ROOT/bench/cp.init.gradle" benchCp | tail -1)
      # ponytail: hotswap-agent.properties rides in on the classpath from bench/
      setsid "$JBR/bin/java" -XX:+AllowEnhancedClassRedefinition -XX:HotswapAgent=fatjar \
        -cp "$ROOT/bench/ha:$CP" $MAIN > "$LOG" 2>&1 & ;;
    jblaze) setsid "$ROOT/jblaze" run > "$LOG" 2>&1 & JB_PID=$! ;;
    jblaze-jbr) JBLAZE_JAVA=$JBR/bin/java setsid "$ROOT/jblaze" run > "$LOG" 2>&1 & JB_PID=$! ;;
  esac
}

stop_app() {
  local pid; pid=$(app_pid)
  [[ -n $pid ]] && kill "$pid" 2>/dev/null
  while [[ -n $(app_pid) ]]; do sleep 0.05; done
}

cleanup() {
  [[ -n $CONT_PID ]] && kill -- -"$CONT_PID" 2>/dev/null
  [[ -n $JB_PID ]] && kill -- -"$JB_PID" 2>/dev/null
  stop_app || true
  rm -rf $BENCH
  cp "$ROOT/bench/.props.bak" $PROPS
}

# --- measuring ---

stats() { # reads ms values on stdin -> "median p90"
  sort -n | awk '{a[NR]=$1} END {if (!NR) {print "- -"; exit}
    print a[int((NR+1)/2)], a[int(NR*0.9+0.999)]}'
}

restarts() { # restarts jblaze has logged so far; "-" for other modes
  [[ $MODE == jblaze* ]] && { grep -c '\] restart (' "$LOG" || true; } || echo -
}

record() { # case, ms list, fails, restarts
  read -r med p90 < <(tr ' ' '\n' <<<"$2" | grep . | stats)
  local row="| $MODE | $1 | $med | $p90 | $3 | $4 |"
  echo "$row"; echo "$row" >> "$RESULTS"
}

# run_case name iterations edit_fn url_fn expect_fn  (each fn gets the iteration number)
run_case() {
  local name=$1 n=$2 times="" fails=0 i t0 r0 r1
  r0=$(restarts)
  for ((i = 1; i <= n; i++)); do
    t0=$(now)
    $3 "$i"
    [[ $MODE == gradle-restart ]] && { stop_app; start_app; }
    if wait_for "$($4 "$i")" "$($5 "$i")"; then times+=" $(( $(now) - t0 ))"; else ((fails++)) || true; fi
  done
  sleep 0.5; r1=$(restarts) # jblaze logs a restart as the app comes back, right when the change shows
  [[ $r0 == - ]] && r1=- || r1="$((r1 - r0))/$n"
  record "$name" "$times" "$fails/$n" "$r1"
}

body_edit() { BODY="body-$1-$RANDOM"; write_body; }
body_url() { echo $URL/bench; }
body_expect() { echo "$BODY"; }

mapping_edit() { write_mapping "$1"; }
mapping_url() { echo $URL/bench/m$1; }
mapping_expect() { echo "moved-$1"; }

endpoint_edit() { write_endpoints "$1"; }
endpoint_url() { echo $URL/bench/new$1; }
endpoint_expect() { echo "new-$1"; }

bean_edit() {
  java_file NewBean$1 "@RestController
class NewBean$1 {

	@GetMapping(\"/bench/bean$1\")
	String hello() {
		return \"bean-$1\";
	}

}"; }
bean_url() { echo $URL/bench/bean$1; }
bean_expect() { echo "bean-$1"; }

prop_edit() { PROP="prop-$1"; write_props; }
prop_url() { echo $URL/bench/prop; }
prop_expect() { echo "prop-$1"; }

cprop_edit() { CPROP="cp-$1"; write_props; }
cprop_url() { echo $URL/bench/cprop; }
cprop_expect() { echo "cp-$1"; }

fw_edit() { FW="$((1 + $1))MB"; write_props; }
fw_url() { echo $URL/actuator/env/server.tomcat.max-swallow-size; }
fw_expect() { echo "\"value\":\"$((1 + $1))MB\""; }

LEVELS=(DEBUG TRACE WARN)
log_edit() { LOGLEVEL=${LEVELS[$(( ($1 - 1) % 3 ))]}; write_props; }
log_url() { echo $URL/actuator/loggers/org.springframework.samples.petclinic.owner; }
log_expect() { echo "\"configuredLevel\":\"${LEVELS[$(( ($1 - 1) % 3 ))]}\""; }

# odd iterations show the exception message on PetClinic's error page, even ones hide it again
err_edit() { (( $1 % 2 )) && ERRMSG=always || ERRMSG=never; write_props; }
err_url() { echo $URL/oups; }
err_expect() { (( $1 % 2 )) && echo "Expected: controller used" || echo "!Expected: controller used"; }

# --- main ---
[[ -f "$ROOT/bench/.props.bak" ]] || cp $PROPS "$ROOT/bench/.props.bak"
trap cleanup EXIT
[[ -z $(app_pid) ]] || { echo "port 8080 busy"; exit 1; }
[[ -f $RESULTS ]] || printf '| mode | change | median ms | p90 ms | fails | restarts |\n|---|---|---|---|---|---|\n' > "$RESULTS"

mkdir -p $BENCH
write_body; write_mapping 0; write_endpoints 0; write_fixed; write_props
./gradlew classes -q
t0=$(now); start_app
wait_for $URL/bench start || { echo "app did not start, see $LOG"; exit 1; }
record "cold start" "$(( $(now) - t0 ))" "0/1" "-"

if [[ $MODE == devtools || $MODE == hotswapagent ]]; then
  setsid ./gradlew classes --continuous -q > "$ROOT/bench/continuous-$MODE.log" 2>&1 &
  CONT_PID=$!
  sleep 5 # let the continuous build settle
fi
if [[ $MODE != gradle-restart ]]; then
  body_edit 0; wait_for "$(body_url)" "$(body_expect)" || true # warmup, not recorded
fi

run_case "method body" "$N" body_edit body_url body_expect
[[ $MODE == gradle-restart ]] && exit 0
run_case "mapping path" $FEW mapping_edit mapping_url mapping_expect
run_case "new endpoint" $FEW endpoint_edit endpoint_url endpoint_expect
run_case "new bean" $FEW bean_edit bean_url bean_expect
run_case "@Value property" $FEW prop_edit prop_url prop_expect
run_case "@ConfigurationProperties" $FEW cprop_edit cprop_url cprop_expect
run_case "log level" $FEW log_edit log_url log_expect
run_case "error detail (spring.web.error)" $FEW err_edit err_url err_expect
run_case "framework property" $FEW fw_edit fw_url fw_expect
