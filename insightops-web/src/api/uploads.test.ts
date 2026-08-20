import { beforeEach, describe, expect, it, vi } from 'vitest'

const { get, post, del } = vi.hoisted(() => ({
  get: vi.fn(), post: vi.fn(), del: vi.fn(),
}))

vi.mock('./client', () => ({ apiClient: { get, post, delete: del } }))

import {
  deleteKnowledgeUpload,
  knowledgeUploadDownloadUrl,
  listKnowledgeUploads,
  retryKnowledgeUpload,
  uploadKnowledgeFile,
} from './uploads'

describe('knowledge uploads api', () => {
  beforeEach(() => vi.clearAllMocks())

  it('lists uploads and submits a multipart file', async () => {
    get.mockResolvedValue({ data: { data: [{ uploadId: 'upload-1' }] } })
    post.mockResolvedValue({ data: { data: { uploadId: 'upload-2' } } })

    await expect(listKnowledgeUploads()).resolves.toEqual([{ uploadId: 'upload-1' }])
    const file = new File(['# Notes'], 'notes.md', { type: 'text/markdown' })
    await expect(uploadKnowledgeFile('project-1', 'PRIVATE', file)).resolves.toMatchObject({ uploadId: 'upload-2' })

    const form = post.mock.calls[0][1] as FormData
    expect(form.get('projectId')).toBe('project-1')
    expect(form.get('visibility')).toBe('PRIVATE')
    expect(form.get('file')).toBe(file)
  })

  it('uses authenticated download, retry and delete endpoints', async () => {
    post.mockResolvedValue({}); del.mockResolvedValue({})
    await retryKnowledgeUpload('upload-1')
    await deleteKnowledgeUpload('upload-1')
    expect(post).toHaveBeenCalledWith('/knowledge/uploads/upload-1/retry')
    expect(del).toHaveBeenCalledWith('/knowledge/uploads/upload-1')
    expect(knowledgeUploadDownloadUrl('upload-1')).toBe('/api/v1/knowledge/uploads/upload-1/content')
  })
})
