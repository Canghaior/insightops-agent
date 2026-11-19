// @vitest-environment happy-dom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createApp, defineComponent, h, nextTick, type App as VueApp } from 'vue'
import { createPinia } from 'pinia'
import { ElIcon } from 'element-plus'
import { createMemoryHistory, createRouter, type Router } from 'vue-router'
import { AxiosError } from 'axios'

import App from '@/App.vue'
import UpgradeAssessmentView from './UpgradeAssessmentView.vue'
import { apiClient } from '@/api/client'
import type { BusinessSystem } from '@/api/businessSystems'
import type { UpgradeInput } from '@/api/upgradeAssessments'

const originalAdapter = apiClient.defaults.adapter
const base = '/business-systems/orders'
const extra = 'private-extra-id'
const project = { id: 'spring', owner: 'spring-projects', name: 'spring-ai' }
const java = { id: 'java_backend', label: 'Java 后端', fields: [
  { key: 'javaVersion', label: 'Java 版本', maxLength: 80 },
  { key: 'springBootVersion', label: 'Spring Boot 版本', maxLength: 80 },
], concerns: ['依赖兼容性', '回归测试'] }
const profiles = [java, { id: 'general', label: '通用研究', fields: [], concerns: ['回归测试'] }]
function dossier(revision: number, id = 'orders'): BusinessSystem {
  return { id, revision, background: { name: '订单系统', purpose: '', keyProcesses: '',
    technologyStack: revision === 1 ? 'Java 17 / Spring Boot 3.2' : 'Java 21 / Spring Boot 3.4',
    currentProblems: '', constraints: '' }, attachments: [{ uploadId: 'A', name: '档案资料 A' }] }
}
function storedInputs(revision: number, changes: Record<string, unknown> = {}) {
  return { projectId: 'spring', currentVersion: '1.0.0', targetVersion: '1.1.0', profileId: 'java_backend',
    concerns: ['回归测试'], context: { javaVersion: '17', springBootVersion: '3.2', notes: '旧补充说明' },
    uploadIds: ['A', extra, 'usable'], selectedMaterials: [{ uploadId: extra, name: '上次追加的说明.pdf' }],
    systemSnapshot: dossier(revision), ...changes }
}

let revision: number
let uploads: string[]
let inputs: Record<string, Record<string, unknown>>
let calls: Array<{ method?: string; url: string }>
let launches: Array<{ url: string; revision?: number; body: UpgradeInput }>
let conflict: boolean
let router: Router
let app: VueApp
let root: HTMLElement

beforeEach(() => {
  revision = 2
  uploads = ['A', 'usable']
  inputs = { R1: storedInputs(1), R2: storedInputs(2, { uploadIds: ['A'] }) }
  calls = []
  launches = []
  conflict = false
  apiClient.defaults.adapter = async config => {
    const url = config.url!
    calls.push({ method: config.method, url })
    let data: unknown
    if (url === '/upgrade-assessments/setup') {
      data = { projects: [project], profiles, sources: [], uploads: uploads.map(uploadId =>
        ({ uploadId, status: 'SUCCEEDED', originalName: uploadId, visibility: 'PRIVATE' })) }
    } else if (url === '/upgrade-assessments/presets') data = []
    else if (url === '/upgrade-assessments/versions') data = { releases: [], fetchedAt: '2026-11-19T00:00:00Z' }
    else if (url === base || url === '/business-systems/other') data = dossier(revision, url.split('/').pop())
    else if (url.startsWith('/agent-workflows/runs/')) {
      const runId = url.split('/').pop()!
      data = { runId, businessSystemId: runId === 'standalone' ? null : 'orders', inputs: inputs[runId] }
    } else if (url === '/upgrade-assessments/events/retired-event') {
      throw new AxiosError('Event no longer visible')
    } else if (url === base + '/upgrade-runs' || url === '/upgrade-assessments/runs') {
      launches.push({ url, revision: config.params?.expectedRevision, body: JSON.parse(config.data) })
      if (conflict) throw new AxiosError('档案已更新', undefined, config, undefined,
        { status: 409, statusText: 'Conflict', headers: {}, data: {}, config })
      data = { runId: 'created' }
    } else throw new Error('Unexpected API call: ' + url)
    return { data: { data }, status: 200, statusText: 'OK', headers: {}, config }
  }
})
afterEach(() => {
  app?.unmount()
  root?.remove()
  apiClient.defaults.adapter = originalAdapter
})

