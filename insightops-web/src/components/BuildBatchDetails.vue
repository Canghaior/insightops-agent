<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { buildFileUrl, buildRoles, buildToolNames, caseStatuses, describeModule, displayTime, evidenceFileName, fileKinds, type BuildBatch, type CaseStatus } from '@/api/buildEvidence'
import BuildCaseResult from './BuildCaseResult.vue'

const props = defineProps<{ reportId: string; batch: BuildBatch }>()
const page = ref(0)
const filter = ref<CaseStatus | ''>('')
const search = ref('')
const size = 15
const fileNames = computed(() => new Map(props.batch.files.map(file => [file.id, evidenceFileName(file)])))
const cases = computed(() => {
  const query = search.value.trim().toLowerCase()
  return props.batch.cases.filter(row => (!filter.value || row.status === filter.value)
    && (!query || `${row.suite} ${row.className} ${row.name}`.toLowerCase().includes(query)))
})
watch([filter, search, () => props.batch.summary.id], () => { page.value = 0 })
</script>

<template>
  <section class="batch-detail" :aria-label="buildRoles[batch.summary.role] + '材料'">
    <header><h5>{{ buildRoles[batch.summary.role] }}材料</h5><span>已保存并解析 · {{ batch.files.length }} 份原件</span></header>
    <p>从 XML 读到 {{ batch.cases.length }} 项，{{ batch.summary.counts.passed }} 通过，{{ batch.summary.counts.failures }} 断言失败，{{ batch.summary.counts.errors }} 执行报错，{{ batch.summary.counts.skipped }} 跳过。</p>
    <details v-if="batch.modules?.length" open>
      <summary>Maven 模块（{{ batch.modules.length }}）</summary>
      <ul>
        <li v-for="module in batch.modules" :key="module.path"><code>{{ module.path }}</code> · {{ module.projectName }} · {{ describeModule(module) }}</li>
      </ul>
    </details>
    <ul v-if="batch.warnings.length" class="warnings"><li v-for="warning in batch.warnings" :key="warning">{{ warning }}</li></ul>
    <details open>
      <summary>标签、执行环境与命令范围</summary>
      <strong>用户填写</strong>
      <dl>
        <dt>构建工具</dt><dd>{{ buildToolNames[batch.buildTool ?? 'MAVEN'] }}</dd>
        <dt>构建标签或提交号</dt><dd>{{ batch.metadata.label || '未知' }}</dd>
        <dt>材料来源</dt><dd>{{ batch.metadata.sourceNote || '未知' }}</dd>
        <dt>执行环境</dt><dd>{{ batch.metadata.environment || '未知' }}</dd>
        <dt>原测试执行者</dt><dd>{{ batch.metadata.executedBy || '未知' }}</dd>
        <dt>原测试执行时间</dt><dd>{{ displayTime(batch.metadata.executedAt) }}</dd>
        <dt>构建退出码</dt><dd>{{ batch.metadata.exitCode ?? '未知' }}</dd>
      </dl>
      <strong>从命令记录、日志与清单读取</strong>
      <dl>
        <dt>命令及参数</dt><dd><ol v-if="batch.commandRecord.command.length" class="arguments"><li v-for="(arg, ix) in batch.commandRecord.command" :key="ix"><code>{{ arg }}</code></li></ol><span v-else>未知</span></dd>
        <dt>记录起止时间</dt><dd>{{ displayTime(batch.commandRecord.startedAt) }} 至 {{ displayTime(batch.commandRecord.endedAt) }}</dd>
        <dt>记录的退出码</dt><dd>{{ batch.commandRecord.exitCode ?? '未知' }}</dd>
        <dt>构建日志结论</dt><dd>{{ batch.log.outcome === 'SUCCESS' ? '构建成功' : batch.log.outcome === 'FAILURE' ? '构建失败' : '未知' }}</dd>
        <template v-if="batch.log.counts"><dt>日志测试总结</dt><dd>通过 {{ batch.log.counts.passed }}，断言失败 {{ batch.log.counts.failures }}，执行报错 {{ batch.log.counts.errors }}，跳过 {{ batch.log.counts.skipped }}</dd></template>
        <dt>测试选择参数</dt><dd>{{ batch.commandRecord.selector || '未知' }}</dd>
        <template v-if="batch.buildTool !== 'GRADLE'">
          <dt>命令明确排除的类</dt><dd>{{ batch.commandRecord.excludedClasses.length ? batch.commandRecord.excludedClasses.join('、') : '未从命令确定' }}</dd>
          <dt>排除的方法</dt><dd v-if="batch.excludedMethods.length">清单列明 {{ batch.excludedMethods.length }} 个方法<ul><li v-for="item in batch.excludedMethods" :key="JSON.stringify(item)">{{ item.className }}.{{ item.methodName }} <small>{{ item.source }}</small></li></ul></dd><dd v-else>具体方法数量未知</dd>
        </template>
      </dl>
      <p>本次导入时间为 {{ displayTime(batch.summary.importedAt) }}。测试范围是否完整、原件是否来自所填提交，仍需结合来源核对。</p>
    </details>
    <details>
      <summary>原件清单与下载（{{ batch.files.length }}）</summary>
      <p>SHA-256 可用于核对下载文件与已保存原件是否相同。</p>
      <ul class="originals">
        <li v-for="file in batch.files" :key="file.id">
          <a :href="buildFileUrl(reportId, batch.summary.id, file.id)" download>{{ evidenceFileName(file) }}</a>
          <small>{{ fileKinds[file.kind] }} · {{ file.size.toLocaleString('zh-CN') }} 字节</small><code>SHA-256 {{ file.sha256 }}</code>
        </li>
      </ul>
    </details>
    <details>
      <summary>查看 XML 中的方法（{{ batch.cases.length }}）</summary>
      <div class="filters">
        <label>方法或测试类<input v-model="search" type="search" placeholder="按名称查找" /></label>
        <label>执行结果<select v-model="filter"><option value="">全部</option><option v-for="(label, key) in caseStatuses" :key="key" :value="key">{{ label }}</option></select></label>
      </div>
      <p v-if="!cases.length">没有符合当前条件的方法。</p>
      <ol v-else class="case-list">
        <li v-for="row in cases.slice(page * size, (page + 1) * size)" :key="row.fileId + '/' + row.ordinal">
          <b>{{ row.name }}</b><small>{{ row.className }}</small>
          <BuildCaseResult :report-id="reportId" :batch-id="batch.summary.id" :result="row" :file-name="fileNames.get(row.fileId)" />
        </li>
      </ol>
      <div v-if="cases.length > size" class="paging">
        <button type="button" class="text-button" :disabled="page === 0" @click="page--">上一页方法</button>
        <small>第 {{ page + 1 }} / {{ Math.ceil(cases.length / size) }} 页</small>
        <button type="button" class="text-button" :disabled="(page + 1) * size >= cases.length" @click="page++">下一页方法</button>
      </div>
    </details>
  </section>
