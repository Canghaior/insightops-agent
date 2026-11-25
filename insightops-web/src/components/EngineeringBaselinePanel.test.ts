// @vitest-environment happy-dom
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { createApp, nextTick, type App } from 'vue'
import Panel from './EngineeringBaselinePanel.vue'
import { baselines, baselineDetail, packageBaseline, previewBaseline, saveBaseline } from '@/api/engineeringBaselines'
import { baselineFixture } from '@/utils/engineeringBaseline.fixture'

vi.mock('@/api/engineeringBaselines', async original => ({
  ...await original<typeof import('@/api/engineeringBaselines')>(),
  baselines: vi.fn(), baselineDetail: vi.fn(), packageBaseline: vi.fn(), previewBaseline: vi.fn(), saveBaseline: vi.fn(),
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
it('selects one local module, sends only confirmed originals, and previews the downloadable v1 ZIP', async () => {
  button('导入工程资料').click(); await nextTick()
  button('选择本机模块目录').click(); await nextTick()
  const paths = ['project/pom.xml', 'project/api/pom.xml', 'project/api/src/main/java/Total.java',
    'project/api/.env', 'project/api/target/surefire-reports/TEST-Total.xml']
  const directoryFiles = paths.map(path => {
    const file = new File([path], path.split('/').at(-1)!)
    Object.defineProperty(file, 'webkitRelativePath', { value: path }); return file
  })
  const folder = root.querySelector<HTMLInputElement>('input[webkitdirectory]')!
  Object.defineProperty(folder, 'files', { value: directoryFiles })
  folder.dispatchEvent(new Event('change')); await nextTick()
  const module = root.querySelector('select')!
  module.value = 'project/api'; module.dispatchEvent(new Event('change')); await nextTick()
  expect(root.textContent).toContain('1 份 Surefire XML')
  expect(root.textContent).not.toContain('.env')
  root.querySelector<HTMLInputElement>('input[type=checkbox]')!.click(); await nextTick()
  vi.mocked(packageBaseline).mockResolvedValue(new Blob(['zip bytes'], { type: 'application/zip' }))
  button('生成工程包并预览').click(); await settle()
  const sent = vi.mocked(packageBaseline).mock.calls[0]![1]
  expect((sent.getAll('files') as File[]).map(file => file.name).sort()).toEqual(['Total.java', 'pom.xml'])
  const selection = JSON.parse(await (sent.get('selection') as Blob).text())
  expect(selection.paths.sort()).toEqual(['sources/pom.xml', 'sources/src/main/java/Total.java'])
  expect((vi.mocked(previewBaseline).mock.calls[0]![1].get('file') as File).name).toBe('maven-baseline.zip')
  expect(button('下载可导入的工程包')).toBeDefined()
  root.querySelector<HTMLInputElement>('input[type=checkbox]')!.click(); await nextTick()
  expect(root.textContent).not.toContain('保存为新基线版本')
})
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

it('shows guidance for an unsupported file and allows replacing it with a named Maven XML', async () => {
  button('导入工程资料').click(); await nextTick()
  const input = root.querySelector<HTMLInputElement>('input[type=file]')!
  async function select(name: string) {
    Object.defineProperty(input, 'files', { configurable: true, value: [new File(['<project/>'], name)] })
    input.dispatchEvent(new Event('change')); await nextTick()
  }
  await select('project.txt')
  expect(root.querySelector('[role=alert]')?.textContent).toContain('POM XML')
  expect(button('预览工程资料').disabled).toBe(true)
  expect(previewBaseline).not.toHaveBeenCalled()
  await select('pom-before.xml')
  expect(root.querySelector('[role=alert]')).toBeNull()
  expect(button('预览工程资料').disabled).toBe(false)
  root.querySelector('form')!.dispatchEvent(new Event('submit', { cancelable: true })); await settle()
  expect((vi.mocked(previewBaseline).mock.calls.at(-1)?.[1].get('file') as File).name).toBe('pom-before.xml')
})

