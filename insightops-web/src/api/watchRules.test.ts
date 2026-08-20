import { afterEach, describe, expect, it, vi } from 'vitest'
import { apiClient } from './client'
import { createWatchRule, deleteWatchRule, listWatchRules, updateWatchRule, type WatchRuleCommand } from './watchRules'

describe('watch rules api',()=>{
  afterEach(()=>vi.restoreAllMocks())
  const command:WatchRuleCommand={name:'Security',projectId:null,keywords:['cve'],excludedKeywords:[],eventTypes:['GITHUB_SECURITY_ADVISORY'],minimumImportance:3,immediateNotification:true,includeInDigest:true,enabled:true}
  it('supports list create update and delete',async()=>{
    vi.spyOn(apiClient,'get').mockResolvedValue({data:{data:[]}})
    const post=vi.spyOn(apiClient,'post').mockResolvedValue({data:{data:{id:'rule-1'}}})
    const put=vi.spyOn(apiClient,'put').mockResolvedValue({data:{data:{id:'rule-1'}}})
    const remove=vi.spyOn(apiClient,'delete').mockResolvedValue({data:undefined})
    expect(await listWatchRules()).toEqual([]);await createWatchRule(command);await updateWatchRule('rule-1',command);await deleteWatchRule('rule-1')
    expect(post).toHaveBeenCalledWith('/watch-rules',command)
    expect(put).toHaveBeenCalledWith('/watch-rules/rule-1',command)
    expect(remove).toHaveBeenCalledWith('/watch-rules/rule-1')
  })
})
