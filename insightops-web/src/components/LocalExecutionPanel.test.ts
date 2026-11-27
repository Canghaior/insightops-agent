// @vitest-environment happy-dom
import { createApp, nextTick, type App } from 'vue'
import { afterEach, beforeEach, expect, it } from 'vitest'
import { apiClient } from '@/api/client'
import { taskDraft } from '@/utils/taskFields'
import LocalExecutionPanel from './LocalExecutionPanel.vue'

const original = apiClient.defaults.adapter
const time = '2026-11-25T00:00:00Z'
const input = { requestId: 'request', workspace: 'amount', sourceRevision: 'a'.repeat(40),
  modelChoice: 'gpt-6.2-astra', instruction: '拒绝零金额', writeScopes: ['src/main/'] }
const run = { id: 'saved', status: 'SUCCEEDED', input, finishedAt: time, createdAt: time, baselineVersion: 1 }
let host: HTMLElement, app: App, history: boolean, saved: Record<string, unknown> | undefined
let events: unknown[]
beforeEach(() => {
  events = []; history = false; saved = undefined
  apiClient.defaults.adapter = async config => {
    let data: unknown
    if (config.url?.endsWith('/local-execution')) {
      data = { enabled: true, taskReady: true, workspaces: [{ id: 'amount', revision: input.sourceRevision }],
        models: [{ id: input.modelChoice, provider: 'OpenAI', label: 'Astra' }], runs: history ? [run] : [] }
    } else if (config.method === 'post') {
      saved = JSON.parse(config.data)
      data = run
    } else data = { run, events }
    return { config, data: { data }, headers: {}, status: 200, statusText: 'OK' }
  }
})
afterEach(() => { app?.unmount(); host?.remove(); apiClient.defaults.adapter = original })
const settle = async () => { await new Promise(resolve => setTimeout(resolve, 0)); await nextTick() }
async function open(inScope: string) {
  host = document.createElement('div'); document.body.appendChild(host)
  app = createApp(LocalExecutionPanel, { reportId: 'report', taskVersionId: 'task',
    frozenTask: { ...taskDraft(), question: '拒绝零金额', inScope, completion: '正金额保留两位' } })
  app.mount(host); await settle()
}

it('prefills the frozen question and explicit paths and submits selected classes as a named plan', async () => {
  await open('只改 src/main/java/example/Rule.java 和 src/test/java/example/')
  const [instruction, scopes] = [...host.querySelectorAll('textarea')]
  expect(instruction!.value).toContain('拒绝零金额')
  expect(scopes!.value).toBe('src/main/java/example/Rule.java\nsrc/test/java/example/')
  const profile = [...host.querySelectorAll('select')].at(-1)!
  profile.selectedIndex = 1; profile.dispatchEvent(new Event('change')); await nextTick()
  const selector = [...host.querySelectorAll('textarea')].at(-1)!
  selector.value = 'AmountRuleTest,NewRuleTest'; selector.dispatchEvent(new Event('input')); await nextTick()
  host.querySelector('form')!.dispatchEvent(new Event('submit', { cancelable: true })); await settle()
  expect(saved?.buildPlan).toEqual({ mode: 'SELECTED_CLASSES', testClasses: ['AmountRuleTest', 'NewRuleTest'] })
})

it('leaves textual scope for confirmation, and old runs keep their original evidence presentation', async () => {
  history = true
  await open('仅修改金额规则')
  expect(host.querySelectorAll('textarea')[1]!.value).toBe('')
  expect(host.textContent).toContain('冻结任务范围未列出具体路径')
  expect(host.querySelector('[aria-label="回归用例对照"]')).toBeNull()
})

it('renders new test discrimination separately from original coverage and shows the executed command', async () => {
  history = true
  events = [{ sequence: 1, type: 'BUILD', message: '候选', details: { command: ['mvn', '-Dtest=MoneyTest', 'test'] } },
    { sequence: 2, type: 'REGRESSION', message: '回归', details: { comparison: { baselineExecuted: 2,
      baselinePassingOnCandidate: 2, failedThenPassed: 1, undistinguished: 1, testFiles: [], limitation: '',
      cases: [{ suite: 'X', className: 'X', name: 'zero', outcome: 'FAILURE_TO_PASS', reason: '' }] } } }]
  await open('src/main/')
  const summary = host.querySelector('[aria-label="回归用例对照"]')!.textContent
  expect(summary).toContain('原回归通过 2 项')
  expect(summary).toContain('在候选通过 1 项')
  expect(summary).toContain('1 组无法区分')
  expect(host.textContent).toContain('mvn -Dtest=MoneyTest test')
})
