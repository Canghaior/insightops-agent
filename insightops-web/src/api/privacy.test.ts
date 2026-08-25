import { beforeEach, describe, expect, it, vi } from 'vitest'

const { post } = vi.hoisted(() => ({ post: vi.fn() }))
vi.mock('./client', () => ({ apiClient: { post } }))
import { createPersonalDataExport, downloadPersonalDataExport, requestPublicAccountDeletion } from './privacy'

describe('privacy api', () => {
  beforeEach(() => vi.clearAllMocks())
  it('creates and consumes a one-time export token', async () => {
    const created = { exportId: 'e1', downloadToken: 'secret', expiresAt: '2026-08-27T00:00:00Z' }
    post.mockResolvedValueOnce({ data: created }).mockResolvedValueOnce({ data: new ArrayBuffer(2) })
    await expect(createPersonalDataExport()).resolves.toEqual(created)
    await downloadPersonalDataExport(created)
    expect(post).toHaveBeenLastCalledWith('/identity/exports/e1/download', { token: 'secret' },
      { responseType: 'arraybuffer' })
  })
  it('uses the isolated public personal account deletion endpoint', async () => {
    post.mockResolvedValue({ data: { scheduledAt: '2026-09-02T00:00:00Z' } })
    await expect(requestPublicAccountDeletion('StrongPass1', '123456')).resolves.toBe('2026-09-02T00:00:00Z')
  })
})
