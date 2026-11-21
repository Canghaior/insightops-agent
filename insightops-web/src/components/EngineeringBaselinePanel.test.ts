// @vitest-environment happy-dom
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { createApp, nextTick, type App } from 'vue'
import Panel from './EngineeringBaselinePanel.vue'
import { baselines, baselineDetail, previewBaseline, saveBaseline } from '@/api/engineeringBaselines'
import { baselineFixture } from '@/utils/engineeringBaseline.fixture'

vi.mock('@/api/engineeringBaselines', async original => ({
  ...await original<typeof import('@/api/engineeringBaselines')>(),
  baselines: vi.fn(), baselineDetail: vi.fn(), previewBaseline: vi.fn(), saveBaseline: vi.fn(),
}))
let root: HTMLElement, app: App
const saved = baselineFixture(1)
async function settle() { await new Promise(resolve => setTimeout(resolve, 0)); await nextTick() }
function button(text: string) { return [...root.querySelectorAll('button')].find(b => b.textContent?.includes(text))! }
async function startImport() {
  button('导入工程资料').click(); await nextTick()
  const input = root.querySelector<HTMLInputElement>('input[type=file]')!
  Object.defineProperty(input, 'files', { value: [new File(['<project/>'], 'pom.xml')] })
  input.dispatchEvent(new Event('change')); await nextTick()
  root.querySelector('form')!.dispatchEvent(new Event('submit', { cancelable: true })); await settle()
}
beforeEach(async () => {
  vi.resetAllMocks()
  vi.mocked(baselines).mockResolvedValue([])
  vi.mocked(previewBaseline).mockResolvedValue({ token: 'token', content: saved.content })
  root = document.createElement('div'); document.body.append(root)
  app = createApp(Panel, { systemId: 'orders' }); app.mount(root); await settle()
})
afterEach(() => { app.unmount(); root.remove() })
it('shows the parsed preview, retains inputs on an uncertain save and replays the same request', async () => {
  vi.mocked(saveBaseline).mockRejectedValueOnce(new Error('连接中断')).mockResolvedValueOnce(saved)
  await startImport()
  expect(root.textContent).toContain('未提供 Maven 解析记录')
  for (let i = 0; i < 2; i++) {
    root.querySelector('form')!.dispatchEvent(new Event('submit', { cancelable: true })); await settle()
    if (i === 0) expect(root.textContent).toContain('连接中断')
  }
  const payloads = vi.mocked(saveBaseline).mock.calls.map(call => call[1])
  expect(payloads).toHaveLength(2)
  expect(payloads[0]?.get('requestId')).toBeTruthy()
  expect(payloads[0]?.get('requestId')).toEqual(payloads[1]?.get('requestId'))
  expect(payloads[0]?.get('token')).toBe('token')
  expect(root.textContent).toContain('已保存工程基线 v1')
  vi.mocked(baselineDetail).mockResolvedValue(saved)
  button('v1 ·').click(); await settle()
  expect(baselineDetail).toHaveBeenCalledWith('orders', 'b1')
})
it('requires a fresh server preview after editing the user explanation', async () => {
  await startImport()
  const description = root.querySelector('textarea')!
  description.value = '范围改变'; description.dispatchEvent(new Event('input')); await nextTick()
  expect(root.textContent).not.toContain('保存为新基线版本')
  root.querySelector('form')!.dispatchEvent(new Event('submit', { cancelable: true })); await settle()
  expect(vi.mocked(previewBaseline).mock.calls.at(-1)?.[1].get('note')).toBe('范围改变')
  expect(saveBaseline).not.toHaveBeenCalled()
})

