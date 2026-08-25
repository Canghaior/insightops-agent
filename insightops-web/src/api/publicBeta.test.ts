import { beforeEach, describe, expect, it, vi } from 'vitest'

const { get, post, patch } = vi.hoisted(() => ({ get: vi.fn(), post: vi.fn(), patch: vi.fn() }))
vi.mock('./client', () => ({ apiClient: { get, post, patch } }))

import { getPublicBetaStatus, registerPublicBeta, updatePublicBetaControl } from './publicBeta'

describe('public beta api', () => {
  beforeEach(() => vi.clearAllMocks())

  it('loads public readiness without authentication', async () => {
    get.mockResolvedValue({ data: { registrationEnabled: false, reason: 'REGISTRATION_SWITCH_OFF' } })
    await expect(getPublicBetaStatus()).resolves.toEqual({
      registrationEnabled: false, reason: 'REGISTRATION_SWITCH_OFF',
    })
    expect(get).toHaveBeenCalledWith('/public/identity/registration/status')
  })

  it('submits all server-validated consent and turnstile fields', async () => {
    const request = { username: 'beta-user', displayName: 'Beta User', email: 'beta@example.com',
      password: 'StrongPass1', turnstileToken: 'token', ageConfirmed: true,
      termsAccepted: true, privacyAccepted: true, acceptableUseAccepted: true }
    post.mockResolvedValue({ data: { registrationSlot: 7 } })
    await expect(registerPublicBeta(request)).resolves.toEqual({ registrationSlot: 7 })
    expect(post).toHaveBeenCalledWith('/public/identity/registration', request)
  })

  it('updates registration and run emergency switches independently', async () => {
    patch.mockResolvedValue({ data: { control: { registrationEnabled: false, runsEnabled: false } } })
    await updatePublicBetaControl({ registrationEnabled: false, runsEnabled: false,
      statusMessage: 'Maintenance' })
    expect(patch).toHaveBeenCalledWith('/admin/public-beta', {
      registrationEnabled: false, runsEnabled: false, statusMessage: 'Maintenance',
    })
  })
})
