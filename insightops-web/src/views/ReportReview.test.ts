// @vitest-environment happy-dom
import { createApp, defineComponent, h, nextTick, type App } from 'vue'
import { createMemoryHistory, createRouter, RouterView } from 'vue-router'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { apiClient } from '@/api/client'
import type { Verification, HumanDecision, VerificationInput, DecisionInput } from '@/api/reportReview'
import ReportsView from './ReportsView.vue'

const original = apiClient.defaults.adapter
let host: HTMLElement, app: App
let records: Verification[], decisions: HumanDecision[], reviewReads: string[], requests: string[]
let loseResponse: boolean
const author = { id: 'user', name: '验收记录者' }
const createdAt = '2026-11-20T08:00:00Z'
function report(id: string) {
  return { id, title: id, reportType: 'RESEARCH', createdAt, items: [], itemCount: 1, markdown: '# 原始报告',
    research: { runId: 'source', sources: [], inputs: { systemSnapshot: { id: 'system', revision: 1 },
      assessmentType: id === 'upgrade' ? 'UPGRADE_ASSESSMENT' : 'SYSTEM_DECISION' } } }
}
beforeEach(() => {
  records = []; decisions = []; reviewReads = []; requests = []; loseResponse = false
  apiClient.defaults.adapter = async config => {
    const url = config.url
    let data: unknown
    if (url === '/reports') data = { items: [report('upgrade'), report('decision')] }
    else if (url === '/reports/archive') data = report('archive')
    else if (url === '/report-deliveries') data = { items: [] }
    else if (url === '/projects' || url === '/delivery-channels') data = []
    else if (url === '/business-systems/available-uploads') data = [{ uploadId: 'file-1', name: '字体记录.md' }]
    else if (url?.endsWith('/review')) {
      reviewReads.push(url)
      data = { context: { systemRevision: 1, requestedModel: null, responseModel: 'actual-model', modelProvider: 'provider' },
        verifications: structuredClone(records), decisions: structuredClone(decisions) }
    } else if (url?.endsWith('/review/verifications')) {
      const body = JSON.parse(config.data) as { requestId: string; previousVersionId: string | null; fields: VerificationInput }
      requests.push(body.requestId)
      const id = 'v-' + body.requestId
      const saved = records.find(x => x.id === id)
      if (!saved) records.push({ id, previousVersionId: body.previousVersionId, version: body.previousVersionId ? 2 : 1, fields: body.fields, createdAt, author })
      if (loseResponse) { loseResponse = false; throw new Error('lost response') }
      data = records.find(x => x.id === id)
    } else if (url?.endsWith('/review/decisions')) {
      const body = JSON.parse(config.data) as { fields: DecisionInput }
      data = { id: 'd1', fields: body.fields, createdAt, author }; decisions.push(data as HumanDecision)
    } else throw new Error('Unexpected endpoint ' + url)
    return { config, data: { data }, headers: {}, status: 200, statusText: 'OK' }
  }
})
afterEach(() => { app?.unmount(); host?.remove(); apiClient.defaults.adapter = original })
const settle = async () => { await new Promise(r => setTimeout(r, 0)); await nextTick() }
async function open(id: string) {
  const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/reports', component: ReportsView }] })
  await router.push({ path: '/reports', query: { reportId: id } })
  host = document.createElement('div'); document.body.appendChild(host)
  app = createApp(defineComponent(() => () => h(RouterView))); app.use(router); app.mount(host); await settle()
  return router
}
const button = (label: string) => [...host.querySelectorAll('button')].find(x => x.textContent?.trim() === label)!
async function fill(label: string, value: string) {
  const el = [...host.querySelectorAll('.review-editor label')].find(x => x.textContent?.trim().startsWith(label))!
    .querySelector<HTMLInputElement | HTMLSelectElement | HTMLTextAreaElement>('input,textarea,select')!
  el.value = value
  el.dispatchEvent(new Event(el.tagName === 'SELECT' ? 'change' : 'input', { bubbles: true })); await nextTick()
}
async function submit() {
  host.querySelector('.review-editor')!.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true })); await settle()
}
describe('报告验证与决定', () => {
  it('scopes the entry to a saved decision and restores even a report outside the first page', async () => {
    const router = await open('upgrade')
    expect(host.textContent).not.toContain('记录验证'); expect(reviewReads).toEqual([])
    await router.push('/reports?reportId=archive'); await settle()
    expect(reviewReads).toContain('/reports/archive/review')
    expect(host.textContent).toContain('请求模型 未知'); expect(host.textContent).toContain('响应模型 actual-model')
    button('记录验证').click(); await nextTick()
    await fill('待验证的问题', '档案之外的备注')
    await router.push('/reports?reportId=upgrade'); await settle()
    expect(host.querySelector('.review-editor')).toBeNull(); expect(host.textContent).toContain('原始报告')
  })

  it('replays a lost reply and keeps a decision attached to the exact older version after correction', async () => {
    await open('decision'); button('记录验证').click(); await nextTick()
    await fill('待验证的问题', '字体有效？'); await fill('原报告建议', '第三项')
    await fill('执行状态', 'BLOCKED'); await fill('受阻原因', '阅读器未知')
    expect(host.textContent).not.toContain('实际观察与证据说明')
    loseResponse = true; await submit(); await submit()
    expect(requests[0]).toEqual(requests[1]); expect(records).toHaveLength(1)
    expect(records[0]!.fields).toMatchObject({ observation: '', impact: 'INCONCLUSIVE' })
    button('记录人工决定').click(); await nextTick()
    await fill('当前选择', '暂缓升级'); await fill('决定理由', '需原阅读器'); await fill('尚未满足', '取得版本')
    host.querySelector<HTMLInputElement>('.review-choices input')!.click(); await submit()
    button('补充或更正').click(); await nextTick()
    await fill('执行状态', 'EXECUTED'); await fill('实际执行的方法', '同文导出'); await fill('实际执行环境', '本机')
    await fill('实际观察', '中点可见'); await fill('对判断的影响', 'SUPPORTS'); await fill('适用范围', '仅此阅读器')
    await submit()
    expect(records[1]).toMatchObject({ previousVersionId: records[0]!.id, version: 2, fields: { blocker: '', observation: '中点可见' } })
    expect(decisions[0]?.fields.verificationVersionIds).toEqual([records[0]!.id])
    const decisionCard = [...host.querySelectorAll('.review-entry')].find(x => x.textContent?.includes('暂缓升级'))!
    expect(decisionCard.querySelector('a')?.getAttribute('href')).toBe('#verification-' + records[0]!.id)
    expect(decisionCard.textContent).toContain('第 1 版 · 受阻')
  })
})
