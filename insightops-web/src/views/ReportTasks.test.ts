// @vitest-environment happy-dom
import { createApp, nextTick, type App } from 'vue'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { apiClient } from '@/api/client'
import type { Verification } from '@/api/reportReview'
import type { TaskFields, TaskResult, TaskResultFields, TaskVersion } from '@/api/reportTasks'
import type { SystemAttachment } from '@/api/businessSystems'
import type { BuildSummary } from '@/api/buildEvidence'
import { taskDraft } from '@/utils/taskFields'
import ReportReviewPanel from '@/components/ReportReviewPanel.vue'

const originalAdapter = apiClient.defaults.adapter
const report = 'report-A', taskPath = '/reports/' + report + '/tasks'
const actor = { id: 'writer', name: '记录人' }, time = '2026-11-21T10:30:15.123Z'
const answer = '先核对两个方法的执行条件。\n记录实际的错误。', markdown = '# 报告\n\n' + answer + '\n\n限制待定。'
const evidence = { uploadId: 'keep', name: '原始日志.md' }, removed = { uploadId: 'gone', name: '临时材料.md' }
type TaskCommand = { requestId: string; previousVersionId: string | null; fields: TaskFields }
type ResultCommand = TaskResultFields & { requestId: string; previousVerificationVersionId: string | null }
let app: App, host: HTMLElement, versions: TaskVersion[], results: TaskResult[], verifications: Verification[]
let taskPosts: TaskCommand[], resultPosts: { command: ResultCommand; path: string; patch: File | null }[], paths: string[]
let visible: SystemAttachment[], uploadsFail: boolean, loseResult: boolean, loseTask: boolean, taskConflict: boolean
const build = (id: string, role: BuildSummary['role']) => ({ id, role, label: role === 'BASELINE' ? '基线批 A' : '候选批 B',
  systemId: 'system', reportId: report, importedAt: time, counts: { passed: 2, failures: 0, errors: 0, skipped: 0 } })
