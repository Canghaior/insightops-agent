// @vitest-environment happy-dom
import { createApp, defineComponent, h, nextTick, type App } from 'vue'
import { createMemoryHistory, createRouter, RouterView } from 'vue-router'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { apiClient } from '@/api/client'
import type { SystemAttachment } from '@/api/businessSystems'
import type { Verification, HumanDecision, VerificationInput, DecisionInput } from '@/api/reportReview'
import ReportsView from './ReportsView.vue'

const original = apiClient.defaults.adapter
let host: HTMLElement, app: App
let records: Verification[], decisions: HumanDecision[], reviewReads: string[], requests: string[]
let loseResponse: boolean, loseBeforeWrite: boolean
const font = { uploadId: 'file-1', name: '字体记录.md' }
const reader = { uploadId: 'file-2', name: '阅读器记录.md' }
let available: SystemAttachment[], loadAvailable: () => Promise<SystemAttachment[]>
const author = { id: 'user', name: '验收记录者' }
const createdAt = '2026-11-20T08:00:00Z'
function report(id: string) {
  return { id, title: id, reportType: 'RESEARCH', createdAt, items: [], itemCount: 1, markdown: '# 原始报告',
    research: { runId: 'source', sources: [], inputs: { systemSnapshot: { id: 'system', revision: 1 },
      assessmentType: id === 'upgrade' ? 'UPGRADE_ASSESSMENT' : 'SYSTEM_DECISION' } } }
}
beforeEach(() => {
  records = []; decisions = []; reviewReads = []; requests = []; loseResponse = false; loseBeforeWrite = false
  available = [font, reader]
  loadAvailable = async () => available
  apiClient.defaults.adapter = async config => {
    const url = config.url
    let data: unknown
    if (url === '/reports') data = { items: [report('upgrade'), report('decision')] }
    else if (url === '/reports/archive') data = report('archive')
    else if (url === '/report-deliveries') data = { items: [] }
    else if (url === '/projects' || url === '/delivery-channels') data = []
    else if (url === '/business-systems/available-uploads') data = await loadAvailable()
    else if (url?.endsWith('/review')) {
      reviewReads.push(url)
      data = { context: { systemRevision: 1, requestedModel: null, responseModel: 'actual-model', modelProvider: 'provider' },
        verifications: structuredClone(records), decisions: structuredClone(decisions) }
    } else if (url?.endsWith('/review/verifications')) {
      const body = JSON.parse(config.data) as { requestId: string; previousVersionId: string | null; fields: VerificationInput }
      requests.push(body.requestId)
      const id = 'v-' + body.requestId
      const saved = records.find(x => x.id === id)
      if (loseBeforeWrite) { loseBeforeWrite = false; throw new Error('connection closed before write') }
      if (!saved && body.fields.uploadIds.some(id => !available.some(file => file.uploadId === id))) {
        throw Object.assign(new Error('资料不可关联'), { response: { status: 400, data: { detail: '资料不可关联' } } })
      }
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
const uploadBox = (id: string) => host.querySelector<HTMLInputElement>(`.review-editor input[value="${id}"]`)
const alertText = () => host.querySelector('.review-editor [role=alert]')?.textContent ?? ''

async function draftWithFiles() {
  await open('decision'); button('记录验证').click(); await nextTick()
  await fill('待验证的问题', '保留问题'); await fill('原报告建议', '原建议第 3 项'); await fill('计划方法', '保留计划')
  uploadBox(font.uploadId)!.click(); await nextTick()
  uploadBox(reader.uploadId)!.click(); await nextTick()
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

  it('can remove a disappeared selection in the open draft and save its text and surviving evidence', async () => {
    await draftWithFiles()
    available = [reader]
    button('刷新记录').click(); await settle()
    expect(uploadBox(font.uploadId)).toBeNull()
    expect(uploadBox(reader.uploadId)?.checked).toBe(true)
    expect(alertText()).toContain(font.name)
    await submit()
    expect(requests).toHaveLength(0)
    button('移除失效资料').click(); await nextTick()
    expect(uploadBox(reader.uploadId)?.checked).toBe(true)
    await submit(); button('刷新记录').click(); await settle()
    expect(records).toHaveLength(1)
    expect(records[0]!.fields).toMatchObject({ question: '保留问题', suggestionRef: '原建议第 3 项', method: '保留计划', uploadIds: [reader.uploadId] })
    expect(host.querySelector('.review-entry')?.textContent).toContain('保留计划')
  })

  it('recovers a correction after an initial list failure and an authoritative empty response', async () => {
    const original: Verification = { id: 'old', version: 1, previousVersionId: null, author, createdAt, fields: {
      question: '问题', suggestionRef: '建议', origin: 'MODEL', status: 'NOT_EXECUTED', method: '原计划',
      environment: '', observation: '', impact: 'INCONCLUSIVE', limits: '', blocker: '', uploadIds: [font.uploadId],
    } }
    records.push(original)
    loadAvailable = async () => { throw new Error('读取失败') }
    await open('decision'); button('补充或更正').click(); await nextTick()
    await fill('计划方法', '更正的计划')
    expect(button('重读资料')).toBeDefined()
    expect(button('移除失效资料')).toBeUndefined()
    available = []; loadAvailable = async () => available
    button('重读资料').click(); await settle()
    expect(alertText()).toContain('1 份')
    expect(alertText()).toContain('名称未知')
    expect(alertText()).not.toContain(font.uploadId)
    button('移除失效资料').click(); await nextTick(); await submit()
    expect(records[1]).toMatchObject({ previousVersionId: original.id, fields: { method: '更正的计划', uploadIds: [] } })
    expect(records[0]!.fields).toMatchObject({ method: '原计划', uploadIds: [font.uploadId] })
  })

  it('keeps selected files during a failed refresh and saves them if still available on the server', async () => {
    await draftWithFiles()
    loadAvailable = async () => { throw new Error('读取失败') }
    button('刷新记录').click(); await settle()
    expect(button('重读资料')).toBeDefined()
    expect(button('移除失效资料')).toBeUndefined()
    await submit()
    expect(records[0]!.fields).toMatchObject({ method: '保留计划', uploadIds: [font.uploadId, reader.uploadId] })
  })

  it('keeps the original selection when the file becomes available before removal', async () => {
    await draftWithFiles()
    available = [reader]
    button('刷新记录').click(); await settle()
    expect(alertText()).toContain(font.name)
    available = [font, reader]
    button('刷新记录').click(); await settle()
    expect(button('移除失效资料')).toBeUndefined()
    expect(uploadBox(font.uploadId)?.checked).toBe(true)
    await submit()
    expect(records[0]!.fields.uploadIds).toEqual([font.uploadId, reader.uploadId])
  })

  it('keeps the draft unconfirmed if a stale empty response arrives after the latest read failed', async () => {
    await draftWithFiles()
    let succeed!: (files: SystemAttachment[]) => void
    loadAvailable = () => new Promise(resolve => { succeed = resolve })
    button('刷新记录').click(); await settle()
    loadAvailable = async () => { throw new Error('最新请求失败') }
    button('刷新记录').click(); await settle()
    succeed([]); await settle()
    expect(button('重读资料')).toBeDefined()
    expect(button('移除失效资料')).toBeUndefined()
    await submit()
    expect(records[0]!.fields.uploadIds).toEqual([font.uploadId, reader.uploadId])
  })

  it.each(['success', 'failure'])('ignores a late %s from an older refresh', async late => {
    await draftWithFiles()
    let finish!: () => void
    loadAvailable = () => new Promise((resolve, reject) => {
      finish = () => late === 'success' ? resolve([font, reader]) : reject(new Error('旧响应失败'))
    })
    button('刷新记录').click(); await settle()
    available = [reader]; loadAvailable = async () => available
    button('刷新记录').click(); await settle()
    finish(); await settle()
    expect(uploadBox(font.uploadId)).toBeNull()
    expect(alertText()).toContain(font.name)
    expect(button('重读资料')).toBeUndefined()
    expect(uploadBox(reader.uploadId)?.checked).toBe(true)
  })

  it('can replay a lost save without changing its request when a file later disappears', async () => {
    await draftWithFiles()
    loseResponse = true; await submit()
    available = [reader]
    button('刷新记录').click(); await settle()
    expect(alertText()).toContain(font.name)
    expect(alertText()).toContain('核对原提交')
    expect(button('移除失效资料')).toBeUndefined()
    await submit()
    expect(requests).toHaveLength(2)
    expect(requests[1]).toBe(requests[0])
    expect(records).toHaveLength(1)
    expect(records[0]!.fields.uploadIds).toEqual([font.uploadId, reader.uploadId])
    expect(host.querySelector('.review-editor')).toBeNull()
  })

  it('allows removal when the replay confirms the dropped submission did not save', async () => {
    await draftWithFiles(); loseBeforeWrite = true; await submit()
    available = [reader]
    button('刷新记录').click(); await settle()
    expect(alertText()).toContain('核对原提交')
    await submit()
    expect(requests).toHaveLength(2)
    expect(requests[1]).toBe(requests[0])
    expect(records).toHaveLength(0)
    await submit()
    expect(requests).toHaveLength(2)
    button('移除失效资料').click(); await nextTick(); await submit()
    expect(records).toHaveLength(1)
    expect(requests[2]).not.toBe(requests[0])
    expect(records[0]!.fields).toMatchObject({ method: '保留计划', uploadIds: [reader.uploadId] })
  })
})