async function settle() { await new Promise(resolve => setTimeout(resolve, 0)); await nextTick() }
async function open(query = '?runId=R1') {
  const empty = defineComponent(() => () => h('div'))
  router = createRouter({ history: createMemoryHistory(), routes: [
    { path: '/upgrade-assessment', name: 'upgrade-assessment', component: UpgradeAssessmentView },
    { path: '/:pathMatch(.*)*', component: empty },
  ] })
  await router.push('/upgrade-assessment' + query)
  await router.isReady()
  root = document.createElement('div')
  document.body.appendChild(root)
  // Mount the actual router outlet as well: it owns the lifetime of each assessment's form.
  app = createApp(App)
  app.component('ElIcon', ElIcon)
  app.use(createPinia()).use(router).mount(root)
  await vi.waitFor(() => expect(root.querySelector('.upgrade-page form')).not.toBeNull())
}
function button(name: string) {
  const found = [...root.querySelectorAll('button')].find(b => b.textContent.trim() === name)
  if (!found) throw new Error('Missing button ' + name)
  return found
}
function control(name: string) {
  const label = [...root.querySelectorAll('label')].find(l => l.textContent.trim().startsWith(name))
  const input = label?.control
  if (!input) throw new Error('Missing control ' + name)
  return input as HTMLInputElement
}
async function edit(name: string, value: string) {
  const input = control(name)
  input.value = value
  input.dispatchEvent(new Event('input', { bubbles: true }))
  await nextTick()
}
async function click(name: string) { button(name).click(); await settle() }
async function confirm() { control('已核对本次背景').click(); await nextTick() }
async function submit() {
  // Deliberately bypass HTML disabled buttons to exercise the submit handler's own guard.
  root.querySelector('form')!.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }))
  await settle()
}
function warning() { return root.querySelector('.recovery-warnings')?.textContent ?? '' }
const start = () => button('开始升级评估')

