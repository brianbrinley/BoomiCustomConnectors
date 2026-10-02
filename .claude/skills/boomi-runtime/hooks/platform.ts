// A small Boomi Platform API client: just the calls a runtime's lifecycle needs.
// Auth matches Boomi Companion's scripts: BOOMI_TOKEN.<username> and the API token.

import type { HttpInit, HttpResponse } from 'claude-code'

import type { Credentials } from './envfile'

export type Fetch = (url: string, init?: HttpInit) => Promise<HttpResponse>

export type Atom = {
  id: string
  name: string
  status: string
  hostName?: string
}

export type Execution = {
  executionId: string
  processName: string
  status: string
  executionTime: string
}

export type ExecutionOutcome = {
  executionId: string
  status: string
}

export class PlatformError extends Error {
  constructor(
    readonly action: string,
    readonly status: number,
    body: string,
  ) {
    super(`${action} failed (HTTP ${status})${summary(body)}`)
  }
}

// The platform's error text, cut short: enough to diagnose, never a whole page.
const summary = (body: string): string => {
  try {
    const parsed = JSON.parse(body) as { message?: unknown }
    if (typeof parsed.message === 'string') return `: ${parsed.message.slice(0, 200)}`
  } catch {}
  const text = body.replace(/\s+/g, ' ').trim().slice(0, 200)
  return text ? `: ${text}` : ''
}

const requireString = (value: unknown, action: string, field: string): string => {
  if (typeof value === 'string' && value) return value
  throw new PlatformError(action, 200, `no ${field} in the answer`)
}

/** Reads `name` from a JSON or XML answer: `"name": "v"` or `name="v"`. */
export const attribute = (text: string, name: string): string | undefined =>
  text.match(new RegExp(`"?${name}"?\\s*[:=]\\s*"([^"]+)"`))?.[1]

