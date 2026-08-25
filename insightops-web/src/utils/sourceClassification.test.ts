import { describe, expect, it } from 'vitest'

import { sourceHeading } from './sourceClassification'

describe('sourceHeading', () => {
  it('classifies exact HTTPS GitHub release and project event URLs', () => {
    expect(sourceHeading(['https://github.com/spring-projects/spring-ai/releases/tag/v2.0.0']))
      .toBe('GitHub 官方来源')
    expect(sourceHeading(['https://github.com/langchain4j/langchain4j/security/advisories/GHSA-test']))
      .toBe('GitHub 官方来源')
  })

  it('labels a GitHub event combined with documentation as mixed evidence', () => {
    expect(sourceHeading([
      'https://github.com/spring-projects/spring-ai/issues/123',
      'https://docs.spring.io/spring-ai/reference/',
    ])).toBe('GitHub 官方事件与知识库来源')
  })

  it('does not trust attacker hosts that merely contain github.com in the URL', () => {
    expect(sourceHeading(['https://attacker.example/github.com/org/repo/releases/tag/v1']))
      .toBe('官方知识库来源')
    expect(sourceHeading(['https://github.com.attacker.example/org/repo/pull/1']))
      .toBe('官方知识库来源')
  })

  it('requires HTTPS and treats invalid sources as documentation', () => {
    expect(sourceHeading(['http://github.com/org/repo/issues/1', 'not-a-url']))
      .toBe('官方知识库来源')
  })
})
