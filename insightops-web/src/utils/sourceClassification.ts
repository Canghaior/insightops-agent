function githubPath(value: string): string | null {
  try {
    const url = new URL(value)
    if (url.protocol !== 'https:' || url.hostname.toLowerCase() !== 'github.com') return null
    return url.pathname
  } catch {
    return null
  }
}

export function sourceHeading(sources: readonly string[]): string {
  const githubPaths = sources.map(githubPath)
  const hasRelease = githubPaths.some((path) => path?.includes('/releases/tag/'))
  const hasProjectEvent = githubPaths.some((path) => path != null
    && (path.includes('/issues/') || path.includes('/pull/') || path.includes('/security/advisories/')))
  const hasDocs = githubPaths.some((path) => path == null)
  if ((hasRelease || hasProjectEvent) && hasDocs) return 'GitHub 官方事件与知识库来源'
  return hasRelease || hasProjectEvent ? 'GitHub 官方来源' : '官方知识库来源'
}