const initial = (id = 'v1', version = 1, fields: Partial<TaskFields> = {}): TaskVersion => ({
  id, taskId: 'task-1', version, previousVersionId: version === 1 ? null : 'v' + (version - 1), fields: {
    ...taskDraft(), question: '这两项错误能否复现？', excerpt: '先核对两个方法的执行条件。', method: '只运行两个方法',
    missingMaterials: '执行者提供 JDK 记录', ...fields },
  reportId: report, runId: 'run', systemId: 'system', systemRevision: 1, engineeringBaselineId: null,
  engineeringBaselineVersion: null, excerptLine: 3, author: actor, createdAt: time,
})
beforeEach(() => {
  versions = []; results = []; verifications = []; paths = []; taskPosts = []; resultPosts = []
  visible = [evidence, removed]; uploadsFail = false; loseResult = false; loseTask = false; taskConflict = false
  apiClient.defaults.adapter = async config => {
    const path = config.url!
    paths.push(config.method + ' ' + path)
    let data: unknown
    if (path === '/business-systems/available-uploads') {
      if (uploadsFail) throw new Error('fixture: upload list unavailable')
      data = visible
    } else if (path === taskPath && config.method === 'get') data = { versions, results }
    else if (path === '/reports/' + report + '/review/builds') data = [build('baseline', 'BASELINE'), build('candidate', 'CANDIDATE')]
    else if (path === '/reports/' + report + '/review') data = { context: {
      reportId: report, runId: 'run', systemId: 'system', systemRevision: 1 }, verifications, decisions: [] }
    else if (path === taskPath && config.method === 'post') {
      const cmd = JSON.parse(config.data) as TaskCommand
      taskPosts.push(cmd)
      if (taskConflict) {
        taskConflict = false
        versions.push(initial('v2', 2, { facts: '并发补充的测试来源', question: '原问题已更新' }))
        throw Object.assign(new Error(), { response: { status: 409, data: { detail: '该版本已有更正' } } })
      }
      let v = versions.find(t => t.id === cmd.requestId)
      if (!v) {
        const previous = versions.find(x => x.id === cmd.previousVersionId)
        v = { ...initial(cmd.requestId, (previous?.version ?? 0) + 1, cmd.fields),
          taskId: previous?.taskId ?? cmd.requestId, previousVersionId: previous?.id ?? null }
        versions.push(v)
      }
      if (loseTask) { loseTask = false; throw new Error('fixture: response lost after write') }
      data = v
    } else if (path.startsWith(taskPath + '/versions/') && path.endsWith('/results')) {
      const form = config.data as FormData
      const cmd = JSON.parse(String(form.get('command'))) as ResultCommand
      const patch = form.get('patch') as File | null
      resultPosts.push({ path, command: cmd, patch })
      const task = versions.find(v => v.id === path.split('/').at(-2))!
      let result = results.find(r => r.verificationVersionId === cmd.requestId)
      if (!result) {
        const previous = verifications.find(v => v.id === cmd.previousVerificationVersionId)
        const info = results.find(r => r.verificationVersionId === previous?.id)?.patch
        result = { verificationVersionId: cmd.requestId, taskVersionId: task.id, executedAt: cmd.executedAt,
          sourceRevision: cmd.sourceRevision, candidateRevision: cmd.candidateRevision, unfinished: cmd.unfinished,
          recordedBy: actor, createdAt: time, patch: patch ? { name: patch.name, size: patch.size, sha256: 'a'.repeat(64) }
            : cmd.retainPreviousPatch ? info ?? null : null }
        results.push(result)
        verifications.push({ id: result.verificationVersionId, author: actor, createdAt: time,
          previousVersionId: previous?.id ?? null, version: (previous?.version ?? 0) + 1,
          fields: { ...cmd, question: task.fields.question, suggestionRef: task.fields.excerpt, origin: task.fields.origin } })
      }
      if (loseResult) { loseResult = false; throw new Error('fixture: response lost after write') }
      data = result
    } else if (path.endsWith('.zip')) {
      throw Object.assign(new Error(), { response: { status: 409, data: { detail: '归档原件校验失败，未生成交接包' } } })
    } else throw new Error('Unexpected endpoint ' + path)
    return { config, data: { data: structuredClone(data) }, headers: {}, status: 200, statusText: 'OK' }
  }
})
afterEach(() => { app?.unmount(); host?.remove(); apiClient.defaults.adapter = originalAdapter; vi.restoreAllMocks() })
const settle = async () => { await new Promise(r => setTimeout(r, 0)); await nextTick() }
async function open() {
  host = document.createElement('div'); document.body.appendChild(host)
  app = createApp(ReportReviewPanel, { reportId: report, originalAnswer: answer, markdown, reportTitle: '两个 JPA 方法' })
  app.mount(host); await settle(); await settle()
}
function button(name: string, where: ParentNode = host) {
  const b = [...where.querySelectorAll('button')].find(el => el.textContent?.trim() === name)
  if (!b) throw new Error('Missing button: ' + name)
  return b
}
const click = async (name: string) => { button(name).click(); await settle() }
const taskRegion = () => host.querySelector<HTMLElement>('[aria-label="单项任务"]')!
const form = () => taskRegion().querySelector<HTMLFormElement>('form')!
function field(name: string) {
  const label = [...taskRegion().querySelectorAll('label')].find(el => el.firstChild?.textContent?.trim() === name)
  if (!label) throw new Error('Missing field: ' + name)
  return label.querySelector<HTMLInputElement | HTMLSelectElement | HTMLTextAreaElement>('input,textarea,select')!
}
async function fill(name: string, value: string) {
  const el = field(name); el.value = value
  el.dispatchEvent(new Event(el.tagName === 'SELECT' ? 'change' : 'input', { bubbles: true }))
  await nextTick()
}
async function submit() {
  form().dispatchEvent(new Event('submit', { bubbles: true, cancelable: true })); await settle(); await settle()
}
const check = async (id: string) => { form().querySelector<HTMLInputElement>('input[value="' + id + '"]')!.click(); await nextTick() }
async function requiredObservation() {
  await fill('任务执行状态', 'EXECUTED')
  await fill('实际执行者', '外部执行人')
  await fill('实际执行时间', '2026-11-21T19:12:13')
  await fill('实际执行环境', '隔离 H2')
  await fill('源码基线说明', '执行者记录的提交 abc')
  await fill('候选版本说明', '补丁工作副本')
  await fill('实际执行的方法', '运行两项定向测试')
  await fill('实际观察', '一项报错，一项通过')
  await fill('适用范围与限制', '仅本次定向范围，未定位单一组件')
}

