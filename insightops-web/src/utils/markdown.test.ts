import { describe, expect, it } from 'vitest'

import { renderSafeMarkdown } from './markdown'

describe('renderSafeMarkdown', () => {
  it('renders common model markdown', () => {
    const html = renderSafeMarkdown('**v2.0.0**\n\n- 功能一\n- `Tool Calling`\n\nhttps://github.com/spring-projects/spring-ai/releases/tag/v2.0.0')

    expect(html).toContain('<strong>v2.0.0</strong>')
    expect(html).toContain('<ul><li>功能一</li><li><code>Tool Calling</code></li></ul>')
    expect(html).toContain('href="https://github.com/spring-projects/spring-ai/releases/tag/v2.0.0"')
  })

  it('escapes raw html and refuses unsafe links', () => {
    const html = renderSafeMarkdown('<img src=x onerror=alert(1)> [危险](javascript:alert(1))')

    expect(html).toContain('&lt;img src=x onerror=alert(1)&gt;')
    expect(html).toContain('[危险](javascript:alert(1))')
    expect(html).not.toContain('<img')
    expect(html).not.toContain('href="javascript:')
  })

  it('renders non-release links as text rather than clickable evidence', () => {
    const html = renderSafeMarkdown(
      '升级说明（https://docs.example.com/upgrade#v2）已发布。',
    )

    expect(html).toContain('https://docs.example.com/upgrade#v2')
    expect(html).not.toContain('href=')
  })
})
