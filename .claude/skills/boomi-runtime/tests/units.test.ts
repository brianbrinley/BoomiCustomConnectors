import { describe, expect, test } from 'claude-code/testing'

import { credentialsFrom, parseEnv, setEnvValue } from '../hooks/envfile'
import { isRuntimeDir, linux, runtimeName } from '../hooks/linux'
import { attribute, basicAuth, platformClient } from '../hooks/platform'
import type { Fetch } from '../hooks/platform'
import { helloWorldProcess } from '../hooks/smoke'

const CREDENTIALS = {
  apiUrl: 'https://api.boomi.com',
  username: 'me@example.com',
  apiToken: 'secret',
  accountId: 'acct-123',
}

describe('the Companion .env', () => {
  test('parses quotes, exports and comments', () => {
    const values = parseEnv(
      '# comment\nexport BOOMI_API_URL="https://api.boomi.com"\nBOOMI_USERNAME=me # note\nEMPTY=\n',
    )
    expect(values.BOOMI_API_URL).toBe('https://api.boomi.com')
    expect(values.BOOMI_USERNAME).toBe('me')
    expect(values.EMPTY).toBe('')
  })

  test('sets, replaces and removes a value, keeping CRLF and the rest', () => {
    const text = 'A=1\r\nBOOMI_TEST_ATOM_ID=old\r\nB=2\r\n'
    expect(setEnvValue(text, 'BOOMI_TEST_ATOM_ID', 'new')).toBe(
      'A=1\r\nBOOMI_TEST_ATOM_ID=new\r\nB=2\r\n',
    )
    expect(setEnvValue(text, 'BOOMI_TEST_ATOM_ID', undefined)).toBe('A=1\r\nB=2\r\n')
    expect(setEnvValue('A=1', 'C', '3')).toBe('A=1\nC=3')
    expect(setEnvValue('', 'C', '3')).toBe('C=3\n')
  })

  test('names the credentials that are missing', () => {
    expect(credentialsFrom({ BOOMI_API_URL: 'x' })).toEqual({
      missing: ['BOOMI_USERNAME', 'BOOMI_API_TOKEN', 'BOOMI_ACCOUNT_ID'],
    })
    const found = credentialsFrom({
      BOOMI_API_URL: 'https://api.boomi.com/',
      BOOMI_USERNAME: 'u',
      BOOMI_API_TOKEN: 't',
      BOOMI_ACCOUNT_ID: 'a',
    })
    expect('credentials' in found && found.credentials.apiUrl).toBe('https://api.boomi.com')
  })
})

describe('the Linux runtime', () => {
  test('names runtimes from prefix, date and session', () => {
    const name = runtimeName('cc', 'session_017E1Ug18pGT1QjTdwMArAYS', new Date('2026-10-02T12:00:00Z'))
    expect(name).toBe('cc-20261002-dwmarays')
  })

  test('removes only folders it made', () => {
    expect(isRuntimeDir('/root/.boomi-runtimes/cc-1', '/root/.boomi-runtimes', 'cc')).toBe(true)
    expect(isRuntimeDir('/root/.boomi-runtimes', '/root/.boomi-runtimes', 'cc')).toBe(false)
    expect(isRuntimeDir('/root/.boomi-runtimes/other', '/root/.boomi-runtimes', 'cc')).toBe(false)
    expect(isRuntimeDir('/root/.boomi-runtimes/cc-1/x', '/root/.boomi-runtimes', 'cc')).toBe(false)
    expect(isRuntimeDir('/etc/cc-1', '/root/.boomi-runtimes', 'cc')).toBe(false)
    expect(isRuntimeDir('/cc-1', '', 'cc')).toBe(false)
  })

  test('installs quietly with the token and name', () => {
    const argv = linux.install({
      installer: '/r/atom_install64.sh',
      dir: '/r/cc-1',
      name: 'cc-1',
      accountId: 'acct',
      token: 'tok',
      extraArgs: ['-VproxyHost=p'],
    })
    expect(argv).toEqual([
      '/r/atom_install64.sh', '-q', '-console', '-dir', '/r/cc-1',
      '-VatomName=cc-1', '-VaccountId=acct', '-VinstallToken=tok', '-VproxyHost=p',
    ])
  })
})

