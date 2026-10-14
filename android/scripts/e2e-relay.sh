#!/usr/bin/env bash
#
# A local relay for the Android end-to-end run (never the production relay): a scratch MySQL
# server, a scratch database, the built relay on a loopback port (low proof-of-work, rate limits
# off, as scripts/interop.sh runs it) and the Kotlin echo bot (android/cli) as the other side.
#
#   bash scripts/e2e-relay.sh up       start everything; prints RELAY_URL and BOT_USERNAME
#   bash scripts/e2e-relay.sh status   what is running
#   bash scripts/e2e-relay.sh down     stop everything, drop the scratch database and the server
#
# The phone reaches the relay through `adb reverse tcp:<port> tcp:<port>` at
# http://127.0.0.1:<port> (the network policy allows plain http to loopback only).
#
# Requirements: Node.js and pnpm (relay/ built with `pnpm build`), a MySQL 8.4/9.x server binary,
# a JDK >= 21.
#
# Environment:
#   E2E_WORK_DIR     logs, the server's data and the state file (default android/build/e2e)
#   E2E_MYSQL_PORT   the scratch MySQL server's port (default 3307)
#   E2E_RELAY_PORT   the relay's port (default 3100)
#   E2E_MYSQLD       the mysqld binary (default /usr/local/mysql/bin/mysqld)

set -uo pipefail

ROOT=$(cd "$(dirname "$0")/../.." && pwd)
readonly ROOT
readonly ANDROID_DIR=$ROOT/android
readonly RELAY_DIR=$ROOT/relay
readonly WORK=${E2E_WORK_DIR:-$ANDROID_DIR/build/e2e}
readonly MYSQL_PORT=${E2E_MYSQL_PORT:-3307}
readonly WANTED_RELAY_PORT=${E2E_RELAY_PORT:-3100}
readonly MYSQLD=${E2E_MYSQLD:-/usr/local/mysql/bin/mysqld}
readonly BOT=$ANDROID_DIR/cli/build/install/us2us-bot/bin/us2us-bot
readonly STATE=$WORK/state.env
readonly MYSQL_DIR=$WORK/mysql

log() { printf '[e2e-relay] %s\n' "$*" >&2; }

die() {
  log "$*"
  exit 1
}

port_open() { nc -z 127.0.0.1 "$1" >/dev/null 2>&1; }

# wait_for SECONDS COMMAND...: polls COMMAND until it succeeds or the time is up.
wait_for() {
  local deadline=$(($(date +%s) + $1))
  shift
  until "$@" >/dev/null 2>&1; do
    if [ "$(date +%s)" -ge "$deadline" ]; then return 1; fi
    sleep 0.3
  done
}

alive() { [ -n "${1:-}" ] && kill -0 "$1" 2>/dev/null; }

gone() { ! kill -0 "$1" 2>/dev/null; }

random_hex() { node -p 'require("node:crypto").randomBytes(4).toString("hex")'; }

load_state() {
  # shellcheck disable=SC1090
  [ -f "$STATE" ] && . "$STATE"
}

start_mysql() {
  mkdir -p "$MYSQL_DIR"
  if port_open "$MYSQL_PORT"; then
    if [ -f "$MYSQL_DIR/mysqld.pid" ] && alive "$(cat "$MYSQL_DIR/mysqld.pid")"; then
      log "scratch MySQL already running on $MYSQL_PORT"
      return 0
    fi
    die "port $MYSQL_PORT is taken by another server; set E2E_MYSQL_PORT"
  fi
  [ -x "$MYSQLD" ] || die "mysqld not found at $MYSQLD (set E2E_MYSQLD)"
  if [ ! -d "$MYSQL_DIR/data" ]; then
    log "initialising a scratch MySQL data directory"
    "$MYSQLD" --no-defaults --initialize-insecure --datadir="$MYSQL_DIR/data" --log-error="$MYSQL_DIR/init.log" ||
      die "mysqld --initialize failed, see $MYSQL_DIR/init.log"
  fi
  nohup "$MYSQLD" --no-defaults --datadir="$MYSQL_DIR/data" --port="$MYSQL_PORT" --bind-address=127.0.0.1 \
    --socket="$MYSQL_DIR/mysql.sock" --pid-file="$MYSQL_DIR/mysqld.pid" --mysqlx=OFF \
    --log-error="$MYSQL_DIR/err.log" >/dev/null 2>&1 &
  wait_for 60 port_open "$MYSQL_PORT" || die "MySQL did not start, see $MYSQL_DIR/err.log"
  log "scratch MySQL on 127.0.0.1:$MYSQL_PORT"
}

