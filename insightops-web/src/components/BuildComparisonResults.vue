<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { batchName, describeModule, displayTime, evidenceFileName, type BuildBatch } from '@/api/buildEvidence'
import { testChanges, type BuildComparison, type ComparisonCounts, type TestChange } from '@/api/buildComparison'
import BuildCaseResult from './BuildCaseResult.vue'

const props = defineProps<{ reportId: string; comparison: BuildComparison; baseline: BuildBatch; candidate: BuildBatch }>()
const sides = computed(() => [
  { key: 'baseline' as const, countKey: 'baselineCounts' as const, title: '基线', batch: props.baseline, counts: props.comparison.baselineCounts },
  { key: 'candidate' as const, countKey: 'candidateCounts' as const, title: '候选', batch: props.candidate, counts: props.comparison.candidateCounts },
])
const rows: { key: keyof ComparisonCounts; title: string }[] = [
  { key: 'total', title: 'XML 测试项' }, { key: 'passed', title: '通过' }, { key: 'failure', title: '断言失败' },
  { key: 'error', title: '执行报错' }, { key: 'skipped', title: '跳过' },
]
const filter = ref<'REVIEW' | 'ALL' | TestChange>('REVIEW')
const search = ref('')
const page = ref(0)
const size = 12
watch([filter, search], () => { page.value = 0 })
const filtered = computed(() => {
  const query = search.value.trim().toLowerCase()
  return props.comparison.groups.filter(group => {
    const needsReview = group.change !== 'UNCHANGED' || group.baselineCounts.error + group.baselineCounts.failure
      + group.candidateCounts.error + group.candidateCounts.failure > 0
    const visible = filter.value === 'ALL' || (filter.value === 'REVIEW' ? needsReview : filter.value === group.change)
    return visible && (!query || `${group.suite} ${group.className} ${group.name}`.toLowerCase().includes(query))
  })
})
const fileNames = computed(() => new Map([...props.baseline.files, ...props.candidate.files].map(file => [file.id, evidenceFileName(file)])))
</script>

