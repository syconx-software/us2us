#!/usr/bin/env bash
# Audits/builds locally, uploads a runtime-only release, migrates with separate credentials,
# switches atomically and checks health. No production package manager or build toolchain.
# --prepare-only <fresh-directory> performs only the local audit/build/package steps.
set -euo pipefail

here="$(cd "$(dirname "$0")/.." && pwd)"
prepare_only=""
if [ "${1:-}" = --prepare-only ] && [ "$#" = 2 ]; then
  prepare_only="$2"
elif [ "$#" != 0 ]; then
  echo 'usage: deploy-relay.sh [--prepare-only <fresh-directory>]' >&2
  exit 2
fi

# Require the supported build Node before loading dependencies or doing anything remote.
node -e 'const [a,b,c]=process.versions.node.split(".").map(Number); if (!((a===22&&(b>22||(b===22&&c>=3)))||(a===24&&b>=15)||a>=26)) { console.error("Use a Node version supported by relay/package.json");process.exit(1) }'
if [ "${US2US_SKIP_AUDIT:-0}" = 1 ]; then
  echo '!! dependency audit explicitly skipped (US2US_SKIP_AUDIT=1)' >&2
else
  echo '==> auditing relay dependencies'
  (cd "$here/relay" && pnpm run --silent audit)
fi

echo '==> building relay locally'
(cd "$here/relay" && pnpm build >/dev/null)
if [ -n "$prepare_only" ]; then
  node "$here/scripts/prepare-relay-release.mjs" "$here/relay" "$prepare_only"
  echo 'Prepared locally only. No SSH, upload, migration or service change.'
  exit 0
fi

# This is the user's private shell configuration, as in the preceding deploy tool.
# shellcheck disable=SC1091
[ ! -f "$here/scripts/deploy.local.env" ] || . "$here/scripts/deploy.local.env"
task_target="${US2US_DEPLOY_TARGET:?set US2US_DEPLOY_TARGET=user@host}"
task_key="${US2US_DEPLOY_KEY:-$HOME/.ssh/us2us_deploy_ed25519}"
task_root="${US2US_APP_ROOT:?set US2US_APP_ROOT=/path/on/server}"
task_port="${US2US_PORT:-3000}"
task_keep="${US2US_KEEP_RELEASES:-3}"
# Values become remote shell arguments; reject shell metacharacters before SSH/rsync.
[[ "$task_target" =~ ^[A-Za-z0-9_.-]+@[A-Za-z0-9.-]+$ ]] || { echo 'Invalid deployment target' >&2; exit 2; }
[[ "$task_root" =~ ^/[A-Za-z0-9_./-]+$ && "$task_root" != / && "$task_root" != *'/../'* && "$task_root" != */.. ]] || { echo 'Invalid application root' >&2; exit 2; }
node -e 'const p=require("node:path").posix,s=process.argv[1];if(s==="/"||p.normalize(s)!==s)process.exit(1)' "$task_root" || { echo 'Application root must be a normalized absolute directory' >&2; exit 2; }
[[ "$task_port" =~ ^[0-9]+$ && "$task_port" -gt 0 && "$task_port" -le 65535 ]] || { echo 'Invalid port' >&2; exit 2; }
[[ "$task_keep" =~ ^[0-9]+$ && "$task_keep" -gt 0 && "$task_keep" -le 50 ]] || { echo 'Invalid release retention' >&2; exit 2; }
ssh_opts=(-i "$task_key" -o IdentitiesOnly=yes -o BatchMode=yes -o ConnectTimeout=15)
release="$(date -u +%Y%m%d-%H%M%S)"
work="$(mktemp -d "${TMPDIR:-/tmp}/us2us-relay-release.XXXXXX")"
trap 'rm -rf "$work"' EXIT
node "$here/scripts/prepare-relay-release.mjs" "$here/relay" "$work/release"
node_major="$(node -p 'process.versions.node.split(".")[0]')"

# Before upload, require both private files. No values are printed or changed.
ssh "${ssh_opts[@]}" "$task_target" 'test -f "$HOME/us2us.env" && test -f "$HOME/us2us-migrate.env"' || {
  echo 'Both us2us.env and separate us2us-migrate.env are required; nothing uploaded' >&2; exit 1;
}
printf -v rsync_ssh '%q ' ssh "${ssh_opts[@]}"
echo '==> uploading runtime-only release'
rsync -az -e "$rsync_ssh" "$work/release/" "$task_target:$task_root/releases/$release/"