up() {
  mkdir -p "$WORK"
  load_state
  if alive "${RELAY_PID:-}" && alive "${BOT_PID:-}"; then
    log "already up"
    cat "$STATE"
    return 0
  fi
  [ -f "$RELAY_DIR/.output/server/index.mjs" ] || die "the relay is not built: (cd relay && pnpm build)"
  if [ ! -x "$BOT" ]; then
    log "building the echo bot"
    (cd "$ANDROID_DIR" && ./gradlew --console=plain -q :cli:installDist) >"$WORK/build-bot.log" 2>&1 ||
      die "bot build failed, see $WORK/build-bot.log"
  fi
  start_mysql

  local admin=mysql://root@127.0.0.1:$MYSQL_PORT
  local name database_url
  name="us2us_e2e_$(date +%Y%m%d%H%M%S)"
  database_url=$(node "$RELAY_DIR/scripts/scratch-db.mjs" create "$admin" "$name") || die "cannot create database $name"
  (cd "$RELAY_DIR" && DATABASE_URL=$database_url pnpm -s db:migrate) >"$WORK/migrate.log" 2>&1 ||
    die "migration failed, see $WORK/migrate.log"

  port_open "$WANTED_RELAY_PORT" && die "port $WANTED_RELAY_PORT is taken; set E2E_RELAY_PORT"
  local relay_url=http://127.0.0.1:$WANTED_RELAY_PORT
  nohup env NODE_ENV=production HOST=127.0.0.1 "PORT=$WANTED_RELAY_PORT" "DATABASE_URL=$database_url" \
    "POW_SECRET=$(node -p 'require("node:crypto").randomBytes(48).toString("base64")')" \
    POW_DIFFICULTY_REGISTER=10 POW_DIFFICULTY_MESSAGE=8 "MEDIA_DIR=$WORK/media" DISK_MIN_FREE_MB=64 LOG_LEVEL=info \
    RATE_LIMIT_POW=off RATE_LIMIT_REGISTER=off RATE_LIMIT_SEND=off RATE_LIMIT_EPHEMERAL=off \
    RATE_LIMIT_MEDIA_FETCH=off RATE_LIMIT_WS_CONNECT=off RATE_LIMIT_AUTH_FAIL=off \
    RATE_LIMIT_DIRECTORY=off RATE_LIMIT_KEY_FETCH=off RATE_LIMIT_MEDIA_UPLOADS=off \
    RATE_LIMIT_MEDIA_BYTES=off RATE_LIMIT_USERNAME_CHANGES=off RATE_LIMIT_WS_FRAMES=off RATE_LIMIT_ACCOUNT=off \
    node "$RELAY_DIR/.output/server/index.mjs" >"$WORK/relay.log" 2>&1 &
  local relay_pid=$!
  wait_for 30 grep -q 'Listening on' "$WORK/relay.log" || die "the relay did not start, see $WORK/relay.log"
  log "relay at $relay_url (database $name)"

  local username
  username="ebot_$(random_hex)"
  rm -f "$WORK/bot.json"
  RELAY_URL=$relay_url nohup "$BOT" --username "$username" --state "$WORK/bot.json" >"$WORK/bot.log" 2>&1 &
  local bot_pid=$!
  wait_for 90 test -s "$WORK/bot.json" || die "the echo bot did not register, see $WORK/bot.log"
  log "echo bot $username"

  cat >"$STATE" <<STATE_FILE
RELAY_URL=$relay_url
RELAY_PORT=$WANTED_RELAY_PORT
RELAY_PID=$relay_pid
BOT_PID=$bot_pid
BOT_USERNAME=$username
DB_NAME=$name
DB_ADMIN=$admin
STATE_FILE
  cat "$STATE"
}

down() {
  load_state
  if alive "${BOT_PID:-}"; then kill -TERM "$BOT_PID" 2>/dev/null; fi
  if alive "${RELAY_PID:-}"; then
    kill -TERM "$RELAY_PID" 2>/dev/null
    wait_for 15 gone "$RELAY_PID" || log "the relay did not stop"
  fi
  if [ -n "${DB_NAME:-}" ] && port_open "$MYSQL_PORT"; then
    node "$RELAY_DIR/scripts/scratch-db.mjs" drop "${DB_ADMIN:-mysql://root@127.0.0.1:$MYSQL_PORT}" "$DB_NAME" ||
      log "could not drop $DB_NAME"
  fi
  if [ -f "$MYSQL_DIR/mysqld.pid" ]; then
    local pid
    pid=$(cat "$MYSQL_DIR/mysqld.pid")
    if alive "$pid"; then
      kill -TERM "$pid" 2>/dev/null
      wait_for 30 gone "$pid" || log "MySQL did not stop"
    fi
  fi
  rm -rf "$MYSQL_DIR" "$WORK/media" "$STATE" "$WORK/bot.json"
  log "down"
}

status() {
  load_state
  if alive "${RELAY_PID:-}"; then log "relay up at ${RELAY_URL:-?}"; else log "relay down"; fi
  if alive "${BOT_PID:-}"; then log "bot up as ${BOT_USERNAME:-?}"; else log "bot down"; fi
  if port_open "$MYSQL_PORT"; then log "MySQL up on $MYSQL_PORT"; else log "MySQL down"; fi
}

case ${1:-} in
  up) up ;;
  down) down ;;
  status) status ;;
  *)
    sed -n '2,/^$/s/^# \{0,1\}//p' "$0"
    exit 2
    ;;
esac
