// pm2 app of the shared host (relay/README.md, "Shared host with pm2").
//
// Copy this file unchanged to $US2US_APP_ROOT/ecosystem.config.cjs: the paths
// follow from where that copy lives and from the home directory of the user
// that runs pm2. scripts/deploy-relay.sh never replaces the server's copy; it
// only says when the two differ. pm2 reads script, node_args and exec_mode
// only when an app is started, so apply a changed copy with
//   pm2 delete us2us-relay && pm2 start ecosystem.config.cjs && pm2 save
const { homedir } = require('node:os')
const { join } = require('node:path')

module.exports = {
  apps: [
    {
      name: 'us2us-relay',
      // Symlink to the live release, switched by scripts/deploy-relay.sh.
      cwd: join(__dirname, 'us2us-relay'),
      // The built server (pnpm build), never `nuxt dev`.
      script: '.output/server/index.mjs',
      // Settings and secrets (mode 600): DATABASE_URL, POW_SECRET, HOST=127.0.0.1,
      // PORT (= US2US_PORT), TRUST_PROXY=1, MEDIA_DIR. Values set in `env`
      // below win over the file.
      node_args: `--env-file=${join(homedir(), 'us2us.env')}`,
      // One process: sessions, the online fast path and the rate limits live in memory.
      exec_mode: 'fork',
      instances: 1,
      env: { NODE_ENV: 'production' },
      // pm2 stops with SIGINT; the relay then closes sockets with 1001 and
      // writes the envelopes it still holds in memory before it exits. Above
      // Nitro's own shutdown limit (30 s), so pm2 never kills it mid-write.
      kill_timeout: 35000,
      // Shared host: a graceful restart instead of growing without bound. Keep
      // it well above the 128 MiB of envelopes the relay may hold in memory.
      max_memory_restart: '512M',
      // Retries with growing delays (up to 15 s) while MySQL is unreachable,
      // instead of giving up after a burst of quick restarts.
      exp_backoff_restart_delay: 100,
    },
  ],
}