ssh "${ssh_opts[@]}" "$task_target" "bash -s -- '$task_root' '$release' '$task_port' '$task_keep' '$node_major'" <<'REMOTE'
set -euo pipefail
task_root="$1"; release="$2"; task_port="$3"; task_keep="$4"; expected_node="$5"
export NVM_DIR="$HOME/.nvm"
. "$NVM_DIR/nvm.sh" >/dev/null
release_dir="$task_root/releases/$release"
live="$task_root/us2us-relay"
previous="$(readlink "$live" || true)"
cd "$release_dir"
[ "$(node -p 'process.versions.node.split(".")[0]')" = "$expected_node" ] || { echo 'Node major differs from build host' >&2; exit 1; }
[ -f "$task_root/ecosystem.config.cjs" ] || { echo 'Server pm2 configuration is missing' >&2; exit 1; }
# Verify the package without trusting any uploaded executable to validate itself.
node --input-type=module - <<'VERIFY'
import {createHash} from 'node:crypto'; import {readFileSync,readdirSync,lstatSync} from 'node:fs';
import {join,relative,resolve} from 'node:path';
const root=process.cwd(), meta=JSON.parse(readFileSync('release.json','utf8'));
if(meta.format!==1 || meta.nodeMajor!==Number(process.versions.node.split('.')[0]) || !Array.isArray(meta.files)) throw Error('Invalid release metadata');
const walk=d=>readdirSync(d).sort().flatMap(n=>{const p=join(d,n),s=lstatSync(p);if(s.isSymbolicLink()||(!s.isDirectory()&&!s.isFile()))throw Error('Invalid release file');return s.isDirectory()?walk(p):[relative(root,p)]});
const actual=walk(root).filter(p=>p!=='release.json');
const expected=meta.files.map(f=>f.path);
if(JSON.stringify(actual)!==JSON.stringify(expected))throw Error('Release file list mismatch');
for(const f of meta.files){if(resolve(root,f.path).startsWith(root+'/')!==true)throw Error('Invalid release path');if(createHash('sha256').update(readFileSync(f.path)).digest('hex')!==f.sha256)throw Error('Release checksum mismatch');}
console.log('Runtime release checksums verified (not a signature)');
VERIFY
if ! cmp -s deploy/ecosystem.config.cjs "$task_root/ecosystem.config.cjs"; then
  echo 'note: server pm2 configuration differs from the supplied template; it was not replaced'
fi
node deploy/runtime-migrate.mjs --check "$HOME/us2us-migrate.env" "$HOME/us2us.env"
# Migration worker inherits no relay environment or secrets; file is read as data, never sourced.
node_path="$(command -v node)"
env -i PATH="$(dirname "$node_path"):/usr/bin:/bin" "$node_path" deploy/runtime-migrate.mjs --migrate "$HOME/us2us-migrate.env"
rollback() {
  if [ -n "$previous" ]; then
    ln -sfn "$previous" "$live.next" && mv -Tf "$live.next" "$live"
    pm2 reload us2us-relay >/dev/null
    echo 'Previous code release restored; schema was not rolled back' >&2
  fi
}
ln -sfn "$release_dir" "$live.next" && mv -Tf "$live.next" "$live"
if ! pm2 startOrReload "$task_root/ecosystem.config.cjs" --update-env >/dev/null; then rollback; exit 1; fi
healthy=""
for _ in $(seq 1 20); do
  sleep 1
  if curl -fsS -m 2 "http://127.0.0.1:$task_port/v1/health" >/dev/null 2>&1; then healthy=1; break; fi
done
if [ -z "$healthy" ]; then rollback; echo 'Health check failed' >&2; exit 1; fi
# Check actual pm2 runtime before accepting the new release. Never print its environment.
if ! pm2 jlist 2>/dev/null | node -e '
let out="";process.stdin.on("data",c=>out+=c).on("end",()=>{for(const line of out.split("\n").filter(l=>l.startsWith("["))){try{const app=JSON.parse(line).find(p=>p.name==="us2us-relay")?.pm2_env??{};const ok=String(app.pm_exec_path).endsWith("/.output/server/index.mjs")&&(app.env?.NODE_ENV??app.NODE_ENV)==="production";process.exit(ok?0:1)}catch{}}process.exit(1)})'; then
  rollback; echo 'pm2 is not running the production built entry' >&2; exit 1
fi
pm2 save >/dev/null
# Only tool-created timestamp releases; preserve both active and rollback releases.
ls -1dt "$task_root"/releases/*/ | tail -n +"$((task_keep + 1))" | while read -r old; do
  base="$(basename "$old")"
  [[ "$base" =~ ^[0-9]{8}-[0-9]{6}$ ]] || continue
  [ "${old%/}" = "$(readlink "$live")" ] || [ "${old%/}" = "$previous" ] || rm -rf "$old"
done
echo 'Runtime-only release is healthy'
REMOTE
