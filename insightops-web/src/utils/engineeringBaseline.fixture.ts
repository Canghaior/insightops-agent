import type { EngineeringBaseline } from '@/api/engineeringBaselines'

export function baselineFixture(version: number, systemId = 'orders'): EngineeringBaseline {
  const label = '合成测试基线 ' + version
  return { baseline: { id: 'b' + version, version, label, systemId, savedAt: '2026-01-01T00:00:00Z' },
    content: { label, note: '', expectedJava: '', originalName: 'pom.xml', originalSha256: 'a'.repeat(64),
      evidence: { sourceLabel: 'fixture', files: [], facts: [], gaps: ['未提供 Maven 解析记录'] },
      profileAtImport: { revision: 1, technologyStack: 'user input' }, conflicts: [] } }
}

