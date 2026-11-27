import { apiClient } from './client'

export interface ResearchModelOption { id: string; label: string; provider: string }
export interface ResearchModelCatalog { defaultLabel: string; options: ResearchModelOption[] }

export async function getResearchModels(): Promise<ResearchModelCatalog> {
  const response = await apiClient.get<{ data: ResearchModelCatalog }>('/research-models')
  return response.data.data
}

export function modelChoiceLabel(id: string): string {
  if (id.startsWith('gpt-')) {
    return id.replace(/^gpt/, 'GPT').replace(/-(sol|astra|terra)$/, (_, name: string) =>
      ' ' + name.charAt(0).toUpperCase() + name.slice(1))
  }
  if (id.startsWith('deepseek-')) {
    const model = id.includes('pro') ? 'DeepSeek Pro' : id.includes('flash') ? 'DeepSeek Flash' : id
    return model + (id.endsWith('-thinking') ? ' · 思考' : '')
  }
  return id
}
