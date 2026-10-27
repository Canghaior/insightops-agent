import { describe, expect, it } from 'vitest'
import { restoreUpgradeInput, suggestResearchProfile } from './upgradeProfiles'

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
})
