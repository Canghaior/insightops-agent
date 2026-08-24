import { beforeEach, describe, expect, it, vi } from 'vitest'

const { get, post, del } = vi.hoisted(() => ({ get: vi.fn(), post: vi.fn(), del: vi.fn() }))
vi.mock('./client', () => ({ apiClient: { get, post, delete: del } }))
import { forgotPassword, getSecurity, requestEmail, revokeSession } from './identity'

describe('identity api', () => {
  beforeEach(() => vi.clearAllMocks())
  it('loads security state and queues a verified email change', async () => {
    get.mockResolvedValue({ data: { data: { mfaEnabled: false } } })
    await expect(getSecurity()).resolves.toEqual({ mfaEnabled: false })
    post.mockResolvedValue({ data: { data: { deliveryQueued: true } } })
    await requestEmail('Password1A', 'owner@example.com')
    expect(post).toHaveBeenCalledWith('/identity/email', { password: 'Password1A', email: 'owner@example.com' })
  })
  it('uses generic recovery and explicit session revoke endpoints', async () => {
    post.mockResolvedValue({})
    await forgotPassword('owner@example.com')
    expect(post).toHaveBeenCalledWith('/public/identity/password/forgot', { email: 'owner@example.com' })
    del.mockResolvedValue({})
    await revokeSession('session-1')
    expect(del).toHaveBeenCalledWith('/identity/sessions/session-1')
  })
})