describe('the Platform API client', () => {
  const recorder = (answers: Record<string, { status: number; text: string }>) => {
    const calls: { url: string; method: string; body?: string; headers: Record<string, string> }[] = []
    const fetch: Fetch = async (url, init) => {
      calls.push({ url, method: init?.method ?? 'GET', body: init?.body, headers: init?.headers ?? {} })
      const path = url.split('/acct-123/')[1] ?? ''
      const answer = answers[path] ?? { status: 404, text: '{"message":"not here"}' }
      return { ...answer, ok: answer.status < 300, headers: {} }
    }
    return { calls, fetch }
  }

  test('authenticates the way Companion does', () => {
    expect(basicAuth(CREDENTIALS)).toBe(`Basic ${btoa('BOOMI_TOKEN.me@example.com:secret')}`)
  })

  test('creates an installer token and an environment', async () => {
    const { calls, fetch } = recorder({
      InstallerToken: { status: 200, text: '{"token":"tok-1"}' },
      Environment: { status: 200, text: '{"id":"env-1","name":"cc-1"}' },
    })
    const platform = platformClient(fetch, CREDENTIALS)
    expect(await platform.createInstallerToken(30)).toBe('tok-1')
    expect(JSON.parse(calls[0]?.body ?? '')).toEqual({ installType: 'ATOM', durationMinutes: 30 })
    expect(await platform.createEnvironment('cc-1')).toBe('env-1')
    expect(JSON.parse(calls[1]?.body ?? '')).toEqual({ name: 'cc-1', classification: 'TEST' })
    expect(calls[1]?.url).toBe('https://api.boomi.com/api/rest/v1/acct-123/Environment')
  })

  test('reports a refusal with its status and message', async () => {
    const platform = platformClient(recorder({}).fetch, CREDENTIALS)
    await expect(platform.findAtom('x')).rejects.toThrow('Find runtime failed (HTTP 404): not here')
  })

  test('executes over XML and waits through 202', async () => {
    const { calls, fetch } = recorder({
      ExecutionRequest: { status: 200, text: '<bns:ExecutionRequest requestId="req-1" xmlns:bns="x"/>' },
      'ExecutionRecord/async/req-1': { status: 202, text: '' },
    })
    const platform = platformClient(fetch, CREDENTIALS)
    expect(await platform.execute('proc-1', 'atom-1')).toBe('req-1')
    expect(calls[0]?.headers['Content-Type']).toBe('application/xml')
    expect(calls[0]?.body).toContain('processId="proc-1" atomId="atom-1"')
    expect(await platform.executionOutcome('req-1')).toBeUndefined()
  })

  test('reads the finished execution', async () => {
    const { fetch } = recorder({
      'ExecutionRecord/async/req-1': {
        status: 200,
        text: '{"result":[{"executionId":"execution-1","status":"COMPLETE"}]}',
      },
    })
    const platform = platformClient(fetch, CREDENTIALS)
    expect(await platform.executionOutcome('req-1')).toEqual({
      executionId: 'execution-1',
      status: 'COMPLETE',
    })
  })

  test('reads attributes from JSON and XML answers', () => {
    expect(attribute('{"componentId" : "c-1"}', 'componentId')).toBe('c-1')
    expect(attribute('<bns:Component componentId="c-2" name="n">', 'componentId')).toBe('c-2')
  })
})

describe('the smoke test process', () => {
  test('is a No Data start, a message, a notify and a stop', () => {
    const xml = helloWorldProcess('cc-1 <hello>', 'folder-1')
    expect(xml).toContain('<noaction/>')
    expect(xml).toContain('folderId="folder-1"')
    expect(xml).toContain('name="cc-1 &#60;hello&#62; "'.replace(' "', '"'))
    expect(xml).toContain('shapetype="stop"')
    expect(helloWorldProcess('x')).not.toContain('folderId')
  })
})
