import { afterEach, describe, expect, it, vi } from 'vitest'
import { apiClient } from './client'
import { getDigestPreference, getIntelligence, listIntelligence, saveDigestPreference } from './intelligence'

describe('intelligence api', () => {
  afterEach(() => vi.restoreAllMocks())
  it('lists and reads user-scoped intelligence', async () => {
    const get=vi.spyOn(apiClient,'get')
      .mockResolvedValueOnce({data:{data:{items:[],page:0,size:20,total:0}}})
      .mockResolvedValueOnce({data:{data:{summary:{analysisId:'a-1'},majorChanges:[]}}})
    expect((await listIntelligence({riskLevel:'HIGH'})).total).toBe(0)
    expect((await getIntelligence('a-1')).summary.analysisId).toBe('a-1')
    expect(get).toHaveBeenNthCalledWith(1,'/intelligence',{params:{riskLevel:'HIGH'}})
    expect(get).toHaveBeenNthCalledWith(2,'/intelligence/a-1')
  })
  it('loads and saves digest preference', async () => {
    const value={cadence:'DAILY' as const,timeZone:'Asia/Shanghai',deliveryHour:9,projectIds:['p-1']}
    vi.spyOn(apiClient,'get').mockResolvedValue({data:{data:value}})
    const put=vi.spyOn(apiClient,'put').mockResolvedValue({data:{data:value}})
    expect((await getDigestPreference()).cadence).toBe('DAILY')
    expect((await saveDigestPreference(value)).projectIds).toEqual(['p-1'])
    expect(put).toHaveBeenCalledWith('/digests/preference',value)
  })
})
