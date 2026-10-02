import type { CommandRunInput } from 'claude-code'
import { expect, test } from 'claude-code/testing'

const boomi = (args: string) => ({ command: 'boomi', args }) as CommandRunInput

test('/boomi lists its actions and status starts idle', async $ => {
  const usage = await $.command.run(boomi('help'))
  expect(usage.text).toContain('/boomi up')
  expect(usage.text).toContain('/boomi smoke')

  const status = await $.command.run(boomi('status'))
  expect(status.text).toContain('phase: idle')
})

test('the smoke test refuses without an online runtime', async $ => {
  await $.command.run(boomi('smoke'))
  const status = await $.command.run(boomi('status'))
  expect(status.text).toContain('smoke test: failed: No online runtime')
})
