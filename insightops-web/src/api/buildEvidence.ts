import { apiClient } from './client'

export type BuildRole = 'BASELINE' | 'CANDIDATE'
export type CaseStatus = 'PASSED' | 'FAILURE' | 'ERROR' | 'SKIPPED'
export const buildRoles: Record<BuildRole, string> = { BASELINE: '基线构建', CANDIDATE: '候选构建' }
export const caseStatuses: Record<CaseStatus, string> = {
  PASSED: '通过', FAILURE: '断言失败', ERROR: '执行报错', SKIPPED: '跳过',
}
export interface BuildCounts { passed: number; failures: number; errors: number; skipped: number }
export interface BuildSummary {
  id: string
  systemId: string
  reportId: string
  role: BuildRole
  label: string | null
  importedAt: string
  counts: BuildCounts
}
export interface BuildMetadata {
  label: string | null
  environment: string | null
  executedAt: string | null
  executedBy: string | null
  exitCode: number | null
  sourceNote: string | null
}
export type EvidenceFileKind = 'SUREFIRE_XML' | 'MAVEN_LOG' | 'COMMAND_RECORD' | 'EXCLUSION_MANIFEST'
export const fileKinds: Record<EvidenceFileKind, string> = {
  SUREFIRE_XML: 'Surefire 测试结果', MAVEN_LOG: 'Maven 日志', COMMAND_RECORD: '命令记录', EXCLUSION_MANIFEST: '排除方法清单',
}
export interface EvidenceFile {
  id: string
  name: string
  kind: EvidenceFileKind
  size: number
  sha256: string
  modulePath?: string | null
}
export interface BuildModuleDeclaration {
  path: string
  projectName: string
  selected: boolean | null
}
export interface BuildModule extends BuildModuleDeclaration {
  state: 'SUCCESS' | 'FAILURE' | 'SKIPPED' | 'NOT_SELECTED' | 'UNKNOWN'
  counts: BuildCounts | null
  noTestsReported?: boolean
}
const moduleStates: Record<BuildModule['state'], string> = {
  SUCCESS: '构建成功', FAILURE: '构建失败', SKIPPED: '构建跳过', NOT_SELECTED: '声明未选中', UNKNOWN: '状态未知',
}
export function describeModule(module: BuildModule | null) {
  if (!module) return '未提供该模块'
  const c = module.counts
  const evidence = c ? `通过 ${c.passed}，断言失败 ${c.failures}，执行报错 ${c.errors}，跳过 ${c.skipped}` : '未取得模块 XML'
  const noTests = module.noTestsReported ? ' · Surefire 日志记录无测试' : ''
  return `${moduleStates[module.state]}${noTests} · ${evidence}`
}
export function evidenceFileName(file: EvidenceFile) {
  return file.modulePath ? `${file.modulePath}/${file.name}` : file.name
}
export interface CaseOccurrence {
  fileId: string
  ordinal: number
  status: CaseStatus
  type: string | null
  message: string | null
  stack: string | null
}
export interface BuildBatch {
  summary: BuildSummary
  metadata: BuildMetadata
  commandRecord: {
    command: string[]
    exitCode: number | null
    startedAt: string | null
    endedAt: string | null
    selector: string | null
    excludedClasses: string[]
  }
  log: { outcome: 'SUCCESS' | 'FAILURE' | null; counts: BuildCounts | null }
  excludedMethods: { className: string; methodName: string; source: string }[]
  files: EvidenceFile[]
  cases: (CaseOccurrence & { suite: string; className: string; name: string })[]
  warnings: string[]
  modules?: BuildModule[]
}
export interface BuildModuleFiles extends BuildModuleDeclaration { xmlFiles: File[] }
export interface ImportFiles {
  xmlFiles: File[]
  modules?: BuildModuleFiles[]
  mavenLog?: File
  commandRecord?: File
  exclusionManifest?: File
}

export const reviewPath = (reportId: string) => `/reports/${encodeURIComponent(reportId)}/review`
const buildsPath = (reportId: string) => reviewPath(reportId) + '/builds'
export async function listBuilds(reportId: string): Promise<BuildSummary[]> {
  return (await apiClient.get(buildsPath(reportId))).data.data
}
export async function getBuild(reportId: string, batchId: string): Promise<BuildBatch> {
  return (await apiClient.get(`${buildsPath(reportId)}/${encodeURIComponent(batchId)}`)).data.data
}
export async function importBuild(reportId: string, metadata: BuildMetadata & { requestId: string; role: BuildRole }, files: ImportFiles): Promise<BuildBatch> {
  const data = new FormData()
  const modules = files.modules?.map(({ path, projectName, selected }) => ({ path, projectName, selected }))
  data.append('metadata', JSON.stringify({ ...metadata, modules }))
  for (const file of files.xmlFiles) data.append('xmlFiles', file)
  files.modules?.forEach((module, index) => {
    for (const file of module.xmlFiles) data.append('moduleXml' + index, file)
  })
  for (const field of ['mavenLog', 'commandRecord', 'exclusionManifest'] as const) {
    const file = files[field]
    if (file) data.append(field, file)
  }
  return (await apiClient.post(buildsPath(reportId), data, { timeout: 60_000 })).data.data
}
export function buildFileUrl(reportId: string, batchId: string, fileId: string) {
  return apiClient.getUri({ url: `${buildsPath(reportId)}/${encodeURIComponent(batchId)}/files/${encodeURIComponent(fileId)}` })
}
export function displayTime(raw: string | null) {
  if (!raw) return '未知'
  const date = new Date(raw)
  return Number.isFinite(date.getTime()) ? date.toLocaleString('zh-CN', { hour12: false }) : raw
}
export function batchName(batch: BuildSummary) {
  return batch.label?.trim() || `标签未知 · 导入于 ${displayTime(batch.importedAt)}`
}
export function evidenceError(cause: unknown, fallback: string) {
  const error = cause as { response?: { status?: number; data?: { detail?: string; message?: string } } }
  if (error.response?.status === 413) return '本批材料过大，请核对文件数量与大小后重试。'
  return error.response?.data?.detail || error.response?.data?.message || fallback
}
