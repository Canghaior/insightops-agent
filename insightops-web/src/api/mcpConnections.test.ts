import { afterEach, describe, expect, it, vi } from 'vitest'

import { apiClient } from './client'
import { createMcpConnection, updateMcpConnection } from './mcpConnections'

describe('MCP connection administration api', () => {
  afterEach(() => vi.restoreAllMocks())

  it('sends an explicit disabled allowlist by default', async () => {
    const post = vi.spyOn(apiClient, 'post').mockResolvedValue({ data: { data: { id: 'm1' } } })
    const input = {
      name: 'docs', endpoint: 'https://mcp.example.com/mcp',
      allowedTools: { search_docs: '只读搜索' }, enabled: false,
    }
    await createMcpConnection(input)
    expect(post).toHaveBeenCalledWith('/admin/mcp-connections', input)
  })

  it('updates only the selected workspace connection', async () => {
    const put = vi.spyOn(apiClient, 'put').mockResolvedValue({ data: { data: { id: 'm/1' } } })
    const input = {
      name: 'docs', endpoint: 'https://mcp.example.com/mcp',
      allowedTools: { search_docs: '只读搜索' }, enabled: true,
    }
    await updateMcpConnection('m/1', input)
    expect(put).toHaveBeenCalledWith('/admin/mcp-connections/m%2F1', input)
  })
})
