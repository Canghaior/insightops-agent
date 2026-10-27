import type { UpgradeInput } from '@/api/upgradeAssessments'

export function suggestResearchProfile(owner: string, name: string): string {
  const repo = `${owner}/${name}`.toLowerCase()
  if (['vuejs', 'vitejs'].includes(owner.toLowerCase()) || repo === 'facebook/react') return 'web_frontend'
  if (['spring-projects', 'langchain4j'].includes(owner.toLowerCase())) return 'java_backend'
  return 'general'
}
export function restoreUpgradeInput(raw: Record<string, unknown>): UpgradeInput {
  const text = (key: string) => typeof raw[key] === 'string' ? raw[key] as string : ''
  const strings = (key: string) => Array.isArray(raw[key]) ? raw[key].filter((value): value is string => typeof value === 'string') : []
  const profileId = text('profileId') || 'java_backend'
  const context = raw.context && typeof raw.context === 'object' && !Array.isArray(raw.context)
    ? Object.fromEntries(Object.entries(raw.context).filter((entry): entry is [string, string] => typeof entry[1] === 'string')) : {}
  if (profileId === 'java_backend') {
    if (text('javaVersion') && !('javaVersion' in context)) context.javaVersion = text('javaVersion')
    if (text('springBootVersion') && !('springBootVersion' in context)) context.springBootVersion = text('springBootVersion')
  }
  return { projectId: text('projectId'), currentVersion: text('currentVersion'), targetVersion: text('targetVersion'),
    businessProject: text('businessProject'), javaVersion: profileId === 'java_backend' ? context.javaVersion ?? '' : '',
    springBootVersion: profileId === 'java_backend' ? context.springBootVersion ?? '' : '',
    profileId, context, concerns: strings('concerns'), uploadIds: strings('uploadIds'), issueUrls: strings('issueUrls') }
}
