#!/usr/bin/env node
// Packages an already-built relay. No install scripts, network, SSH or secret environment reads.
import { createHash } from 'node:crypto'
import { cpSync, existsSync, lstatSync, mkdirSync, readFileSync, readdirSync, realpathSync, rmSync, writeFileSync } from 'node:fs'
import { basename, dirname, isAbsolute, join, relative, resolve, sep } from 'node:path'
import { fileURLToPath } from 'node:url'

const metadataName = 'release.json'
const payload = ['.output', 'drizzle', 'deploy/ecosystem.config.cjs', 'deploy/runtime-migrate.mjs']
const required = [
  '.output/server/index.mjs', '.output/server/package.json', 'drizzle/meta/_journal.json',
  '.output/server/node_modules/drizzle-orm/mysql2/index.js',
  '.output/server/node_modules/drizzle-orm/mysql2/migrator.js',
  'deploy/ecosystem.config.cjs', 'deploy/runtime-migrate.mjs',
]
const buildPackages = ['nuxt', 'nitropack', 'vite', 'vitest', 'typescript', 'drizzle-kit', 'node-forge', 'braces']
const privateKey = /-----BEGIN (?:RSA |EC |OPENSSH |DSA |ENCRYPTED )?PRIVATE KEY-----/
const digest = bytes => createHash('sha256').update(bytes).digest('hex')
const inside = (root, path) => { const part = relative(root, path); return part === '' || (!part.startsWith(`..${sep}`) && part !== '..' && !isAbsolute(part)) }

function allowed(name) {
  return name.startsWith('.output/') || name.startsWith('drizzle/') || payload.includes(name)
}

function files(root, directory = root, ancestors = new Set()) {
  const real = realpathSync(directory)
  if (!inside(root, real) || ancestors.has(real)) throw new Error('release contains an external or cyclic symlink')
  const next = new Set(ancestors).add(real)
  return readdirSync(directory).sort().flatMap(name => {
    const path = join(directory, name)
    const actual = realpathSync(path)
    if (!inside(root, actual)) throw new Error('release contains an external symlink')
    const stat = lstatSync(actual)
    if (stat.isDirectory()) return files(root, path, next)
    if (!stat.isFile()) throw new Error('release contains a non-regular file')
    return [path]
  })
}

function inspect(root) {
  for (const name of required) if (!existsSync(join(root, name))) throw new Error(`required runtime file missing: ${name}`)
  const entries = []
  for (const path of files(root)) {
    const name = relative(root, path).split(sep).join('/')
    if (name === metadataName) continue
    if (!allowed(name) || name.split('/').some(part => /^\.env(?:\.|$)/.test(part)) || /\.(?:p8|p12|jks|keystore|key|mobileprovision|provisionprofile)$/.test(name)) {
      throw new Error('release contains a file outside the runtime allowlist or a credential file')
    }
    const bytes = readFileSync(path)
    if (privateKey.test(bytes.toString('utf8'))) throw new Error('release contains a private key')
    entries.push({ path: name, sha256: digest(bytes) })
  }
  const dependencies = JSON.parse(readFileSync(join(root, '.output/server/package.json'), 'utf8')).dependencies ?? {}
  if (!dependencies.mysql2 || !dependencies['drizzle-orm'] || buildPackages.some(name => name in dependencies)) {
    throw new Error('release runtime dependency manifest is missing a required package or includes build tools')
  }
  return entries
}

export function prepareRelease(sourcePath, destinationPath) {
  const source = realpathSync(sourcePath)
  // macOS /tmp and /var are links to /private: compare canonical parents on both
  // sides so a normal fresh directory is not mistaken for an escaping symlink.
  const resolved = resolve(destinationPath)
  const destination = join(realpathSync(dirname(resolved)), basename(resolved))
  if (existsSync(destination)) throw new Error('release destination must not exist')
  for (const name of payload) {
    const path = join(source, name)
    if (!existsSync(path)) throw new Error(`runtime input missing: ${name}; build the relay first`)
    if (!inside(source, realpathSync(path))) throw new Error('release input follows an external symlink')
    if (inside(path, destination)) throw new Error('release destination overlaps its input')
    // Validate links before copying: even a dependency link may not leave its payload tree.
    if (lstatSync(path).isDirectory()) files(realpathSync(path))
    else if (lstatSync(path).isSymbolicLink()) throw new Error('release input must not be a symlink')
  }
  mkdirSync(destination)
  try {
    for (const name of payload) {
      mkdirSync(dirname(join(destination, name)), { recursive: true })
      cpSync(join(source, name), join(destination, name), { recursive: true, dereference: true })
    }
    const entries = inspect(destination)
    const metadata = { format: 1, nodeMajor: Number(process.versions.node.split('.')[0]), files: entries }
    writeFileSync(join(destination, metadataName), `${JSON.stringify(metadata, null, 2)}\n`, { mode: 0o644 })
    return { files: entries.length, manifestSha256: digest(readFileSync(join(destination, metadataName))) }
  } catch (error) {
    rmSync(destination, { recursive: true, force: true }) // Only the fresh directory this call created.
    throw error
  }
}

export function verifyRelease(directory) {
  const root = realpathSync(directory)
  const metadata = JSON.parse(readFileSync(join(root, metadataName), 'utf8'))
  if (metadata.format !== 1 || metadata.nodeMajor !== Number(process.versions.node.split('.')[0]) || !Array.isArray(metadata.files)) {
    throw new Error('release format or Node major mismatch')
  }
  const actual = inspect(root)
  if (JSON.stringify(actual) !== JSON.stringify(metadata.files)) throw new Error('release file list or checksum mismatch')
  return { files: actual.length, manifestSha256: digest(readFileSync(join(root, metadataName))) }
}

if (process.argv[1] && realpathSync(process.argv[1]) === realpathSync(fileURLToPath(import.meta.url))) {
  try {
    const [option, arg, destination] = process.argv.slice(2)
    const result = option === '--verify' && arg && !destination
      ? verifyRelease(arg)
      : option && arg && !destination ? prepareRelease(option, arg) : null
    if (!result) throw new Error('usage: prepare-relay-release.mjs <built-relay-directory> <fresh-destination> | --verify <release>')
    console.log(JSON.stringify(result))
  } catch (error) {
    console.error(error instanceof Error ? error.message : 'release packaging failed')
    process.exitCode = 1
  }
}
