import { afterEach, describe, expect, it, vi } from 'vitest'
import { createSSRApp, h } from 'vue'
import { renderToString } from 'vue/server-renderer'
import { apiClient } from './client'
import { createSystem, getSystem, launchSystemUpgrade, systemHistory, updateSystem, type BusinessSystem } from './businessSystems'
import type { WorkflowRunDetail } from './workflowRuns'
import SystemBackgroundPanel from '@/components/SystemBackground.vue'
import { previousAssessmentInput, restoreUpgradeInput, systemSnapshotOf } from '@/utils/upgradeProfiles'

afterEach(() => vi.restoreAllMocks())

const profile: BusinessSystem = {
  id: 'order-service', revision: 1, attachments: [{ uploadId: 'guide', name: '流程说明.txt' }],
  background: { name: '订单系统', purpose: '履约', keyProcesses: '支付回调', technologyStack: 'Java 17',
    currentProblems: '', constraints: '限时发布' },
}
const workflow = {
  businessSystemId: profile.id,
  inputs: { projectId: 'framework', currentVersion: '1.0.0', targetVersion: '1.1.0',
    systemSnapshot: profile, uploadIds: ['guide', 'extra'] },
} as WorkflowRunDetail

describe('personal business systems', () => {
  it('creates and reloads a version-free profile, using the observed revision when editing', async () => {
    const draft = { background: profile.background, uploadIds: ['guide'] }
    const post = vi.spyOn(apiClient, 'post').mockResolvedValue({ data: { data: profile } })
    const get = vi.spyOn(apiClient, 'get').mockResolvedValue({ data: { data: profile } })
    const put = vi.spyOn(apiClient, 'put').mockResolvedValue({ data: { data: { ...profile, revision: 2 } } })
    await expect(createSystem(draft)).resolves.toEqual(profile)
    expect(post).toHaveBeenCalledWith('/business-systems', draft)
    const saved = await getSystem(profile.id)
    await expect(updateSystem(saved.id, saved.revision, draft)).resolves.toMatchObject({ revision: 2 })
    expect(put).toHaveBeenCalledWith('/business-systems/order-service', { ...draft, expectedRevision: 1 })
    await systemHistory(profile.id, 1)
    expect(get).toHaveBeenCalledWith('/business-systems/order-service/runs', { params: { page: 1 } })
  })

  it('uses server-scoped launch and restores only the extra files for a new assessment', async () => {
    const post = vi.spyOn(apiClient, 'post').mockResolvedValue({ data: { data: { runId: 'r2' } } })
    const input = restoreUpgradeInput(previousAssessmentInput(workflow))
    expect(input.uploadIds).toEqual(['extra'])
    expect(input).not.toHaveProperty('systemSnapshot')
    await launchSystemUpgrade(profile.id, 2, input, 'request-2')
    expect(post).toHaveBeenCalledWith('/business-systems/order-service/upgrade-runs',
      { ...input, requestId: 'request-2' }, { params: { expectedRevision: 2 } })
    expect(systemSnapshotOf({ ...workflow, businessSystemId: null })).toBeNull()
    expect(systemSnapshotOf({ ...workflow, businessSystemId: 'different' })).toBeNull()
    expect(systemSnapshotOf({ ...workflow, inputs: { businessProject: '订单系统' } })).toBeNull()
  })

  it('renders the frozen revision, user attribution and file names as text', async () => {
    const app = createSSRApp({ render: () => h(SystemBackgroundPanel, { system: systemSnapshotOf(workflow)! }) })
    const html = await renderToString(app)
    for (const value of ['用户提供', '档案修订 1', '已知技术栈', 'Java 17', '支付回调', '未提供', '流程说明.txt']) {
      expect(html).toContain(value)
    }
    expect(html).not.toContain('technologyStack')
  })
})