export const escapeXml = (text: string): string =>
  text.replace(/[<>&"']/g, char => `&#${char.charCodeAt(0)};`)

export const basicAuth = (credentials: Credentials): string => {
  const pair = `BOOMI_TOKEN.${credentials.username}:${credentials.apiToken}`
  const bytes = new TextEncoder().encode(pair)
  let binary = ''
  for (const byte of bytes) binary += String.fromCharCode(byte)
  return `Basic ${btoa(binary)}`
}

const byName = (name: string) => ({
  QueryFilter: {
    expression: { operator: 'EQUALS', property: 'name', argument: [name] },
  },
})

export const platformClient = (fetch: Fetch, credentials: Credentials) => {
  const base = `${credentials.apiUrl}/api/rest/v1/${credentials.accountId}`
  const headers = {
    Accept: 'application/json',
    'Content-Type': 'application/json',
    Authorization: basicAuth(credentials),
  }

  const send = async (
    action: string,
    method: string,
    path: string,
    body?: string,
    contentType = 'application/json',
  ): Promise<HttpResponse> => {
    const response = await fetch(`${base}/${path}`, {
      method,
      headers: { ...headers, 'Content-Type': contentType },
      body,
    })
    if (!response.ok) throw new PlatformError(action, response.status, response.text)
    return response
  }

  const call = async (
    action: string,
    method: string,
    path: string,
    body?: unknown,
  ): Promise<unknown> => {
    const response = await send(
      action,
      method,
      path,
      body === undefined ? undefined : JSON.stringify(body),
    )
    return response.text ? JSON.parse(response.text) : undefined
  }

  const results = (answer: unknown): Record<string, unknown>[] => {
    const list = (answer as { result?: unknown } | undefined)?.result
    return Array.isArray(list) ? list : []
  }

  const toAtom = (raw: Record<string, unknown>): Atom => ({
    id: String(raw.id ?? ''),
    name: String(raw.name ?? ''),
    status: String(raw.status ?? 'UNKNOWN'),
    hostName: raw.hostName === undefined ? undefined : String(raw.hostName),
  })

  return {
    /** A short-lived token the quiet installer registers the runtime with. */
    createInstallerToken: async (durationMinutes = 60): Promise<string> => {
      const answer = (await call('Create installer token', 'POST', 'InstallerToken', {
        installType: 'ATOM',
        durationMinutes,
      })) as { token?: unknown } | undefined
      if (typeof answer?.token !== 'string') {
        throw new PlatformError('Create installer token', 200, 'no token in the answer')
      }
      return answer.token
    },

    findAtom: async (name: string): Promise<Atom | undefined> => {
      const found = results(await call('Find runtime', 'POST', 'Atom/query', byName(name)))
      return found[0] ? toAtom(found[0]) : undefined
    },

    atomsNamedLike: async (prefix: string): Promise<Atom[]> => {
      const query = {
        QueryFilter: {
          expression: { operator: 'LIKE', property: 'name', argument: [`${prefix}%`] },
        },
      }
      return results(await call('List runtimes', 'POST', 'Atom/query', query)).map(toAtom)
    },

    attach: async (atomId: string, environmentId: string): Promise<void> => {
      await call('Attach runtime to environment', 'POST', 'EnvironmentAtomAttachment', {
        atomId,
        environmentId,
      })
    },

    deleteAtom: async (atomId: string): Promise<void> => {
      await call('Delete runtime', 'DELETE', `Atom/${encodeURIComponent(atomId)}`)
    },

    recentExecutions: async (atomId: string, limit = 5): Promise<Execution[]> => {
      const query = {
        QueryFilter: {
          expression: { operator: 'EQUALS', property: 'atomId', argument: [atomId] },
        },
        QuerySort: { sortField: [{ fieldName: 'executionTime', sortOrder: 'DESC' }] },
      }
      return results(await call('List executions', 'POST', 'ExecutionRecord/query', query))
        .slice(0, limit)
        .map(raw => ({
          executionId: String(raw.executionId ?? ''),
          processName: String(raw.processName ?? ''),
          status: String(raw.status ?? ''),
          executionTime: String(raw.executionTime ?? ''),
        }))
    },

    createEnvironment: async (name: string): Promise<string> => {
      const answer = (await call('Create environment', 'POST', 'Environment', {
        name,
        classification: 'TEST',
      })) as { id?: unknown } | undefined
      return requireString(answer?.id, 'Create environment', 'id')
    },

    deleteEnvironment: async (environmentId: string): Promise<void> => {
      await call('Delete environment', 'DELETE', `Environment/${encodeURIComponent(environmentId)}`)
    },

    /** Creates a component from its XML; resolves its componentId. */
    createComponent: async (xml: string): Promise<string> => {
      const response = await send('Create component', 'POST', 'Component', xml, 'application/xml')
      return requireString(attribute(response.text, 'componentId'), 'Create component', 'componentId')
    },

    deleteComponent: async (componentId: string): Promise<void> => {
      await call('Delete component', 'DELETE', `Component/${encodeURIComponent(componentId)}`)
    },

    packageComponent: async (componentId: string, packageVersion: string): Promise<string> => {
      const answer = (await call('Package component', 'POST', 'PackagedComponent', {
        componentId,
        packageVersion,
        notes: 'boomi-runtime smoke test',
      })) as { packageId?: unknown } | undefined
      return requireString(answer?.packageId, 'Package component', 'packageId')
    },

    deploy: async (packageId: string, environmentId: string): Promise<string> => {
      const answer = (await call('Deploy package', 'POST', 'DeployedPackage', {
        packageId,
        environmentId,
        notes: 'boomi-runtime smoke test',
      })) as { deploymentId?: unknown } | undefined
      return requireString(answer?.deploymentId, 'Deploy package', 'deploymentId')
    },

    undeploy: async (deploymentId: string): Promise<void> => {
      await call('Undeploy package', 'DELETE', `DeployedPackage/${encodeURIComponent(deploymentId)}`)
    },

    /** Starts a process on a runtime; resolves the request ID to poll. */
    execute: async (processId: string, atomId: string): Promise<string> => {
      const xml = `<?xml version="1.0" encoding="UTF-8"?><ExecutionRequest processId="${escapeXml(processId)}" atomId="${escapeXml(atomId)}" xmlns="http://api.platform.boomi.com/"/>`
      const response = await send('Execute process', 'POST', 'ExecutionRequest', xml, 'application/xml')
      return requireString(attribute(response.text, 'requestId'), 'Execute process', 'requestId')
    },

    /** The execution a request started, or undefined while it is still running. */
    executionOutcome: async (requestId: string): Promise<ExecutionOutcome | undefined> => {
      const response = await send(
        'Read execution',
        'GET',
        `ExecutionRecord/async/${encodeURIComponent(requestId)}`,
      )
      if (response.status === 202 || !response.text) return undefined
      const record = results(JSON.parse(response.text))[0]
      const status = String(record?.status ?? '')
      if (!record || status === 'INPROCESS' || status === 'STARTED') return undefined
      return { executionId: String(record.executionId ?? ''), status }
    },
  }
}

export type PlatformClient = ReturnType<typeof platformClient>