describe('受报告范围限制的单项任务页面', () => {
  it('checks the source excerpt, readiness, and recovers a draft after a failed material read and later loss of one selection', async () => {
    await open(); await click('建立单项任务')
    await fill('要解决的问题', '查明两项报错')
    await fill('仍缺少的材料', '到外部环境获取日志')
    await fill('原报告摘录', '限制待定。'); await submit()
    expect(form().textContent).toContain('请从所选来源复制'); expect(taskPosts).toHaveLength(0)
    await fill('原建议来源', 'HUMAN_OR_TOOL'); await fill('建议人或工具（如已知）', '维护者补充')
    await check('keep'); await check('gone')
    uploadsFail = true; await click('刷新可用资料')
    expect(form().textContent).toContain('暂时无法读取'); expect(form().textContent).not.toContain('移除失效的资料选择')
    await submit(); expect(taskPosts).toHaveLength(0)
    uploadsFail = false; visible = [evidence]; await click('刷新可用资料')
    expect(form().textContent).toContain('1 份已选资料现在无法关联')
    await click('移除失效的资料选择')
    expect(field('要解决的问题').value).toBe('查明两项报错')
    await fill('交接状态', 'READY'); await submit()
    expect(taskPosts).toHaveLength(0); expect(form().textContent).toContain('需要补充时可保存为')
    await fill('交接状态', 'NEEDS_MATERIALS')
    loseTask = true; await submit()
    expect(form().querySelector('fieldset')?.disabled).toBe(true)
    await click('用原请求核对任务保存结果')
    expect(taskPosts).toHaveLength(2); expect(taskPosts[1]?.requestId).toBe(taskPosts[0]?.requestId)
    expect(taskPosts[1]?.fields).toMatchObject({ question: '查明两项报错', origin: 'HUMAN_OR_TOOL', originDetail: '维护者补充', uploadIds: ['keep'] })
    expect(versions).toHaveLength(1); expect(taskRegion().textContent).toContain('待补材料 · 到外部环境获取日志')
    expect(taskRegion().textContent).toContain('第 3 行')
  })

  it('copies selected raw original text and explicitly rebases a stale correction while retaining locally edited fields', async () => {
    versions = [initial()]
    await open(); await click('补充或更正任务')
    const source = field('供选取的原文') as HTMLTextAreaElement
    source.setSelectionRange(answer.indexOf('记录'), answer.length)
    await click('采用选中原文')
    expect(field('原报告摘录').value).toBe('记录实际的错误。')
    await fill('要解决的问题', '本地拟核对的问题')
    taskConflict = true; await submit()
    expect(form().textContent).toContain('已有人保存第 2 版')
    expect(button('保存任务版本').disabled).toBe(true)
    await submit(); expect(taskPosts).toHaveLength(1)
    await click('已核对，以第 2 版为更正依据')
    expect(field('已知事实与出处').value).toBe('并发补充的测试来源')
    expect(field('要解决的问题').value).toBe('本地拟核对的问题')
    await submit()
    expect(taskPosts[1]?.previousVersionId).toBe('v2')
    expect(taskPosts[1]?.requestId).not.toBe(taskPosts[0]?.requestId)
    expect(versions[0]?.fields.excerpt).toBe('先核对两个方法的执行条件。')
  })

  it('pins an external result to the explicitly viewed older task; lost multipart responses replay the same receipt, patch and build pair', async () => {
    versions = [initial('v1'), initial('v2', 2, { question: '较晚的修订' })]
    await open(); await fill('查看任务版本', 'v1'); await click('登记执行结果')
    expect(form().textContent).toContain('本次结果归属任务第 1 版')
    await fill('任务执行状态', 'EXECUTED'); await submit(); expect(resultPosts).toHaveLength(0)
    await requiredObservation()
    const picker = field('返回的补丁')
    const patch = new File(['diff --git a/pom.xml b/pom.xml\n'], 'candidate.patch')
    Object.defineProperty(picker, 'files', { value: [patch], configurable: true })
    picker.dispatchEvent(new Event('change')); await nextTick()
    await fill('结果引用的基线构建', 'baseline'); await submit()
    expect(resultPosts).toHaveLength(0); expect(form().textContent).toContain('同时选择')
    await fill('结果引用的候选构建', 'candidate')
    loseResult = true; await submit(); await click('用原请求核对结果保存情况')
    expect(results).toHaveLength(1); expect(verifications).toHaveLength(1)
    expect(resultPosts).toHaveLength(2)
    expect(resultPosts[0]!.path).toBe(taskPath + '/versions/v1/results')
    expect(resultPosts[1]!.command).toEqual(resultPosts[0]!.command)
    expect(resultPosts[1]!.patch?.name).toBe('candidate.patch')
    expect(resultPosts[1]!.command).toMatchObject({ impact: 'INCONCLUSIVE', baselineBuildId: 'baseline', candidateBuildId: 'candidate', executedBy: '外部执行人' })
    expect(taskRegion().textContent).toContain('外部执行人')
    expect(taskRegion().textContent).toContain('记录人 记录')
    const id = results[0]!.verificationVersionId
    await click('导出执行包'); await click('下载结果交接包')
    expect(paths).toContain('get ' + taskPath + '/versions/v1/execution.zip')
    expect(paths).toContain('get /reports/' + report + '/review/verifications/' + id + '/handoff.zip')
    expect(taskRegion().textContent).toContain('归档原件校验失败')
    expect(host.querySelector('#verification-' + id)?.textContent).toContain('一项报错，一项通过')
    await fill('查看任务版本', 'v2')
    expect(taskRegion().textContent).toContain('还没有关联到这一版的执行结果')
    await click('在所属任务中补充结果')
    expect(form().textContent).toContain('本次结果归属任务第 1 版')
    expect(form().querySelector<HTMLInputElement>('input[type=checkbox]')?.checked).toBe(true)
    await fill('未完成项', '需要采集额外环境记录'); await submit()
    expect(resultPosts[2]!.command).toMatchObject({ previousVerificationVersionId: id, retainPreviousPatch: true })
    expect(resultPosts[2]!.patch).toBeNull()
    expect(results[1]!.patch?.sha256).toBe(results[0]!.patch?.sha256)
    expect(verifications[0]?.fields.observation).toBe('一项报错，一项通过')
    expect(results[0]!.unfinished).toBe('')
  })

  it('keeps real observations, time and impact out of blocked or unexecuted submissions', async () => {
    versions = [initial()]
    await open(); await click('登记执行结果')
    await requiredObservation(); await fill('对判断的影响', 'SUPPORTS')
    await fill('任务执行状态', 'BLOCKED'); await submit(); expect(resultPosts).toHaveLength(0)
    await fill('受阻原因', '无法访问本次 H2 实例'); await submit()
    expect(resultPosts[0]!.command).toMatchObject({ status: 'BLOCKED', impact: 'INCONCLUSIVE', blocker: '无法访问本次 H2 实例',
      observation: '', executedAt: null, executedBy: '', retainPreviousPatch: false })
    await click('登记执行结果'); await submit(); expect(resultPosts).toHaveLength(1)
    await click('带入任务的计划方法'); await submit()
    expect(resultPosts[1]!.command).toMatchObject({ status: 'NOT_EXECUTED', impact: 'INCONCLUSIVE', method: '只运行两个方法', observation: '' })
    expect(host.textContent).not.toContain('允许合并')
  })

  it('carries the explicitly chosen verification version and saved excerpt into a ready task without copying observations into plan facts', async () => {
    const v: Verification = { id: 'verify-old', version: 1, previousVersionId: null, author: actor, createdAt: time,
      fields: { question: '定向复核问题', suggestionRef: answer.split('\n')[0]!, origin: 'MODEL', status: 'EXECUTED',
        method: '旧执行方法', environment: '旧环境', observation: '旧观察', impact: 'INCONCLUSIVE', limits: '旧限制', blocker: '', uploadIds: [] } }
    verifications = [v, { ...v, id: 'verify-new', previousVersionId: v.id, version: 2 }]
    await open()
    button('据此验证版本建立任务', host.querySelector('#verification-verify-old')!).click(); await settle()
    expect(field('原报告摘录').value).toBe(v.fields.suggestionRef)
    expect(field('要解决的问题').value).toBe('定向复核问题')
    expect(field('已知事实与出处').value).toBe('')
    expect(form().querySelector<HTMLInputElement>('input[value="verify-old"]')!.checked).toBe(true)
    await fill('交接状态', 'READY'); await submit()
    expect(taskPosts).toHaveLength(0)
    for (const label of ['预期交付', '本次修改或检查范围', '明确排除的范围', '拟执行方法', '所需执行环境', '预期观察与完成条件', '失败、受阻或停止条件']) {
      await fill(label, label + '的具体内容')
    }
    const event = () => new Event('submit', { bubbles: true, cancelable: true })
    const formNode = form()
    formNode.dispatchEvent(event()); formNode.dispatchEvent(event()); await settle()
    expect(taskPosts).toHaveLength(1)
    expect(taskPosts[0]!.fields).toMatchObject({ readiness: 'READY', verificationVersionIds: ['verify-old'], facts: '' })
    expect(taskRegion().textContent).toContain('可交给执行者')
    expect(taskRegion().querySelector('a[href="#verification-verify-old"]')).not.toBeNull()
  })

  it('requires review before changing the correction parent of an execution result and keeps the original task version', async () => {
    versions = [initial()]
    await open(); await click('登记执行结果')
    await requiredObservation(); await submit()
    await click('补充或更正执行结果'); await fill('未完成项', '本地草稿')
    const first = verifications[0]!, firstResult = results[0]!
    verifications.push({ ...first, id: 'concurrent', previousVersionId: first.id, version: 2,
      fields: { ...first.fields, limits: '他人补充的适用条件' } })
    results.push({ ...firstResult, verificationVersionId: 'concurrent', candidateRevision: '他人记录的候选说明' })
    await click('刷新记录')
    expect(button('保存执行结果').disabled).toBe(true); await submit()
    expect(resultPosts).toHaveLength(1)
    await click('已核对，以最新验证版为依据并保留已编辑字段')
    expect(field('未完成项').value).toBe('本地草稿')
    expect(field('候选版本说明').value).toBe('他人记录的候选说明')
    expect(field('适用范围与限制').value).toBe('他人补充的适用条件')
    await submit()
    expect(resultPosts[1]!.command.previousVerificationVersionId).toBe('concurrent')
    expect(resultPosts[1]!.path).toBe(taskPath + '/versions/v1/results')
    expect(firstResult.candidateRevision).toBe('补丁工作副本')
  })
})
