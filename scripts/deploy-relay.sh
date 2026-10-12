#!/usr/bin/env bash
# Deploys relay/ to a Linux server as an immutable release and switches to it.
#
#   US2US_DEPLOY_TARGET=<user>@<host> US2US_APP_ROOT=/srv/us2us scripts/deploy-relay.sh
#
# Settings can also live in scripts/deploy.local.env (git-ignored), which is
# read first: US2US_DEPLOY_TARGET, US2US_APP_ROOT, US2US_PORT, US2US_DEPLOY_KEY.
#
# Server layout (created by the first run, see relay/README.md "Deploying"):
#   $APP_ROOT/releases/<timestamp>/   source, node_modules and .output of one release
#   $APP_ROOT/us2us-relay             symlink to the live release (pm2 cwd)
#   $APP_ROOT/ecosystem.config.cjs    pm2 app "us2us-relay" (one process), a copy of
#                                     relay/deploy/ecosystem.config.cjs; never replaced here
#   ~/us2us.env                       settings and secrets, loaded with node --env-file
#
# Steps: dependency audit (local) -> upload -> pnpm install -> build -> migrate ->
# switch symlink -> pm2 reload -> health check. A failed health check switches
# back to the previous release. The audit stops the deploy on any advisory not
# recorded in relay/package.json (relay/README.md "Dependencies");
# US2US_SKIP_AUDIT=1 skips it, e.g. while the registry cannot be reached.
set -euo pipefail

here="$(cd "$(dirname "$0")/.." && pwd)"
# shellcheck disable=SC1091
[ -f "$here/scripts/deploy.local.env" ] && . "$here/scripts/deploy.local.env"

TARGET="${US2US_DEPLOY_TARGET:?set US2US_DEPLOY_TARGET=user@host}"
SSH_KEY="${US2US_DEPLOY_KEY:-$HOME/.ssh/us2us_deploy_ed25519}"
APP_ROOT="${US2US_APP_ROOT:?set US2US_APP_ROOT=/path/on/server}"
PORT="${US2US_PORT:-3000}"
KEEP="${US2US_KEEP_RELEASES:-3}"

ssh_opts=(-i "$SSH_KEY" -o IdentitiesOnly=yes -o BatchMode=yes)
release="$(date -u +%Y%m%d-%H%M%S)"

if [ "${US2US_SKIP_AUDIT:-0}" = 1 ]; then
  echo "!! dependency audit skipped (US2US_SKIP_AUDIT=1)" >&2
else
  echo "==> auditing relay dependencies"
  if ! (cd "$here/relay" && pnpm run --silent audit); then
    echo "!! dependency audit failed; update the package, or record an advisory without a fix (relay/README.md \"Dependencies\")" >&2
    exit 1
  fi
fi

echo "==> uploading relay/ as release $release"
rsync -az -e "ssh ${ssh_opts[*]}" \
  --exclude node_modules --exclude .output --exclude .nuxt --exclude .nitro \
  --exclude .data --exclude .env --exclude test/ --exclude media-data --exclude .DS_Store \
  "$here/relay/" "$TARGET:$APP_ROOT/releases/$release/"

echo "==> building and switching on the server"
ssh "${ssh_opts[@]}" "$TARGET" \
  APP_ROOT="$APP_ROOT" RELEASE="$release" PORT="$PORT" KEEP="$KEEP" 'bash -s' <<'REMOTE'
set -euo pipefail
export NVM_DIR="$HOME/.nvm"
. "$NVM_DIR/nvm.sh" >/dev/null
export COREPACK_ENABLE_DOWNLOAD_PROMPT=0

dir="$APP_ROOT/releases/$RELEASE"
live="$APP_ROOT/us2us-relay"
previous="$(readlink "$live" || true)"

cd "$dir"
# The pm2 app is the server's own file; only point out that it differs from the template.
if [ ! -f "$APP_ROOT/ecosystem.config.cjs" ]; then
  echo "!! $APP_ROOT/ecosystem.config.cjs is missing; copy deploy/ecosystem.config.cjs there (relay/README.md)" >&2
  exit 1
elif ! cmp -s deploy/ecosystem.config.cjs "$APP_ROOT/ecosystem.config.cjs"; then
  echo "note: $APP_ROOT/ecosystem.config.cjs differs from deploy/ecosystem.config.cjs; compare them with diff (relay/README.md)"
fi

pnpm install --frozen-lockfile --reporter=silent
pnpm build >/dev/null
node --env-file="$HOME/us2us.env" node_modules/drizzle-kit/bin.cjs migrate

ln -sfn "$dir" "$live.next" && mv -Tf "$live.next" "$live"
pm2 startOrReload "$APP_ROOT/ecosystem.config.cjs" --update-env >/dev/null

healthy=""
for _ in $(seq 1 20); do
  sleep 1
  if curl -fsS -m 2 "http://127.0.0.1:$PORT/v1/health" >/dev/null 2>&1; then healthy=1; break; fi
done

if [ -z "$healthy" ]; then
  echo "!! health check failed for $RELEASE" >&2
  if [ -n "$previous" ]; then
    ln -sfn "$previous" "$live.next" && mv -Tf "$live.next" "$live"
    pm2 reload us2us-relay >/dev/null
    echo "!! rolled back to $(basename "$previous")" >&2
  fi
  exit 1
fi

pm2 save >/dev/null
# Production must run the built server with NODE_ENV=production (deploy/ecosystem.config.cjs).
# Only these two fields are read; pm2's process list also holds the environment.
pm2 jlist 2>/dev/null | node -e '
  let out = ""
  process.stdin.on("data", (chunk) => (out += chunk)).on("end", () => {
    let ok = false
    for (const line of out.split("\n").filter((l) => l.startsWith("["))) {
      try {
        const app = JSON.parse(line).find((p) => p.name === "us2us-relay")?.pm2_env ?? {}
        ok = String(app.pm_exec_path).endsWith("/.output/server/index.mjs") && (app.env?.NODE_ENV ?? app.NODE_ENV) === "production"
        break
      } catch {}
    }
    if (!ok) console.error("!! warning: pm2 app us2us-relay does not run .output/server/index.mjs with NODE_ENV=production (relay/README.md)")
  })' || true
# Keep the newest $KEEP releases (and never the live one).
ls -1dt "$APP_ROOT"/releases/*/ | tail -n +"$((KEEP + 1))" | while read -r old; do
  [ "${old%/}" = "$(readlink "$live")" ] || rm -rf "$old"
done
echo "live: $(basename "$(readlink "$live")") ($(curl -fsS -m 2 "http://127.0.0.1:$PORT/v1/health"))"
REMOTE
