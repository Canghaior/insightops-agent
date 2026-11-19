// @vitest-environment happy-dom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createApp, defineComponent, h, nextTick, type App as VueApp } from 'vue'
import { createMemoryHistory, createRouter, type Router } from 'vue-router'
import { createPinia } from 'pinia'
import { ElIcon } from 'element-plus'
import { AxiosError } from 'axios'
import App from '@/App.vue'
import { apiClient } from '@/api/client'
import SystemDecisionView from './SystemDecisionView.vue'

const original = apiClient.defaults.adapter
let revision: number, conflict: boolean, oldType: string, root: HTMLElement, app: VueApp, router: Router
let requests: Array<{ revision: number; body: Record<string, unknown> }>
function dossier(rev: number, id = 'orders') {
  return { id, revision: rev, background: { name: '订单系统', technologyStack: '修订-' + rev }, attachments: [] }
}
beforeEach(() => {
  revision = 2; conflict = false; oldType = 'SYSTEM_DECISION'; requests = []
  apiClient.defaults.adapter = async config => {
    const url = config.url
    let data: unknown
    if (url === '/projects' || url === '/business-systems/available-uploads') data = []
    else if (url === '/business-systems/orders' || url === '/business-systems/other') data = dossier(revision, url.split('/').pop())
    else if (url === '/agent-workflows/runs/R1') data = { businessSystemId: 'orders', inputs: {
      assessmentType: oldType, systemSnapshot: dossier(1), decisionQuestion: '旧问题', context: { environment: '旧环境' },
      projectId: 'unwatched', uploadIds: ['E'], selectedMaterials: [{ uploadId: 'E', name: '回调记录.md' }] } }
    else if (url?.endsWith('/decision-runs')) {
      requests.push({ revision: config.params.expectedRevision, body: JSON.parse(config.data) })
      if (conflict) throw new AxiosError('冲突', undefined, config, undefined,
        { data: {}, status: 409, statusText: '', headers: {}, config })
      data = { runId: 'new' }
    } else throw new Error(String(url))
    return { data: { data }, status: 200, statusText: '', headers: {}, config }
  }
})
afterEach(() => { app?.unmount(); root?.remove(); apiClient.defaults.adapter = original })
async function open(query = '') {
  const empty = defineComponent(() => () => h('div'))
  router = createRouter({ history: createMemoryHistory(), routes: [
    { path: '/business-systems/:id/decision-assessment', name: 'system-decision', component: SystemDecisionView },
    { path: '/business-systems/:id', name: 'business-system', component: empty }, { path: '/:pathMatch(.*)*', component: empty },
  ] })
  await router.push('/business-systems/orders/decision-assessment' + query)
  root = document.createElement('div'); document.body.appendChild(root)
  app = createApp(App); app.component('ElIcon', ElIcon); app.use(createPinia()).use(router).mount(root)
  await vi.waitFor(() => expect(root.textContent).not.toContain('正在读取评估输入'))
}
async function submit() {
  root.querySelector('form')!.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }))
  await new Promise(resolve => setTimeout(resolve, 0)); await nextTick()
}
async function edit(index: number, value: string) {
  const field = root.querySelectorAll('textarea')[index]!
  field.value = value; field.dispatchEvent(new Event('input', { bubbles: true })); await nextTick()
}
const box = () => root.querySelector<HTMLInputElement>('.review input')!
const reload = () => root.querySelector<HTMLButtonElement>('.panel .text-button')!.click()

describe('业务系统决策评估页面', () => {
  it('requires a question, launches without versions, a framework or files', async () => {
    await open()
    expect(root.textContent).toContain('没有外部资料依据')
    await submit(); expect(requests).toHaveLength(0)
    await edit(0, '如何诊断回调延迟？'); await submit()
    expect(requests).toHaveLength(1)
    expect(requests[0]).toMatchObject({ revision: 2, body: { question: '如何诊断回调延迟？', projectId: null, uploadIds: [] } })
    expect(requests[0]!.body).not.toHaveProperty('targetVersion')
    expect(router.currentRoute.value.path).toBe('/runs/new')
  })
  it('keeps missing material notices, refreshes the dossier and sends only reviewed inputs', async () => {
    await open('?runId=R1')
    expect(root.textContent).toContain('回调记录.md')
    expect(root.textContent).toContain('原评估的框架已不可用')
    expect(root.textContent).toContain('上次使用档案修订 1，本次使用修订 2')
    await submit(); expect(requests).toHaveLength(0)
    box().click(); await nextTick(); await edit(2, '当前环境')
    expect(box().checked).toBe(false)
    box().click(); revision = 3; reload()
    await vi.waitFor(() => expect(root.textContent).toContain('本次使用修订 3'))
    expect(box().checked).toBe(false); expect(root.textContent).toContain('回调记录.md')
    await submit(); expect(requests).toHaveLength(0)
    box().click(); await nextTick(); await submit()
    expect(requests[0]).toMatchObject({ revision: 3, body: { environment: '当前环境', uploadIds: [], projectId: null } })
  })
  it('requires a fresh confirmation after a conflict and resets warnings after switching systems', async () => {
    await open('?runId=R1')
    conflict = true; box().click(); await nextTick(); await submit()
    expect(requests).toHaveLength(1); expect(box().checked).toBe(false)
    await submit(); expect(requests).toHaveLength(1)
    conflict = false; revision = 3; reload()
    await vi.waitFor(() => expect(root.textContent).toContain('本次使用修订 3'))
    await submit(); expect(requests).toHaveLength(1)
    await router.push('/business-systems/other/decision-assessment')
    await vi.waitFor(() => expect(root.querySelector('textarea')).not.toBeNull())
    expect(root.textContent).not.toContain('回调记录.md')
    expect(box()).toBeNull()
  })
  it('rejects an upgrade run opened in the decision form', async () => {
    oldType = 'UPGRADE_ASSESSMENT'; await open('?runId=R1')
    expect(root.textContent).toContain('历史运行与本次系统决策评估不一致')
    expect(root.querySelector('form')).toBeNull()
  })
})