<template>
  <section class="comparison" aria-label="构建对照结果">
    <h4>构建对照结果</h4>
    <p class="scope" :class="{ warn: comparison.scope === 'DIFFERENT_OBSERVED' }">
      {{ comparison.scope === 'SAME_OBSERVED'
        ? '两批 XML 中上报的测试身份与出现次数一致。'
        : comparison.modules?.length
          ? '两侧模块选择或 XML 测试范围有差异。请核对分模块结果。'
          : '两批 XML 的测试集合或出现次数不同。请逐项核对单侧测试与重复项。' }}
    </p>
    <div class="summary" aria-label="产品解析摘要">
      <strong>产品解析摘要</strong><p>{{ comparison.objectiveSummary }}</p>
    </div>
    <div v-if="comparison.modules?.length" class="table-wrap">
      <table aria-label="Maven 模块对照">
        <thead><tr><th scope="col">Maven 模块</th><th scope="col">基线</th><th scope="col">候选</th></tr></thead>
        <tbody>
          <tr v-for="module in comparison.modules" :key="module.path">
            <th scope="row"><code>{{ module.path }}</code></th><td>{{ describeModule(module.baseline) }}</td><td>{{ describeModule(module.candidate) }}</td>
          </tr>
        </tbody>
      </table>
    </div>
    <div class="table-wrap">
      <table aria-label="构建信息和测试数量">
        <thead><tr><th scope="col">对照项</th><th v-for="side in sides" :key="side.key" scope="col">{{ side.title }}构建</th></tr></thead>
        <tbody>
          <tr><th scope="row">标签（用户填写）</th><td v-for="side in sides" :key="side.key">{{ batchName(side.batch.summary) }}</td></tr>
          <tr><th scope="row">来源（用户填写）</th><td v-for="side in sides" :key="side.key">{{ side.batch.metadata.sourceNote || '未知' }}</td></tr>
          <tr><th scope="row">环境（用户填写）</th><td v-for="side in sides" :key="side.key">{{ side.batch.metadata.environment || '未知' }}</td></tr>
          <tr><th scope="row">执行者（用户填写）</th><td v-for="side in sides" :key="side.key">{{ side.batch.metadata.executedBy || '未知' }}</td></tr>
          <tr><th scope="row">原测试时间（用户填写）</th><td v-for="side in sides" :key="side.key">{{ displayTime(side.batch.metadata.executedAt) }}</td></tr>
          <tr><th scope="row">执行起止时间（命令记录）</th><td v-for="side in sides" :key="side.key">{{ displayTime(side.batch.commandRecord.startedAt) }} 至 {{ displayTime(side.batch.commandRecord.endedAt) }}</td></tr>
          <tr><th scope="row">选择参数（命令）</th><td v-for="side in sides" :key="side.key">{{ side.batch.commandRecord.selector || '未知' }}</td></tr>
          <tr><th scope="row">命令明确排除</th><td v-for="side in sides" :key="side.key">{{ side.batch.commandRecord.excludedClasses.join('、') || '未从命令确定' }}<small>{{ side.batch.excludedMethods.length ? '独立清单列明 ' + side.batch.excludedMethods.length + ' 个方法' : '具体方法数量未知' }}</small></td></tr>
          <tr><th scope="row">退出码（命令记录）</th><td v-for="side in sides" :key="side.key">{{ side.batch.commandRecord.exitCode ?? '未知' }}</td></tr>
          <tr><th scope="row">退出码（用户填写）</th><td v-for="side in sides" :key="side.key">{{ side.batch.metadata.exitCode ?? '未知' }}</td></tr>
          <tr><th scope="row">Maven 日志结论</th><td v-for="side in sides" :key="side.key">{{ side.batch.log.outcome === 'SUCCESS' ? '构建成功' : side.batch.log.outcome === 'FAILURE' ? '构建失败' : '未知' }}</td></tr>
          <tr><th scope="row">导入时间</th><td v-for="side in sides" :key="side.key">{{ displayTime(side.batch.summary.importedAt) }}</td></tr>
          <tr v-for="row in rows" :key="row.key"><th scope="row">{{ row.title }}</th><td v-for="side in sides" :key="side.key">{{ side.counts[row.key] }}</td></tr>
        </tbody>
      </table>
    </div>
    <p v-if="comparison.scope === 'DIFFERENT_OBSERVED'" class="subtle">数量接近或相等仍可能是不同测试。单侧缺席的测试状态未知。</p>
    <details v-if="comparison.limitations.length" open>
      <summary>解释范围</summary><ul><li v-for="note in comparison.limitations" :key="note">{{ note }}</li></ul>
    </details>
    <h5>逐项对照与异常</h5>
    <div class="filters">
      <label>显示范围<select v-model="filter">
        <option value="REVIEW">需要核对的项</option><option value="ALL">全部测试</option>
        <option v-for="(label, key) in testChanges" :key="key" :value="key">{{ label }}</option>
      </select></label>
      <label>查找测试<input v-model="search" type="search" placeholder="输入方法、类或套件名称" /></label>
    </div>
    <p class="subtle">当前显示 {{ filtered.length }} 组测试身份，共 {{ comparison.groups.length }} 组。</p>
    <p v-if="!filtered.length">当前条件下没有测试。<button type="button" class="text-button" @click="filter = 'ALL'; search = ''">查看全部测试</button></p>
    <article v-for="group in filtered.slice(page * size, (page + 1) * size)" :key="JSON.stringify([group.modulePath, group.suite, group.className, group.name])" class="test-result">
      <header><b>{{ group.name }}</b><span>{{ testChanges[group.change] }}</span></header>
      <small v-if="group.modulePath">模块 {{ group.modulePath }}</small>
      <small>{{ group.className }}</small><small v-if="group.suite !== group.className">套件 {{ group.suite }}</small>
      <p v-if="group.change === 'AMBIGUOUS'">同一身份有多条结果，无法可靠地逐条配对。下列结果按各自原件位置展示。</p>
      <div class="sides">
        <div v-for="side in sides" :key="side.key">
          <strong>{{ side.title }} · {{ group[side.key].length }} 次</strong>
          <small v-if="group.change === 'AMBIGUOUS'">{{ group[side.countKey].passed }} 通过，{{ group[side.countKey].failure }} 断言失败，{{ group[side.countKey].error }} 执行报错，{{ group[side.countKey].skipped }} 跳过</small>
          <BuildCaseResult
            v-for="result in group[side.key]" :key="result.fileId + '/' + result.ordinal"
            :report-id="reportId" :batch-id="side.batch.summary.id" :result="result" :file-name="fileNames.get(result.fileId)"
          />
          <span v-if="!group[side.key].length" class="subtle">本侧 XML 未出现，状态未知</span>
        </div>
      </div>
    </article>
    <div v-if="filtered.length > size" class="paging">
      <button type="button" class="text-button" :disabled="page === 0" @click="page--">上一页对照</button>
      <small>第 {{ page + 1 }} / {{ Math.ceil(filtered.length / size) }} 页</small>
      <button type="button" class="text-button" :disabled="(page + 1) * size >= filtered.length" @click="page++">下一页对照</button>
    </div>
  </section>
</template>

<style scoped>
.comparison { border-top: 1px solid var(--line); margin-top: .85rem; padding-top: .6rem; min-width: 0 }
h4, h5 { margin: .55rem 0; font-size: 1rem }
.scope, .summary { padding: .55rem .7rem; background: var(--panel-soft); border-radius: .4rem }
.warn { border-inline-start: 2px solid #e1ab65 }
.summary p { margin: .2rem 0; white-space: pre-wrap }
.table-wrap { overflow-x: auto; margin: .75rem 0 }
table { width: 100%; table-layout: fixed; border-collapse: collapse; font-size: .9rem }
th, td { border-bottom: 1px solid var(--line); text-align: start; padding: .35rem; overflow-wrap: anywhere; vertical-align: top }
th:first-child { width: 29% }
tbody th { font-weight: 500; color: var(--muted) }
.filters, .paging, .test-result header { display: flex; gap: .45rem .75rem; align-items: center; flex-wrap: wrap }
.filters label { display: grid; gap: .2rem; flex: 1; min-width: min(10rem, 100%) }
select, input { padding: .4rem; min-width: 0; width: 100% }
.test-result { border: 1px solid var(--line); border-radius: .4rem; padding: .65rem; margin: .4rem 0; overflow-wrap: anywhere }
.test-result header { justify-content: space-between }
.test-result header span { color: var(--accent); font-size: .85rem }
small { display: block; color: var(--muted) }
.sides { display: grid; grid-template-columns: repeat(auto-fit, minmax(min(12rem,100%), 1fr)); gap: .6rem; margin-top: .5rem }
.sides > div { min-width: 0 }
.sides strong { display: block; font-size: .85rem; margin: .2rem 0 }
summary { cursor: pointer }
details ul { padding-inline-start: 1.2rem; margin: .4rem 0 }
</style>
