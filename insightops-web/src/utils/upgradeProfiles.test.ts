import { describe, expect, it } from 'vitest'
import { applyReleaseEvent, restoreUpgradeInput, suggestResearchProfile } from './upgradeProfiles'
import type { ResearchEventContext } from '@/api/upgradeAssessments'

describe('research profiles', () => {
  it('restores existing Java presets into the new context without dropping versions or issues', () => {
    const restored = restoreUpgradeInput({ projectId: 'spring', javaVersion: '21', springBootVersion: '3.4.5', currentVersion: '1.0.0', targetVersion: '1.1.1', issueUrls: ['https://github.com/spring-projects/spring-ai/issues/6620'] })
    expect(restored.profileId).toBe('java_backend')
    expect(restored.context).toEqual({ javaVersion: '21', springBootVersion: '3.4.5' })
    expect(restored.targetVersion).toBe('1.1.1')
    expect(restored.issueUrls).toHaveLength(1)
  })
  it('keeps frontend facts independent from a saved object and clears legacy Java fields', () => {
    const context = { nodeVersion: '22', renderMode: 'SPA' }
    const restored = restoreUpgradeInput({ profileId: 'web_frontend', context, javaVersion: '21' })
    expect(restored.javaVersion).toBe('')
    restored.context.nodeVersion = '24'
    expect(context.nodeVersion).toBe('22')
    expect(restored.context.renderMode).toBe('SPA')
  })
  it('suggests a direction from the framework while retaining a generic option', () => {
    expect(suggestResearchProfile('vuejs', 'core')).toBe('web_frontend')
    expect(suggestResearchProfile('spring-projects', 'spring-ai')).toBe('java_backend')
    expect(suggestResearchProfile('other', 'framework')).toBe('general')
  })
  it('keeps business context and current version when applying a resolved Release to a preset', () => {
    const preset = restoreUpgradeInput({ projectId: 'spring', currentVersion: '1.0.0', targetVersion: '1.1.0', businessProject: '订单服务', context: { javaVersion: '21' } })
    const event = { eventId: 'event-1', projectId: 'spring', eventType: 'GITHUB_RELEASE', versionTag: 'v1.2.0' } as ResearchEventContext
    const result = applyReleaseEvent(preset, event)
    expect(result).toMatchObject({ currentVersion: '1.0.0', targetVersion: 'v1.2.0', businessProject: '订单服务', eventId: 'event-1', context: { javaVersion: '21' } })
    expect(preset.targetVersion).toBe('1.1.0')
    expect(restoreUpgradeInput(result as unknown as Record<string, unknown>).eventId).toBe('event-1')
  })
  it('does not blend another framework or a non-Release into an upgrade assessment', () => {
    const preset = restoreUpgradeInput({ projectId: 'spring' })
    expect(() => applyReleaseEvent(preset, { projectId: 'vue', eventType: 'GITHUB_RELEASE', versionTag: 'v3.5.0' } as ResearchEventContext)).toThrow('相同框架')
    expect(() => applyReleaseEvent(preset, { projectId: 'spring', eventType: 'GITHUB_ISSUE' } as ResearchEventContext)).toThrow('Release')
  })
})
