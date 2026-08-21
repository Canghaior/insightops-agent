import { apiClient } from './client'

export interface McpConnection {
  id: string
  name: string
  endpoint: string
  allowedToolsJson: string
  enabled: boolean
  createdAt: string
  updatedAt: string
}

export interface McpConnectionInput {
  name: string
  endpoint: string
  allowedTools: Record<string, string>
  enabled: boolean
}

export async function listMcpConnections(): Promise<McpConnection[]> {
  const response = await apiClient.get<{ data: McpConnection[] }>('/admin/mcp-connections')
  return response.data.data
}

export async function createMcpConnection(input: McpConnectionInput): Promise<McpConnection> {
  const response = await apiClient.post<{ data: McpConnection }>('/admin/mcp-connections', input)
  return response.data.data
}

export async function updateMcpConnection(id: string, input: McpConnectionInput): Promise<McpConnection> {
  const response = await apiClient.put<{ data: McpConnection }>(
    `/admin/mcp-connections/${encodeURIComponent(id)}`,
    input,
  )
  return response.data.data
}

export async function deleteMcpConnection(id: string): Promise<void> {
  await apiClient.delete(`/admin/mcp-connections/${encodeURIComponent(id)}`)
}
