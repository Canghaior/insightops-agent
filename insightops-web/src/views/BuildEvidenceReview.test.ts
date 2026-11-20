// @vitest-environment happy-dom
import { createApp, nextTick, type App } from 'vue'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { apiClient } from '@/api/client'
import type { BuildBatch, BuildRole, CaseOccurrence, CaseStatus } from '@/api/buildEvidence'
import type { BuildComparison, ComparisonCounts } from '@/api/buildComparison'
import type { Verification, VerificationInput } from '@/api/reportReview'
import ReportReviewPanel from '@/components/ReportReviewPanel.vue'

const originalAdapter = apiClient.defaults.adapter
const prefix = '/reports/decision/review'
const when = '2026-11-21T00:00:00Z'
const author = { id: 'person', name: '审阅者' }
const observed = '已上报的测试中，候选有一个执行报错。'
const counts: ComparisonCounts = { total: 3, passed: 3, failure: 0, error: 0, skipped: 0 }
const makeResult = (fileId: string, ordinal: number, status: CaseStatus): CaseOccurrence => ({
  fileId, ordinal, status, type: status === 'ERROR' ? 'DataException' : null,
  message: status === 'ERROR' ? '<b>原始文本</b>' : null, stack: status === 'ERROR' ? 'at ExampleTest.java:17' : null,
})
function batch(id: string, role: BuildRole): BuildBatch {
  return {
    summary: { id, systemId: 'system', reportId: 'decision', role, label: id, importedAt: when, counts: { passed: 3, failures: 0, errors: 0, skipped: 0 } },
    metadata: { label: id, environment: null, executedAt: null, executedBy: null, exitCode: null, sourceNote: null },
    commandRecord: { command: [], exitCode: null, startedAt: null, endedAt: null, selector: null, excludedClasses: [] },
    log: { outcome: null, counts: null }, excludedMethods: [], warnings: [],
    files: [{ id: id + '-xml', name: 'TEST-demo.xml', kind: 'SUREFIRE_XML', size: 100, sha256: 'a'.repeat(64) }], cases: [],
  }
}
function comparison(baselineId: string, candidateId: string): BuildComparison {
  const oldFile = baselineId + '-xml', newFile = candidateId + '-xml'
  return {
    baselineId, candidateId, scope: 'DIFFERENT_OBSERVED', baselineCounts: counts,
    candidateCounts: { ...counts, passed: 2, error: 1 }, objectiveSummary: observed, limitations: ['只核对此次上传的 XML。'],
    groups: [
      { suite: 'suite', className: 'Demo', name: 'same[param]', change: 'AMBIGUOUS',
        baseline: [makeResult(oldFile, 1, 'PASSED'), makeResult(oldFile, 2, 'PASSED')],
        candidate: [makeResult(newFile, 3, 'ERROR')], baselineCounts: { ...counts, total: 2, passed: 2 },
        candidateCounts: { ...counts, total: 1, passed: 0, error: 1 } },
      { suite: 'suite', className: 'Demo', name: 'oldOnly', change: 'BASELINE_ONLY', baseline: [makeResult(oldFile, 3, 'PASSED')],
        candidate: [], baselineCounts: { ...counts, total: 1, passed: 1 }, candidateCounts: { ...counts, total: 0, passed: 0 } },
      { suite: 'suite', className: 'Demo', name: 'newOnly', change: 'CANDIDATE_ONLY',
        baseline: [], candidate: [makeResult(newFile, 1, 'PASSED'), makeResult(newFile, 2, 'PASSED')],
        baselineCounts: { ...counts, total: 0, passed: 0 }, candidateCounts: { ...counts, total: 2, passed: 2 } },
    ],
  }
}
const historical: Verification = { id: 'v1', version: 1, previousVersionId: null, author, createdAt: when, fields: {
  question: '原判断', suggestionRef: '原报告第 3 项', origin: 'MODEL', status: 'EXECUTED',
  method: '原始运行方法', environment: '原运行环境', observation: '主人查询成功', impact: 'INCONCLUSIVE',
  limits: '未做单组件定位', blocker: '', uploadIds: ['sql'],
} }
let host: HTMLElement, app: App
let batches: BuildBatch[], saved: Verification[], requests: string[], imports: FormData[]
let loseImport: boolean, rejectedImport: string, compareResponse: (old: string, next: string) => Promise<BuildComparison>

