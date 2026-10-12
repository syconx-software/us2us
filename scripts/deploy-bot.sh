#!/usr/bin/env bash
# Deploys the echo bot (android/cli, us2us-bot) to the relay's server as an
# immutable release and (re)starts its pm2 app, which answers around the clock
# (App Store review talks to it).
#
#   US2US_DEPLOY_TARGET=<user>@<host> scripts/deploy-bot.sh
#
# Settings can also live in scripts/deploy.local.env (git-ignored), which is
# read first: US2US_DEPLOY_TARGET and US2US_DEPLOY_KEY as for deploy-relay.sh,
# and for the bot (defaults in brackets)
#   US2US_BOT_ROOT       releases and the live symlink  [/var/www/us2us_user/us2us-bot]
#   US2US_BOT_DATA       the account, --data            [/home/us2us_user/us2us-echo]
#   US2US_BOT_USERNAME   its username                   [us2us_echo]
#   US2US_BOT_RELAY_URL  the relay it talks to          [https://relay.us2us.app]
#   US2US_BOT_JAVA_HOME  a system OpenJDK 21            [/usr/lib/jvm/java-21-openjdk-amd64]
#   US2US_BOT_JVM_OPTS   passed as US2US_BOT_OPTS       [-Xmx512m -XX:+ExitOnOutOfMemoryError]
#
# Server layout:
#   $BOT_ROOT/releases/<timestamp>/   one :cli:installDist image (bin/, lib/)
#   $BOT_ROOT/current                 symlink to the live release
#   $BOT_DATA/                        the account backup and its password (0700),
#                                     made by the bot's first start; never touched here
#   pm2 app "us2us-echo"              $BOT_ROOT/current/bin/us2us-bot --username ...
#                                     --data $BOT_DATA --relay ..., logs in ~/.pm2/logs
#
# Steps: build (local) -> server checks (x86_64, Java 21, the relay's pm2) -> upload, with
# unchanged files hard-linked to the live release -> switch symlink -> pm2
# delete + start (the old bot saves its backup and lets go of the directory
# first) -> 20 s without a restart -> pm2 save. A bot that does not stay up
# is switched back to the previous release; without one it is stopped, so it
# does not retry for ever. The relay's pm2 app and files are never touched.
set -euo pipefail

here="$(cd "$(dirname "$0")/.." && pwd)"
# shellcheck disable=SC1091
[ -f "$here/scripts/deploy.local.env" ] && . "$here/scripts/deploy.local.env"

TARGET="${US2US_DEPLOY_TARGET:?set US2US_DEPLOY_TARGET=user@host}"
SSH_KEY="${US2US_DEPLOY_KEY:-$HOME/.ssh/us2us_deploy_ed25519}"
BOT_ROOT="${US2US_BOT_ROOT:-/var/www/us2us_user/us2us-bot}"
BOT_DATA="${US2US_BOT_DATA:-/home/us2us_user/us2us-echo}"
BOT_USERNAME="${US2US_BOT_USERNAME:-us2us_echo}"
BOT_RELAY_URL="${US2US_BOT_RELAY_URL:-https://relay.us2us.app}"
BOT_JAVA_HOME="${US2US_BOT_JAVA_HOME:-/usr/lib/jvm/java-21-openjdk-amd64}"
BOT_JVM_OPTS="${US2US_BOT_JVM_OPTS:--Xmx512m -XX:+ExitOnOutOfMemoryError}"
KEEP="${US2US_KEEP_RELEASES:-3}"

if [ -n "${US2US_APP_ROOT:-}" ] && [ "${BOT_ROOT%/}" = "${US2US_APP_ROOT%/}" ]; then
  echo "!! US2US_BOT_ROOT is the relay's US2US_APP_ROOT; give the bot a directory of its own" >&2
  exit 1
fi

ssh_opts=(-i "$SSH_KEY" -o IdentitiesOnly=yes -o BatchMode=yes)
release="$(date -u +%Y%m%d-%H%M%S)"
image="$here/android/cli/build/install/us2us-bot"

# NAME=value pairs for the remote shell, quoted, so values may hold spaces.
remote_env() {
  local name
  for name in "$@"; do printf '%s=%q ' "$name" "${!name}"; done
}

echo "==> building us2us-bot"
(cd "$here/android" && ./gradlew :cli:installDist --console=plain -q)
[ -x "$image/bin/us2us-bot" ] || { echo "!! $image/bin/us2us-bot is missing after the build" >&2; exit 1; }

