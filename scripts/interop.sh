#!/usr/bin/env bash
#
# Cross-language live interop run against one real relay build (README.md,
# "Live interop run"). Every step is reported PASS, FAIL or SKIP; the exit
# status is 1 if any step failed.
#
#   1  build the relay, the Kotlin bot and the iOS tests; create and migrate a
#      scratch database; start the relay on a free port (low proof-of-work,
#      rate limits off, long WS_ACK_TIMEOUT_MS)
#   2  live tests against it: Kotlin LiveRelayTest, Swift LiveRelayTests
#   3  the Kotlin us2us-bot as an echo bot, then
#      3a iOS -> bot     Swift LiveEchoBotTests
#      3b Kotlin -> bot  Kotlin LiveEchoBotTest
#      3c Kotlin -> iOS  Kotlin LiveEchoBotTest against Swift LiveEchoResponderTests
#      3d communities    Kotlin LiveCommunityInteropTest (two app runtimes) with Swift
#                        LiveCommunityInteropTests (the iOS runtime): public and private
#                        communities, approval, removal, leaving, one-to-one alongside
#   4  SIGTERM in the middle of traffic (relay/scripts/restart-check.mjs): the
#      relay is stopped and started again, no accepted envelope may be lost;
#      4b the bot, connected throughout, still answers after the restart
#   5  clean up: stop everything, check the relay's exit and logs, drop the database
#
# Usage: scripts/interop.sh [--no-ios] [--no-build]
#   --no-ios    skip every step that needs Xcode (2 Swift, 3a, 3c, 3d)
#   --no-build  use the existing builds (relay/.output, the bot, the iOS test bundles)
#
# Requirements: Node.js 24 and pnpm (relay), a JDK >= 21 (android), Xcode with
# an iOS Simulator (ios), a MySQL 8.4/9.x server.
#
# Environment:
#   INTEROP_MYSQL_URL    server URL of a user that may create and drop databases
#                        (default mysql://root@127.0.0.1:3307)
#   INTEROP_DESTINATION  xcodebuild destination
#                        (default "platform=iOS Simulator,name=iPhone 17,OS=27.0")
#   INTEROP_WORK_DIR     logs, state files and the summary (default: a new
#                        temporary directory, kept after the run)

set -uo pipefail

ROOT=$(cd "$(dirname "$0")/.." && pwd)
readonly ROOT
readonly RELAY_DIR=$ROOT/relay
readonly ANDROID_DIR=$ROOT/android
readonly KIT_DIR=$ROOT/ios/Us2usKit
readonly DERIVED_DATA=$KIT_DIR/.build/interop
readonly CORE_DIR=$ROOT/ios/Us2usAppCore
readonly CORE_DERIVED_DATA=$CORE_DIR/.build/interop
readonly BOT=$ANDROID_DIR/cli/build/install/us2us-bot/bin/us2us-bot
readonly CLI_RESULTS=$ANDROID_DIR/cli/build/test-results/test
readonly RUNTIME_RESULTS=$ANDROID_DIR/runtime/build/test-results/testDebugUnitTest

IOS=1
BUILD=1
for argument in "$@"; do
  case $argument in
    --no-ios) IOS=0 ;;
    --no-build) BUILD=0 ;;
    -h | --help)
      sed -n '2,/^$/s/^# \{0,1\}//p' "$0"
      exit 0
      ;;
    *)
      echo "unknown option: $argument (see --help)" >&2
      exit 2
      ;;
  esac
done

