#!/usr/bin/env bash
# End-to-end check of `jblaze test` on samples/multimodule: affected-test selection across modules, a failing
# then fixed test, a new test method without a JVM restart, config changes, and a structural change.
# usage: samples/e2e-test.sh        (JBLAZE_JAVA picks the JDK)
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd); cd "$ROOT/samples/multimodule"
LOG=$ROOT/samples/e2e-test.log
CORE=core/src/main/java/sample/core
APP=app/src/main/java/sample/app
TEST=app/src/test/java/sample/app
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
edit() { # file, from, to (literal text)
  grep -qF -- "$2" "$1" || { echo "edit: '$2' not in $1"; exit 2; }
  FROM=$2 TO=$3 perl -0pi -e 's/\Q$ENV{FROM}\E/$ENV{TO}/' "$1"
}
# the jblaze output after line $1, once a test summary (or "no tests affected") has been printed
wait_summary() {
  local deadline=$(( $(now) + 60000 ))
  while (( $(now) < deadline )); do
    if tail -n +"$(( $1 + 1 ))" "$LOG" | grep -qE '^\[jblaze\] (PASSED|FAILED|no tests affected|compile failed)'; then
      sleep 0.2; tail -n +"$(( $1 + 1 ))" "$LOG" | grep '^\[jblaze\]'; return
    fi
    sleep 0.05
  done
  echo "(timed out)"
}
# step name expected-regex [edit args...]: the output after the edit must match the regex
step() {
  local name=$1 expect=$2 n t0 out; shift 2
  n=$(wc -l < "$LOG"); t0=$(now)
  edit "$@"
  out=$(wait_summary "$n")
  if grep -qE -- "$expect" <<<"$out"; then
    echo "ok   $name ($(( $(now) - t0 )) ms): $(grep -E 'PASSED|FAILED|no tests' <<<"$out" | head -1 | sed 's/\[jblaze\] //')"
  else
    echo "FAIL $name: expected /$expect/ in:"; sed 's/^/       /' <<<"$out"; FAILS=$((FAILS + 1))
  fi
}

setsid "$ROOT/jblaze" test > "$LOG" 2>&1 & JB_PID=$!
out=$(wait_summary 0)
grep -q 'PASSED 3 tests' <<<"$out" && echo "ok   first run: $(grep -E 'PASSED' <<<"$out" | sed 's/\[jblaze\] //')" \
  || { echo "FAIL first run:"; echo "$out"; exit 1; }

step "core change breaks an app test" 'FAILED 3 tests, 1 failed' \
  $CORE/Greeter.java 'return Messages.PREFIX + name;' 'return Messages.PREFIX + name + "!";'
step "fix the test (redefined test class)" 'PASSED' $TEST/GreetControllerTest.java '"Hello you"' '"Hello you!"'
step "new test method, no JVM restart" 'PASSED 3 tests' $TEST/GreetControllerTest.java '	@Test
	void describesAPerson()' '	@Test
	void greetsLoudly() {
		assertTrue(controller.greet().endsWith("!"));
	}

	@Test
	void describesAPerson()'
step "body change keeps the Spring contexts" 'swapped 1$' $CORE/Person.java '"person " + getName()' '"person " + getName() + ""'
step "config change resets the contexts" 'reset Spring test contexts' app/src/main/resources/application.properties \
  'server.port=8081' 'server.port=8082'
step "structural change: test JVM restart" 'test JVM restarted.*|PASSED' $CORE/Greeter.java \
  'public String greet(String name) {' 'public String hi() {
		return "hi";
	}

	public String greet(String name) {'

if grep -q 'test JVM restarted' "$LOG"; then echo "     (the JVM restarted once, for the structural change)"; fi
(( FAILS == 0 )) && echo "PASS" || { echo "$FAILS FAILED (log: $LOG)"; exit 1; }