# Prints "live=<release>" (empty on the first deploy); everything else goes to stderr.
check_server() {
  ssh "${ssh_opts[@]}" "$TARGET" "$(remote_env BOT_ROOT BOT_JAVA_HOME) bash -s" <<'REMOTE'
set -euo pipefail
# libsignal-client carries a Linux library for x86_64 only.
if [ "$(uname -m)" != x86_64 ]; then
  echo "!! the bot needs an x86_64 server, this one is $(uname -m)" >&2
  exit 1
fi
java="$BOT_JAVA_HOME/bin/java"
major="$("$java" -XshowSettings:properties -version 2>&1 | awk -F' = ' '/java.specification.version/ {print $2}' || true)"
if [ -z "$major" ] || [ "${major%%.*}" -lt 21 ]; then
  echo "!! no Java 21 at $BOT_JAVA_HOME: install it (apt install openjdk-21-jre-headless) or set US2US_BOT_JAVA_HOME" >&2
  exit 1
fi
export NVM_DIR="$HOME/.nvm"
[ -s "$NVM_DIR/nvm.sh" ] && . "$NVM_DIR/nvm.sh" >/dev/null
command -v pm2 >/dev/null || { echo "!! pm2 is not on the PATH of $(whoami)" >&2; exit 1; }
# The relay's pm2 (us2us_user's): its boot unit brings the bot back, and pm2 save is its own.
if ! pm2 jlist 2>/dev/null | grep -F '"name":"us2us-relay"' >/dev/null; then
  echo "!! the pm2 of $(whoami) does not run us2us-relay: deploy as the relay's user" >&2
  exit 1
fi
# Releases are pruned below: never in the relay's app root, whatever US2US_APP_ROOT says locally.
if [ -L "$BOT_ROOT/us2us-relay" ] || [ -e "$BOT_ROOT/ecosystem.config.cjs" ]; then
  echo "!! $BOT_ROOT is the relay's app root; give the bot a directory of its own (US2US_BOT_ROOT)" >&2
  exit 1
fi
mkdir -p "$BOT_ROOT/releases"
echo "live=$(readlink "$BOT_ROOT/current" || true)"
REMOTE
}

echo "==> checking the server"
previous="$(check_server | sed -n 's/^live=//p')"

echo "==> uploading the bot as release $release"
link_dest=()
[ -n "$previous" ] && link_dest=(--link-dest="$previous/")
# --checksum: Gradle rewrites every jar's mtime, so only the content says what is unchanged.
rsync -az --checksum --delete -e "ssh ${ssh_opts[*]}" ${link_dest[@]+"${link_dest[@]}"} \
  "$image/" "$TARGET:$BOT_ROOT/releases/$release/"

echo "==> switching and starting pm2 app us2us-echo"
ssh "${ssh_opts[@]}" "$TARGET" \
  "$(remote_env BOT_ROOT BOT_DATA BOT_USERNAME BOT_RELAY_URL BOT_JAVA_HOME BOT_JVM_OPTS KEEP release previous) bash -s" <<'REMOTE'
set -euo pipefail
export NVM_DIR="$HOME/.nvm"
[ -s "$NVM_DIR/nvm.sh" ] && . "$NVM_DIR/nvm.sh" >/dev/null

live="$BOT_ROOT/current"

switch_to() {
  ln -sfn "$1" "$live.next" && mv -Tf "$live.next" "$live"
}

# delete + start, so a changed script path, argument or environment always applies. pm2
# stops with SIGINT: the old bot writes its pending backup and lets go of the directory.
start_bot() {
  pm2 delete us2us-echo >/dev/null 2>&1 || true
  JAVA_HOME="$BOT_JAVA_HOME" PATH="$BOT_JAVA_HOME/bin:$PATH" US2US_BOT_OPTS="$BOT_JVM_OPTS" \
    pm2 start "$live/bin/us2us-bot" --name us2us-echo --interpreter none --time \
      --kill-timeout 15000 --exp-backoff-restart-delay 1000 --stop-exit-codes 2 \
      -- --username "$BOT_USERNAME" --data "$BOT_DATA" --relay "$BOT_RELAY_URL" >/dev/null
}

# "<status> <restarts>" of us2us-echo; only these two fields are read.
app_state() {
  pm2 jlist 2>/dev/null | node -e '
    let out = ""
    process.stdin.on("data", (chunk) => (out += chunk)).on("end", () => {
      for (const line of out.split("\n").filter((l) => l.startsWith("["))) {
        try {
          const env = JSON.parse(line).find((p) => p.name === "us2us-echo")?.pm2_env
          if (env) console.log(`${env.status} ${env.restart_time}`)
          break
        } catch {}
      }
    })' || true
}

# A start that fails (relay refused, another account in the directory) exits at once, and
# pm2 counts the restart: 20 s online without one is a bot that restored and connected.
stays_up() {
  local state
  for _ in $(seq 1 20); do
    sleep 1
    state="$(app_state)"
    case "$state" in
      "online 0" | "launching 0") ;;
      *) return 1 ;;
    esac
  done
  [ "$state" = "online 0" ]
}

switch_to "$BOT_ROOT/releases/$release"
start_bot
if ! stays_up; then
  echo "!! us2us-echo did not stay up on $release:" >&2
  pm2 logs us2us-echo --nostream --lines 20 >&2 2>&1 || true
  if [ -n "$previous" ]; then
    switch_to "$previous"
    start_bot
    pm2 save >/dev/null
    echo "!! switched back to $(basename "$previous")" >&2
  else
    pm2 stop us2us-echo >/dev/null || true
    echo "!! stopped us2us-echo; see the log above" >&2
  fi
  exit 1
fi

pm2 save >/dev/null
# Keep the newest $KEEP releases (and never the live one). By name, a UTC time: rsync -a gives
# a release the mtime of the local build directory, which Gradle leaves alone between builds.
ls -1d "$BOT_ROOT"/releases/*/ | sort -r | tail -n +"$((KEEP + 1))" | while read -r old; do
  [ "${old%/}" = "$(readlink "$live")" ] || rm -rf "$old"
done
echo "live: $(basename "$(readlink "$live")")"
pm2 logs us2us-echo --nostream --lines 5 2>/dev/null || true
REMOTE
