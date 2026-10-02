// The Linux runtime: how it is named, installed, started, stopped and removed.
// Other platforms (Windows, containers) are meant to sit beside this file with
// the same shape.

export const runtimeName = (prefix: string, sessionId: string, now: Date): string => {
  const date = now.toISOString().slice(0, 10).replaceAll('-', '')
  const session = sessionId.replace(/[^A-Za-z0-9]/g, '').slice(-8)
  return `${prefix}-${date}-${session}`.toLowerCase().replace(/[^a-z0-9-]/g, '-')
}

export const splitArgs = (text: string): string[] => text.trim().split(/\s+/).filter(Boolean)

/** True when `dir` is a runtime folder this mod made: one level under `root`. */
export const isRuntimeDir = (dir: string, root: string, prefix: string): boolean => {
  const base = root.replace(/\/+$/, '')
  if (!base.startsWith('/') || base === '' || !dir.startsWith(`${base}/`)) return false
  const rest = dir.slice(base.length + 1)
  return !rest.includes('/') && rest.startsWith(`${prefix}-`) && !rest.includes('..')
}

export type InstallRequest = {
  installer: string
  dir: string
  name: string
  accountId: string
  token: string
  extraArgs: string[]
}

export const linux = {
  installerPath: (root: string) => `${root}/atom_install64.sh`,
  download: (url: string, dest: string) => ['curl', '-fsSL', '--retry', '3', '-o', dest, url],
  makeExecutable: (path: string) => ['chmod', '+x', path],
  install: (request: InstallRequest) => [
    request.installer,
    '-q',
    '-console',
    '-dir',
    request.dir,
    `-VatomName=${request.name}`,
    `-VaccountId=${request.accountId}`,
    `-VinstallToken=${request.token}`,
    ...request.extraArgs,
  ],
  launcher: (dir: string) => `${dir}/bin/atom`,
  start: (dir: string) => [`${dir}/bin/atom`, 'start'],
  stop: (dir: string) => [`${dir}/bin/atom`, 'stop'],
  remove: (dir: string) => ['rm', '-rf', '--', dir],
  logsDir: (dir: string) => `${dir}/logs`,
}

/** The last `count` lines of `text`, without a trailing blank line. */
export const tail = (text: string, count: number): string[] =>
  text.replace(/\s+$/, '').split(/\r?\n/).slice(-count)