</template>

<style scoped>
.batch-detail { border-inline-start: 2px solid var(--accent); padding-inline-start: .8rem; margin-block: .7rem; min-width: 0 }
header, .paging, .filters { display: flex; align-items: center; flex-wrap: wrap; gap: .5rem .85rem }
h5 { font-size: 1rem; margin: .2rem 0 }
header span, small, dt { color: var(--muted); font-size: .85rem }
p, dl { margin: .55rem 0 }
details { border-top: 1px solid var(--line); padding: .45rem 0 }
summary { cursor: pointer; font-weight: 600 }
details > strong { display: block; margin-top: .55rem }
dl { display: grid; grid-template-columns: minmax(5em, auto) minmax(0, 1fr); gap: .3rem .8rem }
dd { margin: 0; white-space: pre-wrap; overflow-wrap: anywhere }
dd ul { margin: .2rem 0; padding-inline-start: 1.2rem }
.arguments, .originals, .case-list { padding: 0; list-style: none; margin: .2rem 0 }
.arguments { display: flex; gap: .25rem .4rem; flex-wrap: wrap }
.arguments li { background: var(--panel-soft); padding: .05rem .25rem }
.originals li, .case-list li { display: grid; gap: .2rem; padding: .45rem 0; border-bottom: 1px solid var(--line); overflow-wrap: anywhere }
.originals code { font-size: .78rem }
.filters { margin-top: .55rem }
.filters label { display: grid; gap: .15rem }
.warnings { padding-inline-start: 1.3rem; color: #ffd19c }
</style>
