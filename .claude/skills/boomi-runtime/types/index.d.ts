export type RuntimePhase =
  | 'idle'
  | 'installing'
  | 'starting'
  | 'online'
  | 'stopping'
  | 'error'

export type RuntimeExecution = {
  id: string
  process: string
  status: string
  at: string
}

export type SmokeTest = {
  phase: 'running' | 'passed' | 'failed'
  message: string
  componentId?: string
  packageId?: string
  deploymentId?: string
  requestId?: string
  executionId?: string
  status?: string
}

export type RuntimeInfo = {
  phase: RuntimePhase
  message: string
  name?: string
  atomId?: string
  installDir?: string
  platformStatus?: string
  environmentId?: string
  /** Set when this mod created the environment, which teardown then deletes. */
  isOwnEnvironment?: boolean
  /** BOOMI_TEST_ATOM_ID and BOOMI_ENVIRONMENT_ID as they were before this runtime. */
  previousAtomId?: string
  previousEnvironmentId?: string
  smoke?: SmokeTest
  executions: RuntimeExecution[]
  log: string[]
}

declare module 'claude-code' {
  interface PluginState {
    'boomi-runtime': { runtime: RuntimeInfo }
  }
}
