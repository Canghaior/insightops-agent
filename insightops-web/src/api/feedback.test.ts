import { afterEach, describe, expect, it, vi } from 'vitest'
import { apiClient } from './client'
import { submitAnswerFeedback, submitCitationFeedback } from './feedback'

describe('research feedback api',()=>{
  afterEach(()=>vi.restoreAllMocks())
  it('saves answer and citation feedback against a run',async()=>{
    const put=vi.spyOn(apiClient,'put').mockResolvedValue({data:undefined})
    await submitAnswerFeedback('run-1',false,'MISSING_EVIDENCE','missing issue')
    await submitCitationFeedback('run-1','https://github.com/acme/agent/issues/1',true)
    expect(put).toHaveBeenNthCalledWith(1,'/research-feedback/runs/run-1',{helpful:false,reason:'MISSING_EVIDENCE',comment:'missing issue'})
    expect(put).toHaveBeenNthCalledWith(2,'/research-feedback/runs/run-1/citations',{citationUrl:'https://github.com/acme/agent/issues/1',correct:true,comment:undefined})
  })
})
