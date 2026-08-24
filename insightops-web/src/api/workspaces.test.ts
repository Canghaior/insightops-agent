import { beforeEach, describe, expect, it, vi } from 'vitest'

const { get, post, patch, del } = vi.hoisted(() => ({ get: vi.fn(), post: vi.fn(), patch: vi.fn(), del: vi.fn() }))
vi.mock('./client', () => ({ apiClient: { get, post, patch, delete: del } }))
import { createWorkspace, invitationPreview, switchWorkspace, updateMemberRole } from './workspaces'

describe('workspace api', () => {
  beforeEach(() => vi.clearAllMocks())
  it('creates and switches a tenant workspace', async () => {
    post.mockResolvedValueOnce({ data: { data: { id: 'workspace-2' } } }).mockResolvedValueOnce({})
    await createWorkspace({ name: 'Team', slug: 'team-one', description: '' })
    expect(post).toHaveBeenCalledWith('/workspaces', { name: 'Team', slug: 'team-one', description: '' })
    await switchWorkspace('workspace-2')
    expect(post).toHaveBeenCalledWith('/workspaces/workspace-2/switch')
  })
  it('previews invitation without putting its token in the URL and updates roles', async () => {
    post.mockResolvedValue({ data: { data: { maskedEmail: 'o***@example.com' } } })
    await invitationPreview('token-value')
    expect(post).toHaveBeenCalledWith('/public/invitations/preview', { token: 'token-value' })
    patch.mockResolvedValue({})
    await updateMemberRole('workspace-2', 'user-2', 'OWNER')
    expect(patch).toHaveBeenCalledWith('/workspaces/workspace-2/members/user-2', { role: 'OWNER' })
  })
})