beforeEach(() => {
  batches = [batch('old', 'BASELINE'), batch('next', 'CANDIDATE')]
  saved = [structuredClone(historical)]; requests = []; imports = []; loseImport = false; rejectedImport = ''
  compareResponse = async (old, next) => comparison(old, next)
  apiClient.defaults.adapter = async config => {
    const url = config.url!
    requests.push(config.method + ' ' + url)
    let data: unknown
    if (url === prefix) data = { context: { systemRevision: 1 }, verifications: structuredClone(saved), decisions: [] }
    else if (url === '/business-systems/available-uploads') data = [{ uploadId: 'sql', name: '正向 SQL.md' }]
    else if (url === prefix + '/builds' && config.method === 'get') data = batches.map(item => item.summary)
    else if (url === prefix + '/builds') {
      const metadata = JSON.parse(config.data.get('metadata') as string)
      imports.push(config.data)
      if (rejectedImport) throw Object.assign(new Error(), { response: { data: { detail: rejectedImport }, status: 400 } })
      let item = batches.find(x => x.summary.id === metadata.requestId)
      if (!item) { item = batch(metadata.requestId, metadata.role); item.summary.label = metadata.label; batches.push(item) }
      if (loseImport) { loseImport = false; throw new Error('simulated lost response after import') }
      data = item
    } else if (url.startsWith(prefix + '/builds/')) data = batches.find(x => x.summary.id === url.split('/').at(-1))
    else if (url === prefix + '/comparison') data = await compareResponse(config.params.baselineId, config.params.candidateId)
    else if (url === prefix + '/verifications') {
      const cmd = JSON.parse(config.data) as { previousVersionId: string | null; fields: VerificationInput }
      const item = { id: 'v2', version: cmd.previousVersionId ? 2 : 1, previousVersionId: cmd.previousVersionId, author, createdAt: when, fields: cmd.fields, buildSummary: observed }
      saved.push(item); data = item
    } else if (url.endsWith('/handoff.zip')) {
      throw Object.assign(new Error(), { response: { data: { detail: '本版附件暂不可取得' }, status: 409 } })
    } else throw new Error('Unexpected endpoint ' + url)
    return { config, data: { data }, headers: {}, status: 200, statusText: 'OK' }
  }
})
afterEach(() => { app?.unmount(); host?.remove(); apiClient.defaults.adapter = originalAdapter; vi.restoreAllMocks() })
const settle = async () => { await new Promise(resolve => setTimeout(resolve, 0)); await nextTick() }
async function open() {
  host = document.createElement('div'); document.body.appendChild(host)
  app = createApp(ReportReviewPanel, { reportId: 'decision' }); app.mount(host); await settle()
}
function button(text: string) {
  const found = [...host.querySelectorAll('button')].find(x => x.textContent?.trim() === text)
  if (!found) throw new Error('No button ' + text)
  return found
}
async function click(text: string) { button(text).click(); await settle() }
function input(label: string) {
  const found = [...host.querySelectorAll('label')].find(el => el.textContent?.trim().startsWith(label))
  if (!found) throw new Error('No label ' + label)
  return found.querySelector<HTMLInputElement | HTMLSelectElement | HTMLTextAreaElement>('input,select,textarea')!
}
async function fill(label: string, value: string) {
  const el = input(label)
  el.value = value
  el.dispatchEvent(new Event(el.tagName === 'SELECT' ? 'change' : 'input', { bubbles: true })); await settle()
}
async function performComparison() {
  await fill('基线构建批次', 'old'); await fill('候选构建批次', 'next'); await click('对照两次构建')
}
async function submit(selector: string) {
  host.querySelector(selector)!.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true })); await settle()
}
async function selectFiles(label: string, files: File[]) {
  const pick = input(label)
  Object.defineProperty(pick, 'files', { configurable: true, value: files })
  pick.dispatchEvent(new Event('change', { bubbles: true })); await settle()
}

