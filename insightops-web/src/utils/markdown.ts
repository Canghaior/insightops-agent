const escapeHtml = (value: string): string => value
  .replaceAll('&', '&amp;')
  .replaceAll('<', '&lt;')
  .replaceAll('>', '&gt;')
  .replaceAll('"', '&quot;')
  .replaceAll("'", '&#39;')

function trustedReleaseUrl(value: string): string | null {
  try {
    const url = new URL(value)
    const releasePath = /^\/[^/]+\/[^/]+\/releases\/tag\/[^/]+\/?$/
    return url.protocol === 'https:' && url.hostname === 'github.com' && releasePath.test(url.pathname)
      ? url.toString()
      : null
  } catch {
    return null
  }
}

function renderInline(value: string): string {
  const token = /(`[^`\n]+`|\*\*[^*\n]+\*\*|\[[^\]\n]+\]\([^)\n]+\)|https?:\/\/[^\s<>(){},;!?，。；！？、（）【】《》「」『』]+)/g
  let result = ''
  let cursor = 0

  for (const match of value.matchAll(token)) {
    const index = match.index ?? 0
    result += escapeHtml(value.slice(cursor, index))
    const raw = match[0]

    if (raw.startsWith('`')) {
      result += `<code>${escapeHtml(raw.slice(1, -1))}</code>`
    } else if (raw.startsWith('**')) {
      result += `<strong>${escapeHtml(raw.slice(2, -2))}</strong>`
    } else if (raw.startsWith('[')) {
      const link = raw.match(/^\[([^\]]+)]\(([^)]+)\)$/)
      const href = link ? trustedReleaseUrl(link[2]) : null
      result += href
        ? `<a href="${escapeHtml(href)}" target="_blank" rel="noreferrer">${escapeHtml(link![1])}</a>`
        : escapeHtml(raw)
    } else {
      const clean = raw.replace(/[)\]}>）,.;!?，。；！？）】》」』]+$/, '')
      const trailing = raw.slice(clean.length)
      const href = trustedReleaseUrl(clean)
      result += href
        ? `<a href="${escapeHtml(href)}" target="_blank" rel="noreferrer">${escapeHtml(clean)}</a>${escapeHtml(trailing)}`
        : escapeHtml(raw)
    }
    cursor = index + raw.length
  }
  return result + escapeHtml(value.slice(cursor))
}

export function renderSafeMarkdown(markdown: string): string {
  const lines = markdown.replaceAll('\r\n', '\n').split('\n')
  const blocks: string[] = []
  let paragraph: string[] = []
  let listType: 'ul' | 'ol' | null = null
  let listItems: string[] = []
  let codeLines: string[] | null = null

  const flushParagraph = () => {
    if (paragraph.length) {
      blocks.push(`<p>${paragraph.map(renderInline).join('<br>')}</p>`)
      paragraph = []
    }
  }
  const flushList = () => {
    if (listType && listItems.length) {
      blocks.push(`<${listType}>${listItems.map(item => `<li>${renderInline(item)}</li>`).join('')}</${listType}>`)
    }
    listType = null
    listItems = []
  }
  const flushText = () => {
    flushParagraph()
    flushList()
  }

  for (const line of lines) {
    if (line.trimStart().startsWith('```')) {
      flushText()
      if (codeLines == null) {
        codeLines = []
      } else {
        blocks.push(`<pre><code>${escapeHtml(codeLines.join('\n'))}</code></pre>`)
        codeLines = null
      }
      continue
    }
    if (codeLines != null) {
      codeLines.push(line)
      continue
    }
    if (!line.trim()) {
      flushText()
      continue
    }

    const heading = line.match(/^(#{1,3})\s+(.+)$/)
    if (heading) {
      flushText()
      const level = heading[1].length
      blocks.push(`<h${level}>${renderInline(heading[2])}</h${level}>`)
      continue
    }

    const unordered = line.match(/^\s*[-*]\s+(.+)$/)
    const ordered = line.match(/^\s*\d+[.)]\s+(.+)$/)
    if (unordered || ordered) {
      flushParagraph()
      const nextType = unordered ? 'ul' : 'ol'
      if (listType && listType !== nextType) flushList()
      listType = nextType
      listItems.push((unordered ?? ordered)![1])
      continue
    }

    const quote = line.match(/^>\s?(.*)$/)
    if (quote) {
      flushText()
      blocks.push(`<blockquote>${renderInline(quote[1])}</blockquote>`)
      continue
    }

    flushList()
    paragraph.push(line)
  }

  if (codeLines != null) blocks.push(`<pre><code>${escapeHtml(codeLines.join('\n'))}</code></pre>`)
  flushText()
  return blocks.join('')
}
