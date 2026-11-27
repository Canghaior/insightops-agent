import { apiClient } from './client'
import { isAxiosError } from 'axios'

export const kindLabels = {
  DECLARATION: 'POM 声明', STATIC_DERIVATION: '静态推导', MAVEN_RESOLUTION: 'Maven 实际解析记录',
  BUILD_ENVIRONMENT: '构建工具环境', RUNTIME_OBSERVATION: '应用进程观察',
} as const
interface Source { path: string; line: number }
export interface BaselineContent {
  label: string; note: string; expectedJava: string; profileAtImport: { revision: number; technologyStack: string }
  evidence: { sourceLabel: string; files: { path: string; size: number; sha256: string }[]
    facts: { kind: keyof typeof kindLabels; subject: string; value: string; source: Source; via: Source[] }[]; gaps: string[] }
  conflicts: string[]; originalName: string; originalSha256: string
}
export interface BaselineSummary { id: string; systemId: string; version: number; label: string; savedAt: string }
export interface EngineeringBaseline { baseline: BaselineSummary; content: BaselineContent }
export interface BaselinePreview { token: string; content: BaselineContent }
export function frozenBaseline(inputs: unknown): EngineeringBaseline | null {
  const entry = (inputs as { engineeringBaseline?: EngineeringBaseline } | null)?.engineeringBaseline
  return entry?.baseline?.id && entry.content?.evidence ? entry : null
}
const path = (system: string) => '/business-systems/' + encodeURIComponent(system) + '/engineering-baselines'
export async function baselines(system: string): Promise<BaselineSummary[]> {
  return (await apiClient.get(path(system))).data.data
}
export async function baselineDetail(system: string, id: string): Promise<EngineeringBaseline> {
  return (await apiClient.get(path(system) + '/' + encodeURIComponent(id))).data.data
}
export async function previewBaseline(system: string, data: FormData): Promise<BaselinePreview> {
  return (await apiClient.post(path(system) + '/preview', data)).data.data
}
export async function packageBaseline(system: string, data: FormData): Promise<Blob> {
  try {
    return (await apiClient.post(path(system) + '/package', data, { responseType: 'blob' })).data
  } catch (error) {
    if (isAxiosError(error)) {
      if (error.response?.status === 413) throw new Error('材料超过上传限制。每份最多 1 MiB，工程包合计最多 6 MiB。')
      const body = error.response?.data
      if (body instanceof Blob && body.type.includes('json')) {
        const problem = await body.text().then(text => JSON.parse(text)).catch(() => null)
        if (typeof problem?.detail === 'string') throw new Error(problem.detail)
      }
    }
    throw error
  }
}
export async function saveBaseline(system: string, data: FormData): Promise<EngineeringBaseline> {
  return (await apiClient.post(path(system), data)).data.data
}
export function originalUrl(system: string, id: string, file?: string) {
  return apiClient.getUri({ url: path(system) + '/' + encodeURIComponent(id) + '/original', params: file ? { path: file } : {} })
}