describe('报告内工程证据', () => {
  it('uploads actual multipart fields and retries a lost import using the same request ID', async () => {
    await open(); await click('导入基线构建')
    await fill('构建标签或提交号', '历史构建')
    await selectFiles('Surefire XML 原件', [new File(['<testsuite/>'], 'TEST-one.xml'), new File(['<testsuite/>'], 'TEST-two.xml')])
    loseImport = true
    await submit('.build-import')
    expect(host.textContent).toContain('导入结果尚未确认')
    expect(input('构建标签或提交号').value).toBe('历史构建')
    await submit('.build-import')
    expect(imports).toHaveLength(2)
    const first = JSON.parse(imports[0]!.get('metadata') as string)
    expect(first).toMatchObject({ role: 'BASELINE', label: '历史构建', exitCode: null, executedAt: null })
    expect(first.requestId).toBeTruthy()
    expect(JSON.parse(imports[1]!.get('metadata') as string).requestId).toBe(first.requestId)
    expect(imports[0]!.getAll('xmlFiles').map(file => (file as File).name)).toEqual(['TEST-one.xml', 'TEST-two.xml'])
    expect(imports[0]!.has('mavenLog')).toBe(false)
    expect(batches).toHaveLength(3)
    expect(host.querySelector('.build-import')).toBeNull()
    expect(host.textContent).toContain('已保存并解析')
    expect(host.querySelector('.batch-detail')?.textContent).toContain('具体方法数量未知')
    expect(host.textContent).toContain('独立于研究检索')
  })

  it('shows the rejected material and allows replacement without losing source metadata or the manual draft', async () => {
    await open(); await click('记录验证'); await fill('待验证的问题', '已填写的问题')
    await click('导入候选构建'); await fill('构建标签或提交号', '候选提交')
    const meta = [...host.querySelectorAll('summary')].find(summary => summary.textContent?.includes('填写执行环境'))!
    meta.click(); await settle()
    await fill('原测试执行者', '原执行者')
    await fill('构建退出码', '1')
    await selectFiles('Surefire XML 原件', [new File(['partial'], 'TEST-broken.xml')])
    await selectFiles('Maven 构建日志', [new File(['sample'], 'test.log')])
    rejectedImport = 'TEST-broken.xml 的套件计数与已记录的方法不一致。'
    await submit('.build-import')
    expect(host.querySelector('.build-import [role=alert]')?.textContent).toBe(rejectedImport)
    expect(input('构建标签或提交号').value).toBe('候选提交')
    expect(input('待验证的问题').value).toBe('已填写的问题')
    expect(batches).toHaveLength(2)
    rejectedImport = ''
    await selectFiles('Surefire XML 原件', [new File(['replacement'], 'TEST-good.xml')])
    await submit('.build-import')
    const second = JSON.parse(imports[1]!.get('metadata') as string)
    expect(second).toMatchObject({ role: 'CANDIDATE', label: '候选提交', exitCode: 1, executedBy: '原执行者' })
    expect(second.requestId).not.toBe(JSON.parse(imports[0]!.get('metadata') as string).requestId)
    expect((imports[1]!.get('mavenLog') as File).name).toBe('test.log')
    expect(input('待验证的问题').value).toBe('已填写的问题')
    expect(host.querySelector('.comparison')).toBeNull()
  })

  it('shows unmatched and repeated identities, original exception text and distinct original locators', async () => {
    await open(); await performComparison()
    const view = host.querySelector('.comparison')!
    expect(view.textContent).toContain('测试集合或出现次数不同')
    expect(view.textContent).toContain('same[param]')
    expect(view.textContent).toContain('无法可靠地逐条配对')
    expect(view.textContent).toContain('本侧 XML 未出现，状态未知')
    expect(view.textContent).toContain('<b>原始文本</b>')
    expect(view.textContent).toContain('at ExampleTest.java:17')
    expect([...view.querySelectorAll('b')].some(el => el.textContent === '原始文本')).toBe(false)
    const links = [...view.querySelectorAll('a')]
    expect(links.some(a => a.getAttribute('href') === '/api/v1/reports/decision/review/builds/next/files/next-xml'
      && a.textContent?.includes('第 3 项'))).toBe(true)
    await fill('显示范围', 'CANDIDATE_ONLY')
    expect(view.textContent).toContain('newOnly'); expect(view.textContent).not.toContain('same[param]')
    expect(requests.some(r => r.includes('/decisions'))).toBe(false)
  })

  it('corrects the exact chosen version without losing its human text, impact or positive evidence', async () => {
    await open(); await performComparison()
    await fill('将构建对照用于', 'v1'); await click('用此对照补充已有验证')
    expect(input('实际观察与证据说明').value).toBe('主人查询成功')
    expect(input('适用范围').value).toBe('未做单组件定位')
    expect(input('对判断的影响').value).toBe('INCONCLUSIVE')
    expect(host.querySelector<HTMLInputElement>('.review-editor input[value=sql]')?.checked).toBe(true)
    expect(host.querySelector('.bound-builds')?.textContent).toContain(observed)
    await fill('实际执行者', '外部工程师'); await submit('.review-editor')
    expect(saved).toHaveLength(2)
    expect(saved[0]).toEqual(historical)
    expect(saved[1]).toMatchObject({ previousVersionId: 'v1', fields: { origin: 'MODEL', executedBy: '外部工程师',
      observation: '主人查询成功', uploadIds: ['sql'], baselineBuildId: 'old', candidateBuildId: 'next' } })
    expect(saved[1]!.fields).not.toHaveProperty('buildSummary')
    expect(host.querySelector('#verification-v2')?.textContent).toContain(observed)
    await click('刷新记录')
    await click('查看本版构建对照')
    expect(requests.filter(r => r.endsWith('/comparison'))).toHaveLength(2)
    await click('下载第 1 版验证交接包')
    expect(requests.at(-1)).toBe('get /reports/decision/review/verifications/v1/handoff.zip')
    expect(host.querySelector('#verification-v1')?.textContent).toContain('本版附件暂不可取得')
  })

  it('preserves an open manual draft, remains undecided, and discards superseded comparison responses', async () => {
    let finish!: (value: BuildComparison) => void
    compareResponse = () => new Promise(resolve => { finish = resolve })
    await open(); await click('记录验证')
    await fill('待验证的问题', '我的手工草稿')
    await performComparison()
    await fill('候选构建批次', '')
    finish(comparison('old', 'next')); await settle()
    expect(host.querySelector('.comparison')).toBeNull()
    expect(input('待验证的问题').value).toBe('我的手工草稿')
    compareResponse = async (a, b) => comparison(a, b)
    await performComparison(); await click('将此对照用于当前草稿')
    expect(input('待验证的问题').value).toBe('我的手工草稿')
    expect(input('执行状态').value).toBe('NOT_EXECUTED')
    expect(saved).toHaveLength(1)
  })
})
