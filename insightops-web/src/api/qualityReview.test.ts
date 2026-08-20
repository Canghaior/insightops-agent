import { afterEach, describe, expect, it, vi } from 'vitest'
import { apiClient } from './client'
import {
  activateDatasetVersion, createDatasetVersion, decideEvaluationCandidate,
  evaluateDatasetVersion, listDatasetVersions, listEvaluationCandidates,
  listQualityFeedback, reviewQualityFeedback, updateEvaluationCandidate,
  type CandidateCommand, type QualityFeedback,
} from './qualityReview'

describe('quality review api', () => {
  afterEach(() => vi.restoreAllMocks())

  it('supports feedback and candidate review endpoints', async () => {
    const get = vi.spyOn(apiClient, 'get').mockResolvedValue({ data: { data: { items: [], total: 0 } } })
    const patch = vi.spyOn(apiClient, 'patch').mockResolvedValue({ data: { data: { id: 'f1' } } })
    const put = vi.spyOn(apiClient, 'put').mockResolvedValue({ data: { data: { id: 'c1' } } })
    const feedback = { id: 'f1', type: 'ANSWER' } as QualityFeedback
    const candidate: CandidateCommand = {
      question: 'q', expectedAnswerable: true, expectedProject: 'spring-ai',
      category: 'feedback', mustHitTerms: ['spring'], answerMustInclude: [], sourceDomain: 'docs.spring.io',
    }
    await listQualityFeedback('PENDING', 'ANSWER')
    await reviewQualityFeedback(feedback, 'ADD_TO_EVAL', 'note', candidate)
    await listEvaluationCandidates('DRAFT')
    await updateEvaluationCandidate('c1', candidate)
    await decideEvaluationCandidate('c1', 'APPROVED', 'ready')
    expect(get).toHaveBeenCalledTimes(2)
    expect(patch).toHaveBeenCalledTimes(2)
    expect(put).toHaveBeenCalledWith('/admin/quality/candidates/c1', candidate)
  })

  it('supports dataset version lifecycle endpoints', async () => {
    vi.spyOn(apiClient, 'get').mockResolvedValue({ data: { data: [] } })
    const post = vi.spyOn(apiClient, 'post').mockResolvedValue({ data: { data: { id: 'v1' } } })
    await listDatasetVersions()
    await createDatasetVersion('feedback-v1', ['c1'])
    await evaluateDatasetVersion('v1')
    await activateDatasetVersion('v1')
    expect(post).toHaveBeenCalledTimes(3)
  })
})
