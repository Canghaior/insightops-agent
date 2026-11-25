// @vitest-environment happy-dom
import { createApp, defineComponent, h, nextTick, type App } from 'vue'
import { createPinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { apiClient } from '@/api/client'
import InsightOps from '@/App.vue'
import ResearchResultActions from '@/components/ResearchResultActions.vue'
import KnowledgeFilesView from './KnowledgeFilesView.vue'
import ReportsView from './ReportsView.vue'

const original = apiClient.defaults.adapter
let host: HTMLElement, app: App
const when = '2026-11-21T00:00:00Z'
const report = {
  id: 'saved-upgrade', title: '订单系统版本升级评估', reportType: 'RESEARCH', createdAt: when,
  markdown: '# 已保存的原研究', periodStart: when, periodEnd: when, items: [], itemCount: 1,
  research: { runId: 'source', sources: [], inputs: { assessmentType: 'UPGRADE_ASSESSMENT', systemSnapshot: { id: 'system', revision: 2 } } },
}
const uploads = ['SUCCEEDED', 'PENDING', 'FAILED'].map((status, index) => ({
  uploadId: String(index), originalName: '资料' + index + '.md', uploadedBy: 'user', uploaderName: '记录者',
  status, byteSize: 200, pageCount: 1, sha256: 'a'.repeat(64), updatedAt: when, visibility: 'PRIVATE',
  errorMessage: status === 'FAILED' ? '原件格式无效' : null,
}))
let readUploads: () => Promise<typeof uploads>
let listedReports: unknown[]
let verificationVersions: unknown[]
beforeEach(() => {
  readUploads = async () => uploads
  listedReports = [report]
  verificationVersions = []
  apiClient.defaults.adapter = async config => {
    let data: unknown
    switch (config.url) {
      case '/runs/source/report': data = report; break
      case '/reports': data = { items: listedReports }; break
      case '/reports/amount-rule/review/eligibility':
      case '/reports/saved-upgrade/review/eligibility': data = { eligible: true }; break
      case '/reports/amount-rule/tasks':
      case '/reports/saved-upgrade/tasks': data = { versions: [], results: [] }; break
      case '/reports/amount-rule/review': data = { context: { systemRevision: 1 }, verifications: verificationVersions, decisions: [] }; break
      case '/reports/saved-upgrade/review': data = { context: { systemRevision: 2 }, verifications: [], decisions: [] }; break
      case '/report-deliveries': data = { items: [] }; break
      case '/knowledge/uploads': data = await readUploads(); break
      case '/delivery-channels':
      case '/projects':
      case '/business-systems/available-uploads':
      case '/reports/amount-rule/review/builds':
      case '/reports/saved-upgrade/review/builds': data = []; break
      default: throw new Error('Unexpected navigation request ' + config.url)
    }
    return { config, data: { data }, headers: {}, status: 200, statusText: 'OK' }
  }
})
afterEach(() => { app?.unmount(); host?.remove(); apiClient.defaults.adapter = original })
const settle = async () => { await new Promise(resolve => setTimeout(resolve, 0)); await nextTick() }

async function open(path: string) {
  const router = createRouter({ history: createMemoryHistory(), routes: [
    { path: '/runs/source', component: defineComponent(() => () => h(ResearchResultActions, { runId: 'source', canSave: true })) },
    { path: '/reports', component: ReportsView }, { path: '/knowledge-files', component: KnowledgeFilesView },
    { path: '/:pathMatch(.*)*', component: defineComponent(() => () => null) },
  ] })
  await router.push(path)
  host = document.createElement('div'); document.body.appendChild(host)
  app = createApp(InsightOps)
  app.component('ElIcon', { template: '<i><slot /></i>' })
  app.use(createPinia()).use(router).mount(host); await settle()
  return router
}

describe('报告与构建证据导航', () => {
  it('opens the exact report saved from a run and keeps the period and webhook tools accessible', async () => {
    const router = await open('/runs/source')
    expect(host.querySelector('.topbar h1')?.textContent).toBe('业务系统升级与改造工作台')
    expect(host.querySelector('.navigation a[aria-label="报告与验证"]')?.getAttribute('href')).toBe('/reports')
    const save = [...host.querySelectorAll('button')].find(el => el.textContent === '保存为研究报告')!
    save.click(); await settle()
    const link = [...host.querySelectorAll('a')].find(el => el.textContent === '打开这份报告')!
    expect(link.getAttribute('href')).toBe('/reports?reportId=saved-upgrade')
    link.click(); await settle()
    expect(router.currentRoute.value.query.reportId).toBe('saved-upgrade')
    expect(host.querySelector('.report-detail h3')?.textContent).toBe(report.title)
    expect(host.querySelector('.build-panel')?.textContent).toContain('独立于研究检索')
    const tools = host.querySelector<HTMLDetailsElement>('.report-tools')!
    expect(tools.querySelector('summary')?.textContent).toContain('周期报告')
    expect(tools.querySelectorAll('form')).toHaveLength(2)
  })

  it('shows engineering, external sources and execution for new reports and preserves the legacy card', async () => {
    const savedQuality = { status: 'INSUFFICIENT_EVIDENCE', label: '证据不足', summary: '原报告保存的说明',
      reasons: [{ code: 'ENGINEERING_BASELINE_ONLY', message: '原报告保存的原因' }] }
    const baseline = { label: '已关联工程基线 v1', summary: '可核对原件', reasons: [], missing: false }
    const coverage = {
      engineering: baseline,
      external: { label: '本次未选用', summary: '本次使用工程基线', reasons: [], missing: false },
      validation: { label: '评估时未执行', summary: '后续可核对实测', reasons: [], missing: false },
    }
    const targetGap = { label: '缺少依据', summary: 'Vue · 3.4.38 → 3.5.1', missing: true,
      reasons: [{ code: 'SOURCE_WARNING', message: '另外还有版本检查说明。' },
        { code: 'TARGET_VERSION_EVIDENCE_MISSING', message: '缺少 Vue 目标版本 3.5.1 的可核对来源。' }] }
    listedReports = [
      { ...report, id: 'amount-rule', title: '金额规则', research: { ...report.research,
        evidenceQuality: { status: 'REQUIRES_VALIDATION', label: '需要进一步核实', coverage } } },
      { ...report, research: { ...report.research, evidenceQuality: { ...savedQuality, coverage: { ...coverage, external: targetGap } } } },
      { ...report, id: 'legacy', research: { ...report.research, inputs: {}, evidenceQuality: savedQuality } },
    ]
    const version = { id: 'old-verification', previousVersionId: null, version: 1, createdAt: when, author: { name: '验收人' },
      fields: { question: '检查零金额', status: 'NOT_EXECUTED', uploadIds: [] } }
    verificationVersions = [version, { ...version, id: 'new-verification', previousVersionId: version.id, version: 2,
      fields: { ...version.fields, status: 'EXECUTED' } }]

    const router = await open('/reports?reportId=amount-rule')
    const detail = () => host.querySelector('.report-detail')!
    const section = (title: string) => detail().querySelector(`[aria-label="${title}"]`)!
    expect(section('工程依据').textContent).toContain('已关联工程基线 v1')
    expect(section('外部资料').textContent).toContain('本次未选用')
    expect(section('外部资料').classList.contains('coverage-missing')).toBe(false)
    expect(section('实际验证').textContent).toContain('评估时未执行')
    expect(section('实际验证').textContent).toContain('后续关联 1 条验证记录，其中 1 条记录为已执行')
    expect(section('实际验证').querySelector('a')?.getAttribute('href')).toBe('#report-verification-records')

    await router.push('/reports?reportId=saved-upgrade'); await settle()
    expect(section('外部资料').querySelector('li')?.textContent).toContain('缺少 Vue 目标版本 3.5.1 的可核对来源')
    expect(section('外部资料').querySelector('details:not([open])')?.textContent).toContain('另外还有版本检查说明')
    expect(section('实际验证').textContent).toContain('当前尚无后续验证记录')
    expect(section('实际验证').textContent).not.toContain('1 条记录为已执行')

    await router.push('/reports?reportId=legacy'); await settle()
    expect(detail().querySelector('.evidence-coverage')).toBeNull()
    expect(detail().querySelector('.evidence-quality')?.textContent).toContain('证据不足')
    expect(detail().querySelector('.evidence-quality p')?.textContent).toBe('原报告保存的说明')
    expect(detail().querySelector('.evidence-quality li')?.textContent).toBe('原报告保存的原因')
    expect(detail().querySelector('details .markdown')?.textContent ?? detail().textContent).toContain('已保存的原研究')
  })

  it('separates saved, processed and failed knowledge material from evidence for an assessment', async () => {
    await open('/knowledge-files')
    const cards = [...host.querySelectorAll('.upload-card')]
    expect(cards.map(card => card.querySelector('.status-pill')?.textContent)).toEqual(['已处理', '已保存，等待处理', '处理失败'])
    expect(cards[2]!.querySelector('.stream-error')?.textContent).toBe('原件格式无效')
    expect(host.querySelector('.upload-summary')?.textContent).not.toContain('可检索')
    expect(host.querySelector('.section-heading')?.textContent).toContain('实际引用')
    const link = host.querySelector<HTMLAnchorElement>('.upload-form a')!
    expect(link.getAttribute('href')).toBe('/reports')
  })

  it('keeps an unavailable knowledge list distinct from a successful empty response', async () => {
    readUploads = async () => { throw Object.assign(new Error(), { isAxiosError: true, response: { data: { detail: '资料列表暂不可读' } } }) }
    await open('/knowledge-files')
    expect(host.querySelector('[role=alert]')?.textContent).toContain('资料列表暂不可读')
    expect([...host.querySelectorAll('.upload-summary strong')].map(el => el.textContent)).toEqual(['—', '—', '—'])
    expect(host.querySelector('.upload-grid .empty-state')).toBeNull()
    readUploads = async () => []
    const refresh = [...host.querySelectorAll('button')].find(el => el.textContent === '刷新状态')!
    refresh.click(); await settle()
    expect(host.querySelector('[role=alert]')).toBeNull()
    expect(host.querySelector('.upload-grid .empty-state')?.textContent).toContain('暂无可见的知识资料')
    expect(host.querySelector('.upload-summary strong')?.textContent).toBe('0')
  })
})
