const escapeHtml = (value: string): string => value
  .replaceAll('&', '&amp;')
  .replaceAll('<', '&lt;')
  .replaceAll('>', '&gt;')
  .replaceAll('"', '&quot;')
  .replaceAll("'", '&#39;')

function trustedEvidenceUrl(value: string, sources: string[]): string | null {
  try {
    const url = new URL(value)
    if (!url.username && !url.password && ['https:', 'http:'].includes(url.protocol) && sources.includes(value)) return url.toString()
    const releasePath = /^\/[^/]+\/[^/]+\/releases\/tag\/[^/]+\/?$/
    return url.protocol === 'https:' && url.hostname === 'github.com' && releasePath.test(url.pathname)
      ? url.toString()
      : null
  } catch {
    return null
  }
}

function renderInline(value: string, sources: string[]): string {
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
      const href = link ? trustedEvidenceUrl(link[2], sources) : null
      result += href
        ? `<a href="${escapeHtml(href)}" target="_blank" rel="noreferrer">${escapeHtml(link![1])}</a>`
        : escapeHtml(raw)
    } else {
      const clean = raw.replace(/[)\]}>）,.;!?，。；！？）】》」』]+$/, '')
      const trailing = raw.slice(clean.length)
      const href = trustedEvidenceUrl(clean, sources)
      result += href
        ? `<a href="${escapeHtml(href)}" target="_blank" rel="noreferrer">${escapeHtml(clean)}</a>${escapeHtml(trailing)}`
        : escapeHtml(raw)
    }
    cursor = index + raw.length
  }
  return result + escapeHtml(value.slice(cursor))
}

export function renderSafeMarkdown(markdown: string, sources: string[] = []): string {
  const lines = markdown.replaceAll('\r\n', '\n').split('\n')
  const blocks: string[] = []
  let paragraph: string[] = []
  let listType: 'ul' | 'ol' | null = null
  let listItems: string[] = []
  let codeLines: string[] | null = null

  const flushParagraph = () => {
    if (paragraph.length) {
      blocks.push(`<p>${paragraph.map(value => renderInline(value, sources)).join('<br>')}</p>`)
      paragraph = []
    }
  }
  const flushList = () => {
    if (listType && listItems.length) {
      blocks.push(`<${listType}>${listItems.map(item => `<li>${renderInline(item, sources)}</li>`).join('')}</${listType}>`)
    }
    listType = null
    listItems = []
  }
  const flushText = () => {
    flushParagraph()
    flushList()
  }

  for (let index = 0; index < lines.length; index++) {
    const line = lines[index]
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
    if (line.trim().startsWith('|') && /^\s*\|?\s*:?-{3,}:?\s*\|[\s:|-]*$/.test(lines[index + 1] ?? '')) {
      flushText()
      const cells = (row: string) => row.trim().replace(/^\||\|$/g, '').split('|').map(value => value.trim())
      const headers = cells(line)
      const rows: string[] = []
      index += 2
      while (index < lines.length && lines[index].trim().startsWith('|')) {
        const values = cells(lines[index])
        if (values.length !== headers.length) break
        rows.push(`<tr>${values.map(value => `<td>${renderInline(value, sources)}</td>`).join('')}</tr>`)
        index++
      }
      index--
      blocks.push(`<div class="markdown-table-scroll"><table><thead><tr>${headers.map(value => `<th>${renderInline(value, sources)}</th>`).join('')}</tr></thead><tbody>${rows.join('')}</tbody></table></div>`)
      continue
    }

    const heading = line.match(/^(#{1,3})\s+(.+)$/)
    if (heading) {
      flushText()
      const level = heading[1].length
      blocks.push(`<h${level}>${renderInline(heading[2], sources)}</h${level}>`)
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
      blocks.push(`<blockquote>${renderInline(quote[1], sources)}</blockquote>`)
      continue
    }

    flushList()
    paragraph.push(line)
  }

  if (codeLines != null) blocks.push(`<pre><code>${escapeHtml(codeLines.join('\n'))}</code></pre>`)
  flushText()
  return blocks.join('')
}