describe('repeat an upgrade assessment from its historical run', () => {
  it('keeps named and unnamed material gaps after dossier load, refresh and reload, without looking up hidden files', async () => {
    inputs.R1 = storedInputs(1, { uploadIds: ['A', extra, extra, 'unnamed-id', 'usable'] })
    await open()
    expect(root.querySelector('.linked-system')?.textContent).toContain('档案修订 2')
    for (const action of [null, '刷新资料状态', '载入最新档案', '刷新资料状态']) {
      if (action) await click(action)
      expect(warning()).toContain('2 份原选资料未纳入本次研究')
      expect(warning().match(/上次追加的说明\.pdf/g)).toHaveLength(1)
      expect(warning()).not.toContain('unnamed-id')
    }
    expect(calls.some(c => c.url.includes(extra) || c.url.includes('unnamed-id'))).toBe(false)
    await confirm()
    await submit()
    expect(launches[0]?.body.uploadIds).toEqual(['usable'])
  })

  it('detects a newly unavailable selection on refresh and clears its gap once explicitly reselected', async () => {
    uploads.push(extra)
    await open()
    expect(warning()).toBe('')
    uploads = ['A', 'usable']
    await click('刷新资料状态')
    expect(warning()).toContain('上次追加的说明.pdf')
    uploads.push(extra)
    await click('刷新资料状态')
    expect(warning()).toContain('1 份')
    expect(control(extra).checked).toBe(false)
    control(extra).click()
    await nextTick()
    expect(warning()).toBe('')
    control(extra).click()
    await nextTick()
    expect(warning()).toBe('')
  })

  it('blocks clicks and direct submits until the edited, visible supplement is confirmed', async () => {
    await open()
    expect(root.querySelector('.review-note')?.textContent).toContain('上次评估使用档案修订 1，本次使用修订 2')
    expect(root.querySelector('.review-note')?.textContent).toContain('Java 21 / Spring Boot 3.4')
    expect(control('Java 版本').value).toBe('17')
    expect(control('Spring Boot 版本').value).toBe('3.2')
    expect(control('项目补充说明').value).toBe('旧补充说明')
    expect(start().disabled).toBe(true)
    start().click()
    await submit()
    await edit('Java 版本', '21')
    await edit('Spring Boot 版本', '3.4')
    await confirm()
    expect(start().disabled).toBe(false)
    await edit('项目补充说明', '本次核对后的环境')
    expect(control('已核对本次背景').checked).toBe(false)
    await submit()
    expect(launches).toHaveLength(0)
    await confirm()
    await submit()
    expect(launches).toHaveLength(1)
    expect(launches[0]).toMatchObject({ url: base + '/upgrade-runs', revision: 2, body: {
      projectId: 'spring', currentVersion: '1.0.0', targetVersion: '1.1.0', concerns: ['回归测试'],
      javaVersion: '21', springBootVersion: '3.4',
      context: { javaVersion: '21', springBootVersion: '3.4', notes: '本次核对后的环境' },
    } })
    expect(launches[0]?.body).not.toHaveProperty('systemSnapshot')
  })

  it('retains review on the same revision and invalidates it on another revision or a server conflict', async () => {
    await open()
    await edit('Java 版本', '21')
    await confirm()
    await click('刷新资料状态')
    await click('载入最新档案')
    expect(start().disabled).toBe(false)
    revision = 3
    await click('载入最新档案')
    expect(root.querySelector('.review-note')?.textContent).toContain('本次使用修订 3')
    expect(control('已核对本次背景').checked).toBe(false)
    await submit()
    expect(launches).toHaveLength(0)
    await confirm()
    conflict = true
    await submit()
    expect(launches).toHaveLength(1)
    expect(control('已核对本次背景').checked).toBe(false)
    await submit()
    expect(launches).toHaveLength(1)
    revision = 4
    conflict = false
    await click('载入最新档案')
    expect(warning()).toContain('上次追加的说明.pdf')
    expect(control('Java 版本').value).toBe('21')
    expect(start().disabled).toBe(true)
    await confirm()
    await submit()
    expect(launches.map(call => call.revision)).toEqual([3, 4])
  })

  it('starts fresh for other runs and dossiers, and permits same-revision and standalone launches', async () => {
    await open()
    await confirm()
    expect(warning()).toContain('上次追加的说明.pdf')
    inputs.R3 = storedInputs(1, { uploadIds: [] })
    await router.push('/upgrade-assessment?runId=R3')
    await settle()
    expect(control('已核对本次背景').checked).toBe(false)
    expect(warning()).toBe('')
    await router.push('/upgrade-assessment?runId=R2')
    await settle()
    expect(root.querySelector('.review-confirm')).toBeNull()
    expect(warning()).toBe('')
    expect(start().disabled).toBe(false)
    await submit()
    expect(launches[0]?.revision).toBe(2)
    await router.push('/upgrade-assessment?runId=R1')
    await settle()
    expect(control('已核对本次背景').checked).toBe(false)
    await router.push('/upgrade-assessment?systemId=other')
    await settle()
    expect(root.querySelector('.review-confirm')).toBeNull()
    expect(warning()).toBe('')
    await router.push('/upgrade-assessment')
    await settle()
    expect(root.querySelector('.linked-system')).toBeNull()
    expect(control('Java 版本').value).toBe('')
    await edit('当前版本', '1.0.0')
    await edit('目标版本', '1.1.0')
    await submit()
    expect(launches[1]).toMatchObject({ url: '/upgrade-assessments/runs', body: { context: {} } })
  })

  it('keeps independent project, profile and file warnings when history is incomplete', async () => {
    inputs.R1 = storedInputs(1, { projectId: 'no-longer-watched', profileId: 'retired', eventId: 'retired-event', selectedMaterials: undefined })
    await open()
    await click('刷新资料状态')
    expect(warning()).toContain('原研究方向已不可用')
    expect(warning()).toContain('原框架已不在关注列表')
    expect(warning()).toContain('原触发事件已不可访问')
    expect(warning()).toContain('1 份原选资料未纳入本次研究')
    expect(warning()).not.toContain(extra)
  })

  it('restores a standalone historical run with legacy Java fields and no dossier review', async () => {
    inputs.standalone = storedInputs(1, { systemSnapshot: undefined, context: {}, javaVersion: '11',
      springBootVersion: '2.7', uploadIds: [] })
    await open('?runId=standalone')
    expect(root.querySelector('.review-confirm')).toBeNull()
    expect(root.querySelector('.linked-system')).toBeNull()
    expect(control('Java 版本').value).toBe('11')
    await submit()
    expect(launches[0]).toMatchObject({ url: '/upgrade-assessments/runs', body: {
      context: { javaVersion: '11', springBootVersion: '2.7' },
    } })
  })
})
