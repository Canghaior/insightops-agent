import { afterEach, describe, expect, it, vi } from 'vitest'

import { apiClient } from './client'
import { createDeliveryChannel, createReport, downloadReport, enqueueReportDelivery } from './reports'

afterEach(() => vi.restoreAllMocks())

describe('report delivery api', () => {
  it('creates a bounded report query', async () => {
    const post = vi.spyOn(apiClient, 'post').mockResolvedValue({
      data: { data: { id: 'r1', title: 'Weekly report', items: [], itemCount: 1, highRiskCount: 0 } },
    })
    const command = {
      title: 'Weekly report', periodStart: '2026-08-01T00:00:00Z', periodEnd: '2026-08-08T00:00:00Z',
      projectIds: ['p1'], eventTypes: ['GITHUB_RELEASE'] as const, maxItems: 50,
    }

    const report = await createReport({ ...command, eventTypes: [...command.eventTypes] })

    expect(report.id).toBe('r1')
    expect(post).toHaveBeenCalledWith('/reports', { ...command, eventTypes: [...command.eventTypes] })
  })

  it('keeps webhook endpoint write-only and downloads binary exports', async () => {
    const blob = new Blob(['pdf'], { type: 'application/pdf' })
    const post = vi.spyOn(apiClient, 'post')
      .mockResolvedValueOnce({ data: { data: {
        id: 'c1', name: 'Team', type: 'WEBHOOK', endpointMasked: 'https://hooks.example.com/***',
      } } })
      .mockResolvedValueOnce({ data: { data: { id: 'd1', status: 'PENDING' } } })
    const get = vi.spyOn(apiClient, 'get').mockResolvedValue({ data: blob })

    const channel = await createDeliveryChannel('Team', 'https://hooks.example.com/token')

    expect(channel.endpointMasked).not.toContain('token')
    expect((await enqueueReportDelivery('r1', channel.id)).status).toBe('PENDING')
    expect((await downloadReport('r1', 'pdf')).type).toBe('application/pdf')
    expect(post).toHaveBeenNthCalledWith(1, '/delivery-channels', {
      name: 'Team', endpointUrl: 'https://hooks.example.com/token', enabled: true,
    })
    expect(post).toHaveBeenNthCalledWith(2, '/reports/r1/deliveries', { channelId: 'c1' })
    expect(get).toHaveBeenCalledWith('/reports/r1/export.pdf', { responseType: 'blob', timeout: 60_000 })
  })
})
