#!/usr/bin/env bash
# End-to-end check of jblaze on samples/multimodule: a change in another module, Lombok, MapStruct, an inlined
# constant, a breaking signature change and its fix, and new endpoints on a stock JDK.
# usage: samples/e2e.sh        (JBLAZE_JAVA picks the JDK, as for jblaze itself)
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd); cd "$ROOT/samples/multimodule"
URL=http://localhost:8081
LOG=$ROOT/samples/e2e.log
CORE=core/src/main/java/sample/core
APP=app/src/main/java/sample/app
BACKUP=$(mktemp -d)
FAILS=0
JB_PID=

cp -r core/src "$BACKUP/core"; cp -r app/src "$BACKUP/app"
cleanup() {
  [[ -n $JB_PID ]] && kill -- -"$JB_PID" 2>/dev/null || true
  rm -rf core/src app/src; cp -r "$BACKUP/core" core/src; cp -r "$BACKUP/app" app/src; rm -rf "$BACKUP"
}
trap cleanup EXIT

now() { date +%s%3N; }
get() { curl -sf --max-time 1 "$URL$1" 2>/dev/null || true; }
wait_url() { # path, expected text
  local deadline=$(( $(now) + 30000 ))
  while (( $(now) < deadline )); do [[ $(get "$1") == *"$2"* ]] && return 0; sleep 0.02; done
  return 1
}
# first thing jblaze did after log line $1: a swap, a restart or a compile failure
action() { tail -n +"$(( $1 + 1 ))" "$LOG" | grep -m1 -E '^\[jblaze\] (swap|restart|compile failed)' || true; }
wait_action() { # from line -> prints the action line
  local deadline=$(( $(now) + 30000 )) a
  while (( $(now) < deadline )); do
    a=$(action "$1")
    [[ -n $a ]] && { sleep 0.1; action "$1"; return; } # re-read: the line may still have been half written
    sleep 0.02
  done
}
edit() { # file, from, to (literal text)
  grep -qF -- "$2" "$1" || { echo "edit: '$2' not in $1"; exit 2; }
  FROM=$2 TO=$3 perl -0pi -e 's/\Q$ENV{FROM}\E/$ENV{TO}/' "$1"
}

# step name file from to path expected-text action-pattern
step() {
  local n t0 a
  n=$(wc -l < "$LOG"); t0=$(now)
  edit "$2" "$3" "$4"
  a=$(wait_action "$n")
  if [[ $a =~ $7 ]] && wait_url "$5" "$6"; then
    echo "ok   $1 ($(( $(now) - t0 )) ms) ${a:0:140}"
  else
    echo "FAIL $1: expected /$7/ and '$6' at $5, got '$a' and '$(get "$5")'"; FAILS=$((FAILS + 1))
  fi
}

setsid "$ROOT/jblaze" run > "$LOG" 2>&1 & JB_PID=$!
wait_url /greet "Hello you" || { echo "app did not start, see $LOG"; exit 1; }
echo "started"

step "change in another module (core)" $CORE/Greeter.java \
  'return Messages.PREFIX + name;' 'return Messages.PREFIX + name + "!";' /greet 'Hello you!' '\] swap'
step "Lombok class" $CORE/Person.java '"person " + getName()' '"human " + getName()' /person 'human Ada' '\] swap'
step "MapStruct mapping (generated impl)" $CORE/PersonMapper.java 'constant = "v1"' 'constant = "v2"' \
  /person 'label=v2' '\] swap'
step "inlined constant" $CORE/Messages.java '"Hello "' '"Hey "' /greet 'Hey you!' 'recompiled everything'
step "breaking signature change" $CORE/Greeter.java \
  'greet(String name) {
		return Messages.PREFIX + name + "!";' \
  'greet(String name, int times) {
		return Messages.PREFIX + name + "!".repeat(times);' /greet 'Hey you!' 'compile failed'
step "fix the caller (other module)" $APP/GreetController.java 'greeter.greet("you")' 'greeter.greet("you", 2)' \
  /greet 'Hey you!!' '\] (swap|restart)'
step "new endpoint" $APP/GreetController.java '	@GetMapping("/person")' '	@GetMapping("/new")
	String fresh() {
		return "fresh-1";
	}

	@GetMapping("/person")' /new 'fresh-1' '\] swap'
step "edit the replaced controller" $APP/GreetController.java '"fresh-1"' '"fresh-2"' /new 'fresh-2' '\] swap'
step "another new endpoint" $APP/GreetController.java '	@GetMapping("/person")' '	@GetMapping("/newer")
	String newer() {
		return "newer";
	}

	@GetMapping("/person")' /newer 'newer' '\] swap'
for path in /greet /person /new; do
  [[ -n $(get $path) ]] && echo "ok   $path still answers: $(get $path)" || { echo "FAIL $path"; FAILS=$((FAILS + 1)); }
done

echo "restarts: $(grep -c '^\[jblaze\] restart' "$LOG" || true)"
(( FAILS == 0 )) && echo "PASS" || { echo "$FAILS FAILED (log: $LOG)"; exit 1; }
