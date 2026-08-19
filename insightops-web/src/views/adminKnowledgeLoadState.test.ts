import { describe, expect, it } from 'vitest'

import {
  beginKnowledgeStatusLoad,
  completeKnowledgeStatusLoad,
  failKnowledgeStatusLoad,
} from './adminKnowledgeLoadState'

describe('knowledge status refresh errors', () => {
  it('clears an old silent refresh error after the next successful refresh', () => {
    const error = { value: '' }
    const refreshError = { value: '' }

    failKnowledgeStatusLoad(error, refreshError, true, 'network unavailable')
    expect(refreshError.value).toContain('network unavailable')

    completeKnowledgeStatusLoad(refreshError)
    expect(refreshError.value).toBe('')
    expect(error.value).toBe('')
  })

  it('does not overwrite an operation error during a silent refresh failure', () => {
    const error = { value: '采集提交失败' }
    const refreshError = { value: '' }

    failKnowledgeStatusLoad(error, refreshError, true, 'status endpoint timed out')

    expect(error.value).toBe('采集提交失败')
    expect(refreshError.value).toContain('status endpoint timed out')
  })

  it('clears both error channels when a manual load begins', () => {
    const error = { value: 'manual failure' }
    const refreshError = { value: 'refresh failure' }

    beginKnowledgeStatusLoad(error, refreshError, false)

    expect(error.value).toBe('')
    expect(refreshError.value).toBe('')
  })
})
