// Reads and edits Boomi Companion's .env without disturbing the rest of it.

const LINE = /^\s*(?:export\s+)?([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(.*?)\s*$/

const unquote = (raw: string): string => {
  const quoted = raw.match(/^(['"])(.*)\1$/)
  if (quoted) return quoted[2] ?? ''
  // An unquoted value ends at an inline comment.
  return raw.replace(/\s+#.*$/, '')
}

export const parseEnv = (text: string): Record<string, string> => {
  const values: Record<string, string> = {}
  for (const line of text.split(/\r?\n/)) {
    if (/^\s*#/.test(line)) continue
    const match = line.match(LINE)
    if (match && match[1]) values[match[1]] = unquote(match[2] ?? '')
  }
  return values
}

/**
 * Sets `key` to `value`, or removes it when `value` is undefined. Keeps the
 * file's line endings, its other lines and a trailing newline as they were.
 */
export const setEnvValue = (
  text: string,
  key: string,
  value: string | undefined,
): string => {
  const eol = text.includes('\r\n') ? '\r\n' : '\n'
  const hasTrailingEol = text === '' || text.endsWith('\n')
  const lines = text === '' ? [] : text.replace(/\r?\n$/, '').split(/\r?\n/)
  const isKey = (line: string) => line.match(LINE)?.[1] === key
  const next = value === undefined ? undefined : `${key}=${value}`

  const at = lines.findIndex(isKey)
  const kept = lines.filter((line, i) => !isKey(line) || i === at)
  if (at === -1) {
    if (next !== undefined) kept.push(next)
  } else {
    const index = kept.findIndex(isKey)
    if (next === undefined) kept.splice(index, 1)
    else kept[index] = next
  }

  const body = kept.join(eol)
  return body === '' ? '' : hasTrailingEol ? body + eol : body
}

export type Credentials = {
  apiUrl: string
  username: string
  apiToken: string
  accountId: string
  environmentId?: string
  testAtomId?: string
  targetFolder?: string
}

const REQUIRED = [
  'BOOMI_API_URL',
  'BOOMI_USERNAME',
  'BOOMI_API_TOKEN',
  'BOOMI_ACCOUNT_ID',
] as const

/** The Platform API credentials in a Companion .env, or the names missing. */
export const credentialsFrom = (
  values: Record<string, string>,
): { credentials: Credentials } | { missing: string[] } => {
  const missing = REQUIRED.filter(name => !values[name])
  if (missing.length > 0) return { missing }
  return {
    credentials: {
      apiUrl: (values.BOOMI_API_URL ?? '').replace(/\/+$/, ''),
      username: values.BOOMI_USERNAME ?? '',
      apiToken: values.BOOMI_API_TOKEN ?? '',
      accountId: values.BOOMI_ACCOUNT_ID ?? '',
      environmentId: values.BOOMI_ENVIRONMENT_ID || undefined,
      testAtomId: values.BOOMI_TEST_ATOM_ID || undefined,
      targetFolder: values.BOOMI_TARGET_FOLDER || undefined,
    },
  }
}