MYSQL_URL=${INTEROP_MYSQL_URL:-mysql://root@127.0.0.1:3307}
DESTINATION=${INTEROP_DESTINATION:-platform=iOS Simulator,name=iPhone 17,OS=27.0}
WORK=${INTEROP_WORK_DIR:-$(mktemp -d "${TMPDIR:-/tmp}/us2us-interop.XXXXXX")}
mkdir -p "$WORK"
WORK=$(cd "$WORK" && pwd)
readonly MYSQL_URL DESTINATION WORK

RELAY_PID=""
BOT_PID=""
RESPONDER_PID=""
CHECK_PID=""
DB_NAME=""
DATABASE_URL=""
RELAY_URL=""
RELAY_ENV=()
RESULTS=()
FAILED=0

log() { printf '[interop] %s\n' "$*" >&2; }

# record STEP STATUS DETAIL
record() {
  RESULTS+=("$1|$2|$3")
  [ "$2" = FAIL ] && FAILED=1
  log "$1: $2${3:+ - $3}"
}

die() {
  log "$*"
  exit 1
}

# wait_for SECONDS PID COMMAND...: polls COMMAND; fails at the deadline or when PID (if set) has exited.
wait_for() {
  local deadline=$(($(date +%s) + $1)) pid=$2
  shift 2
  until "$@" >/dev/null 2>&1; do
    if [ -n "$pid" ] && ! kill -0 "$pid" 2>/dev/null; then return 1; fi
    if [ "$(date +%s)" -ge "$deadline" ]; then return 1; fi
    sleep 0.2
  done
}

# stop PID: SIGTERM, then the exit status.
stop() {
  [ -n "$1" ] || return 0
  kill -TERM "$1" 2>/dev/null
  wait "$1" 2>/dev/null
}

random_hex() { node -p 'require("node:crypto").randomBytes(4).toString("hex")'; }

free_port() {
  node -e 'const s = require("node:net").createServer().listen(0, "127.0.0.1", () => { console.log(s.address().port); s.close() })'
}

scratch_db() { node "$RELAY_DIR/scripts/scratch-db.mjs" "$@"; }

# Error lines of a relay log (README "Logging": a relay working as intended writes none).
relay_errors() { grep -E '^\[relay\] error|\[uncaught|\[unhandled' "$1"; }

start_relay() {
  local logfile=$1
  env ${RELAY_ENV[@]+"${RELAY_ENV[@]}"} node "$RELAY_DIR/.output/server/index.mjs" >"$logfile" 2>&1 &
  RELAY_PID=$!
  if ! wait_for 30 "$RELAY_PID" grep -q 'Listening on' "$logfile"; then
    log "the relay did not start, see $logfile"
    stop "$RELAY_PID"
    RELAY_PID=""
    return 1
  fi
}

# junit_ok XML: the suite ran at least one test, and none was skipped or failed.
junit_ok() {
  local counts
  [ -f "$1" ] || return 1
  counts=$(sed -n 's/.*<testsuite [^>]*tests="\([0-9]*\)" skipped="\([0-9]*\)" failures="\([0-9]*\)" errors="\([0-9]*\)".*/\1 \2 \3 \4/p' "$1" | head -n 1)
  set -- $counts
  [ "${1:-0}" -gt 0 ] && [ "${2:-1}" -eq 0 ] && [ "${3:-1}" -eq 0 ] && [ "${4:-1}" -eq 0 ]
}

# gradle_test TASK FILTER RESULT_XML LOG [NAME=VALUE...]
gradle_test() {
  local task=$1 filter=$2 xml=$3 logfile=$4
  shift 4
  rm -f "$xml"
  (cd "$ANDROID_DIR" && env "$@" ./gradlew --console=plain "$task" --tests "$filter" --rerun) >"$logfile" 2>&1 &&
    junit_ok "$xml" && cp "$xml" "${logfile%.log}.xml"
}

# xcode_test SUITE LOG [NAME=VALUE...]: TEST_RUNNER_* variables reach the test process without the prefix.
xcode_test() {
  local suite=$1 logfile=$2
  shift 2
  (cd "$KIT_DIR" && exec env "$@" xcodebuild test-without-building -scheme Us2usKit -destination "$DESTINATION" \
    -derivedDataPath "$DERIVED_DATA" -only-testing:"Us2usKitTests/$suite" -collect-test-diagnostics never) \
    >"$logfile" 2>&1
}

# xcode_core_test SUITE LOG [NAME=VALUE...]: the same for the app core's runtime tests.
xcode_core_test() {
  local suite=$1 logfile=$2
  shift 2
  (cd "$CORE_DIR" && exec env "$@" xcodebuild test-without-building -scheme Us2usAppCore-Package -destination "$DESTINATION" \
    -derivedDataPath "$CORE_DERIVED_DATA" -only-testing:"Us2usRuntimeTests/$suite" -collect-test-diagnostics never) \
    >"$logfile" 2>&1
}

# xcode_ok LOG: the run passed and nothing in it was skipped (a skip means a variable did not arrive).
xcode_ok() { grep -q 'Test run with .* passed' "$1" && ! grep -q ' skipped' "$1"; }

cleanup() {
  stop "$CHECK_PID"
  stop "$RESPONDER_PID"
  stop "$BOT_PID"
  stop "$RELAY_PID"
  CHECK_PID="" RESPONDER_PID="" BOT_PID="" RELAY_PID=""
  if [ -n "$DB_NAME" ]; then
    scratch_db drop "$MYSQL_URL" "$DB_NAME" || log "could not drop the scratch database $DB_NAME"
    DB_NAME=""
  fi
  rm -rf "$WORK/media"
}
trap cleanup EXIT
trap 'exit 130' INT TERM

check_requirements() {
  command -v node >/dev/null || die "node not found: Node.js 24 is required (relay/README.md)"
  [ "$(node -p 'process.versions.node.split(".")[0]')" -ge 24 ] || die "Node.js 24 is required, found $(node --version)"
  command -v pnpm >/dev/null || die "pnpm not found (corepack enable)"
  [ -x "$ANDROID_DIR/gradlew" ] || die "android/gradlew missing"
  if [ "$IOS" = 1 ]; then
    command -v xcodebuild >/dev/null || die "xcodebuild not found; use --no-ios to skip the iOS steps"
    [ -d "$ROOT/ios/Vendor/SignalFfi.xcframework" ] || die "libsignal is not vendored: run ios/scripts/fetch-libsignal.sh"
  fi
}

step1_relay() {
  if [ "$BUILD" = 1 ]; then
    log "building the relay, the bot and the iOS tests"
    (cd "$RELAY_DIR" && pnpm build) >"$WORK/1-build-relay.log" 2>&1 ||
      { record "1 relay up" FAIL "relay build, see 1-build-relay.log"; return 1; }
    (cd "$ANDROID_DIR" && ./gradlew --console=plain :cli:installDist :core:testClasses :cli:testClasses \
      :runtime:compileDebugUnitTestKotlin) \
      >"$WORK/1-build-android.log" 2>&1 ||
      { record "1 relay up" FAIL "android build, see 1-build-android.log"; return 1; }
    if [ "$IOS" = 1 ]; then
      (cd "$KIT_DIR" && xcodebuild build-for-testing -scheme Us2usKit -destination "$DESTINATION" \
        -derivedDataPath "$DERIVED_DATA") >"$WORK/1-build-ios.log" 2>&1 ||
        { record "1 relay up" FAIL "iOS build, see 1-build-ios.log"; return 1; }
      (cd "$CORE_DIR" && xcodebuild build-for-testing -scheme Us2usAppCore-Package -destination "$DESTINATION" \
        -derivedDataPath "$CORE_DERIVED_DATA") >"$WORK/1-build-ios-core.log" 2>&1 ||
        { record "1 relay up" FAIL "iOS app core build, see 1-build-ios-core.log"; return 1; }
    fi
  fi
  [ -f "$RELAY_DIR/.output/server/index.mjs" ] || { record "1 relay up" FAIL "relay not built"; return 1; }

  local name url
  name="us2us_interop_$(date +%Y%m%d%H%M%S)_$$"
  url=$(scratch_db create "$MYSQL_URL" "$name") || { record "1 relay up" FAIL "cannot create database $name"; return 1; }
  DB_NAME=$name
  DATABASE_URL=$url
  (cd "$RELAY_DIR" && DATABASE_URL=$DATABASE_URL pnpm -s db:migrate) >"$WORK/1-migrate.log" 2>&1 ||
    { record "1 relay up" FAIL "migration, see 1-migrate.log"; return 1; }

  local port
  port=$(free_port)
  RELAY_URL=http://127.0.0.1:$port
  # Test-friendly: every client runs on this machine (one IP), so the per-IP and
  # per-account limits are off; WS_ACK_TIMEOUT_MS outlasts step 4, so its live
  # envelopes reach MySQL only through the shutdown flush.
  RELAY_ENV=(
    NODE_ENV=production HOST=127.0.0.1 "PORT=$port" "DATABASE_URL=$DATABASE_URL"
    "POW_SECRET=$(node -p 'require("node:crypto").randomBytes(48).toString("base64")')"
    POW_DIFFICULTY_REGISTER=10 POW_DIFFICULTY_MESSAGE=8
    WS_ACK_TIMEOUT_MS=120000 "MEDIA_DIR=$WORK/media" DISK_MIN_FREE_MB=64 LOG_LEVEL=info
    RATE_LIMIT_POW=off RATE_LIMIT_REGISTER=off RATE_LIMIT_SEND=off RATE_LIMIT_EPHEMERAL=off
    RATE_LIMIT_MEDIA_FETCH=off RATE_LIMIT_WS_CONNECT=off RATE_LIMIT_AUTH_FAIL=off
    RATE_LIMIT_DIRECTORY=off RATE_LIMIT_KEY_FETCH=off RATE_LIMIT_MEDIA_UPLOADS=off
    RATE_LIMIT_MEDIA_BYTES=off RATE_LIMIT_USERNAME_CHANGES=off RATE_LIMIT_WS_FRAMES=off RATE_LIMIT_ACCOUNT=off
    RATE_LIMIT_COMMUNITY_IP=off RATE_LIMIT_COMMUNITY_CREATE=off RATE_LIMIT_COMMUNITY_JOIN=off
    RATE_LIMIT_COMMUNITY_SEND=off RATE_LIMIT_COMMUNITY_API=off
  )
  start_relay "$WORK/relay-1.log" || { record "1 relay up" FAIL "see relay-1.log"; return 1; }
  record "1 relay up" PASS "$RELAY_URL, database $DB_NAME"
}

step2_live_tests() {
  if gradle_test :core:test '*LiveRelayTest' "$ANDROID_DIR/core/build/test-results/test/TEST-com.syconx.us2us.core.messenger.LiveRelayTest.xml" \
    "$WORK/2-kotlin-live.log" "RELAY_URL=$RELAY_URL"; then
    record "2 Kotlin live tests" PASS "LiveRelayTest"
  else
    record "2 Kotlin live tests" FAIL "see 2-kotlin-live.log"
  fi
  if [ "$IOS" = 0 ]; then
    record "2 Swift live tests" SKIP "--no-ios"
  elif xcode_test LiveRelayTests "$WORK/2-swift-live.log" "TEST_RUNNER_RELAY_URL=$RELAY_URL" &&
    xcode_ok "$WORK/2-swift-live.log"; then
    record "2 Swift live tests" PASS "LiveRelayTests"
  else
    record "2 Swift live tests" FAIL "see 2-swift-live.log"
  fi
}

# kotlin_to_responder STATE_FILE LOG: the Kotlin LiveEchoBotTest against the responder that wrote STATE_FILE.
kotlin_to_responder() {
  gradle_test :cli:test '*LiveEchoBotTest' "$CLI_RESULTS/TEST-com.syconx.us2us.cli.LiveEchoBotTest.xml" "$2" \
    "RELAY_URL=$RELAY_URL" "US2US_BOT_STATE=$1"
}

step3_cross_language() {
  local state=$WORK/kotlin-bot.json
  RELAY_URL=$RELAY_URL "$BOT" --username "kbot_$(random_hex)" --state "$state" >"$WORK/3-kotlin-bot.log" 2>&1 &
  BOT_PID=$!
  if ! wait_for 60 "$BOT_PID" test -s "$state"; then
    record "3 Kotlin echo bot" FAIL "did not register, see 3-kotlin-bot.log"
    stop "$BOT_PID"
    BOT_PID=""
    return 1
  fi
  record "3 Kotlin echo bot" PASS "$(cat "$state")"

  if [ "$IOS" = 0 ]; then
    record "3a iOS -> Kotlin bot" SKIP "--no-ios"
  elif xcode_test LiveEchoBotTests "$WORK/3a-ios-to-kotlin-bot.log" \
    "TEST_RUNNER_RELAY_URL=$RELAY_URL" "TEST_RUNNER_BOT_STATE=$state" && xcode_ok "$WORK/3a-ios-to-kotlin-bot.log"; then
    record "3a iOS -> Kotlin bot" PASS "LiveEchoBotTests"
  else
    record "3a iOS -> Kotlin bot" FAIL "see 3a-ios-to-kotlin-bot.log, 3-kotlin-bot.log"
  fi

  if kotlin_to_responder "$state" "$WORK/3b-kotlin-to-kotlin-bot.log"; then
    record "3b Kotlin -> Kotlin bot" PASS "LiveEchoBotTest"
  else
    record "3b Kotlin -> Kotlin bot" FAIL "see 3b-kotlin-to-kotlin-bot.log, 3-kotlin-bot.log"
  fi

  if [ "$IOS" = 0 ]; then
    record "3c Kotlin -> iOS responder" SKIP "--no-ios"
    return 0
  fi
  local echo_state=$WORK/ios-echo.json
  xcode_test LiveEchoResponderTests "$WORK/3c-ios-responder.log" "TEST_RUNNER_RELAY_URL=$RELAY_URL" \
    "TEST_RUNNER_ECHO_STATE=$echo_state" TEST_RUNNER_ECHO_TIMEOUT_SECONDS=300 &
  RESPONDER_PID=$!
  if ! wait_for 300 "$RESPONDER_PID" test -s "$echo_state"; then
    record "3c Kotlin -> iOS responder" FAIL "the responder did not start, see 3c-ios-responder.log"
    stop "$RESPONDER_PID"
    RESPONDER_PID=""
    return 1
  fi
  local kotlin_ok=0 responder_status
  kotlin_to_responder "$echo_state" "$WORK/3c-kotlin-to-ios.log" && kotlin_ok=1
  # A failed driver never sends the final wipe: do not wait for the responder's timeout.
  if [ "$kotlin_ok" = 1 ]; then wait "$RESPONDER_PID"; else stop "$RESPONDER_PID"; fi
  responder_status=$?
  RESPONDER_PID=""
  if [ "$kotlin_ok" = 1 ] && [ "$responder_status" -eq 0 ] && xcode_ok "$WORK/3c-ios-responder.log"; then
    record "3c Kotlin -> iOS responder" PASS "LiveEchoBotTest + LiveEchoResponderTests"
  else
    record "3c Kotlin -> iOS responder" FAIL "see 3c-kotlin-to-ios.log, 3c-ios-responder.log"
  fi
}

# 3d: both platforms play one community script (docs/COMMUNITIES.md) and meet in a
# directory of step files; a side that fails writes <side>-failed.json, so the other stops.
step3d_communities() {
  if [ "$IOS" = 0 ]; then
    record "3d communities Kotlin <-> iOS" SKIP "--no-ios"
    return 0
  fi
  local meeting=$WORK/communities kotlin_ok=0 ios_status
  rm -rf "$meeting"
  mkdir -p "$meeting"
  xcode_core_test LiveCommunityInteropTests "$WORK/3d-ios-communities.log" "TEST_RUNNER_RELAY_URL=$RELAY_URL" \
    "TEST_RUNNER_COMMUNITY_DIR=$meeting" &
  RESPONDER_PID=$!
  gradle_test :runtime:testDebugUnitTest '*LiveCommunityInteropTest' \
    "$RUNTIME_RESULTS/TEST-com.syconx.us2us.runtime.facade.LiveCommunityInteropTest.xml" \
    "$WORK/3d-kotlin-communities.log" "RELAY_URL=$RELAY_URL" "COMMUNITY_DIR=$meeting" && kotlin_ok=1
  if [ "$kotlin_ok" = 0 ] && [ ! -f "$meeting/kotlin-failed.json" ]; then
    echo '{"error":"the Kotlin test did not run"}' >"$meeting/kotlin-failed.json"
  fi
  wait_for 300 "$RESPONDER_PID" false
  stop "$RESPONDER_PID"
  ios_status=$?
  RESPONDER_PID=""
  if [ "$kotlin_ok" = 1 ] && [ "$ios_status" -eq 0 ] && xcode_ok "$WORK/3d-ios-communities.log"; then
    record "3d communities Kotlin <-> iOS" PASS "LiveCommunityInteropTest + LiveCommunityInteropTests"
  else
    record "3d communities Kotlin <-> iOS" FAIL "see 3d-kotlin-communities.log, 3d-ios-communities.log, communities/"
  fi
}

step4_restart() {
  local out=$WORK/4-restart-check.json status flushed
  node "$RELAY_DIR/scripts/restart-check.mjs" --relay "$RELAY_URL" --database "$DATABASE_URL" \
    >"$out" 2>"$WORK/4-restart-check.log" &
  CHECK_PID=$!
  if ! wait_for 120 "$CHECK_PID" grep -qx READY "$out"; then
    record "4 SIGTERM mid-traffic" FAIL "no traffic, see 4-restart-check.log"
    stop "$CHECK_PID"
    CHECK_PID=""
    return 1
  fi
  log "stopping the relay with SIGTERM in the middle of traffic"
  kill -TERM "$RELAY_PID"
  wait "$RELAY_PID"
  status=$?
  RELAY_PID=""
  flushed=$(scratch_db envelopes "$DATABASE_URL")
  local problems=""
  [ "$status" -eq 0 ] || problems="relay exit status $status; "
  relay_errors "$WORK/relay-1.log" >/dev/null && problems="${problems}errors in relay-1.log; "
  start_relay "$WORK/relay-2.log" || problems="${problems}restart failed; "
  wait "$CHECK_PID"
  status=$?
  CHECK_PID=""
  [ "$status" -eq 0 ] || problems="${problems}restart-check reported a loss or failed, see 4-restart-check.json/.log; "
  if [ -z "$problems" ]; then
    record "4 SIGTERM mid-traffic" PASS "exit 0, $flushed envelopes in MySQL while stopped, none lost (4-restart-check.json)"
  else
    record "4 SIGTERM mid-traffic" FAIL "$problems"
  fi

  if [ -z "$RELAY_PID" ]; then
    record "4b bot after the restart" SKIP "relay not running"
  elif [ -z "$BOT_PID" ]; then
    record "4b bot after the restart" SKIP "no bot"
  elif kotlin_to_responder "$WORK/kotlin-bot.json" "$WORK/4b-kotlin-to-kotlin-bot.log"; then
    record "4b bot after the restart" PASS "the bot reconnected and answers"
  else
    record "4b bot after the restart" FAIL "see 4b-kotlin-to-kotlin-bot.log, 3-kotlin-bot.log"
  fi
}

step5_cleanup() {
  local status problems=""
  stop "$BOT_PID"
  BOT_PID=""
  if [ -n "$RELAY_PID" ]; then
    stop "$RELAY_PID"
    status=$?
    RELAY_PID=""
    [ "$status" -eq 0 ] || problems="relay exit status $status; "
  fi
  for logfile in "$WORK"/relay-*.log; do
    [ -f "$logfile" ] && relay_errors "$logfile" >/dev/null && problems="${problems}errors in $(basename "$logfile"); "
  done
  local name=$DB_NAME
  cleanup
  [ -z "$DB_NAME" ] || problems="${problems}database not dropped; "
  if [ -z "$problems" ]; then
    record "5 cleanup" PASS "processes stopped, relay logs clean, database $name dropped"
  else
    record "5 cleanup" FAIL "$problems"
  fi
}

summary() {
  {
    printf '\n%-30s %-5s %s\n' STEP RESULT DETAIL
    local entry step result detail
    for entry in ${RESULTS[@]+"${RESULTS[@]}"}; do
      step=${entry%%|*}
      entry=${entry#*|}
      result=${entry%%|*}
      detail=${entry#*|}
      printf '%-30s %-5s %s\n' "$step" "$result" "$detail"
    done
    printf '\nlogs: %s\n' "$WORK"
  } | tee "$WORK/summary.txt"
}

check_requirements
log "work directory: $WORK"
if step1_relay; then
  step2_live_tests
  step3_cross_language
  step3d_communities
  step4_restart
fi
step5_cleanup
summary
exit "$FAILED"
