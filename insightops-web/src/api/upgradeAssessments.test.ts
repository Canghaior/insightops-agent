import { afterEach, describe, expect, it, vi } from 'vitest'
import { apiClient } from './client'
import { getResearchEvent, launchUpgrade } from './upgradeAssessments'
import { restoreUpgradeInput } from '@/utils/upgradeProfiles'

afterEach(() => vi.restoreAllMocks())

describe('event research handoff', () => {
  it('resolves context from an authenticated event ID instead of supplied URL or title', async () => {
    const event = { eventId: 'e1', sourceUrl: 'https://github.com/vuejs/core/releases/tag/v3.5.0' }
    const get = vi.spyOn(apiClient, 'get').mockResolvedValue({ data: { data: event } })
    await expect(getResearchEvent('e1')).resolves.toEqual(event)
    expect(get).toHaveBeenCalledWith('/upgrade-assessments/events/e1')
  })
  it('retains event provenance and idempotency when launching an assessment', async () => {
    const post = vi.spyOn(apiClient, 'post').mockResolvedValue({ data: { data: { runId: 'run-1' } } })
    const input = restoreUpgradeInput({ projectId: 'spring', eventId: 'event-1', currentVersion: '1.0.0', targetVersion: '1.1.0' })
    await launchUpgrade(input, 'request-1')
    expect(post).toHaveBeenCalledWith('/upgrade-assessments/runs', expect.objectContaining({ eventId: 'event-1', requestId: 'request-1' }))
  })
})
