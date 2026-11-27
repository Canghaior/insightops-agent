<script setup lang="ts">
import { computed, ref } from 'vue'

const task = ref('完成一次真实项目的升级评估')
const startedAt = ref<number | null>(null), finishedAt = ref<number | null>(null)
const outcome = ref(''), evidenceChecked = ref(false), adoption = ref('undecided')
const baselineMinutes = ref(''), obstacles = ref(''), notice = ref('')
const elapsedMinutes = computed(() => startedAt.value !== null && finishedAt.value !== null
  ? Math.round((finishedAt.value - startedAt.value) / 600) / 100 : null)
function beginTrial() {
  if (startedAt.value !== null) return
  startedAt.value = Date.now()
  notice.value = '计时已开始。可以在新标签页完成研究，再回这里记录结果。请保留本页。'
}
function finishTrial() {
  if (startedAt.value === null || finishedAt.value !== null) return
  finishedAt.value = Date.now()
  notice.value = '计时已结束，请如实记录结果；未完成任务也有反馈价值。'
}
function downloadTrial() {
  if (elapsedMinutes.value === null || !outcome.value) return
  const baseline = Number(baselineMinutes.value)
  const record = {
    schemaVersion: 1, task: task.value, startedAt: new Date(startedAt.value!).toISOString(),
    finishedAt: new Date(finishedAt.value!).toISOString(), elapsedMinutes: elapsedMinutes.value,
    outcome: outcome.value, evidenceChecked: evidenceChecked.value, adoption: adoption.value,
    baselineMinutes: baselineMinutes.value.trim() && Number.isFinite(baseline) && baseline > 0 ? baseline : null,
    obstacles: obstacles.value.trim(),
    note: '用户主动填写的单次试用记录，不代表自动核验或产品总体表现。耗时包含等待和中途停留。',
  }
  const url = globalThis.URL.createObjectURL(new globalThis.Blob([JSON.stringify(record, null, 2)], { type: 'application/json;charset=utf-8' }))
  const anchor = globalThis.document.createElement('a')
  anchor.href = url; anchor.download = `insightops-trial-${new Date().toISOString().slice(0, 10)}.json`
  globalThis.document.body.appendChild(anchor); anchor.click(); anchor.remove()
  globalThis.setTimeout(() => globalThis.URL.revokeObjectURL(url), 30_000)
  notice.value = '试用记录已下载；需要反馈时可主动分享给产品维护者。'
}
</script>

<template>
  <section class="guide">
    <div class="section-heading"><span class="eyebrow">开始使用</span><h2>从工程问题到验证交接</h2><p>准备一项具体的改造任务，以及能说明当前实现的材料。</p></div>
    <ol class="guide-steps">
      <li class="panel"><h3>准备业务系统和基线</h3><p>选择或新建系统档案。在「工程基线」导入 POM、规范 ZIP，或选择本机模块及相关文件，核对预览后保存。</p><RouterLink to="/business-systems">打开业务系统 →</RouterLink></li>
      <li class="panel"><h3>选择评估问题</h3><p>金额、权限等内部规则可以使用系统决策评估。框架升级需要选择关注的框架、当前版本和目标版本。</p><RouterLink :to="{ path: '/business-systems', query: { intent: 'decision' } }">解决项目中的问题 →</RouterLink> <RouterLink to="/upgrade-assessment">评估框架升级 →</RouterLink></li>
      <li class="panel"><h3>核对依据并准备任务</h3><p>从运行记录保存报告，核对工程依据、外部资料与实际验证。选取一条建议，填写可改范围和验收条件，保存任务版本。</p><RouterLink to="/runs">查看研究记录 →</RouterLink></li>
      <li class="panel"><h3>执行并交接</h3><p>就绪任务可导出执行包。在已配置本地执行的环境，还能选择登记仓库试改。回看测试、补丁与限制，然后下载结果交接包。</p><RouterLink to="/reports">打开报告与验证 →</RouterLink></li>
    </ol>
    <div class="panel help-panel"><h3>遇到问题时</h3><p>内部问题缺工程依据时，检查本次选择的基线。升级缺目标版本资料时，核对该版本的 Release 或迁移指南。研究中断可在详情查看原因与恢复操作。</p></div>
    <details class="panel trial-panel">
      <summary>参与一次产品试用（选填）</summary>
      <p>用同一个真实问题，对照你平时阅读官方资料或使用其他助手的方式。记录能否独立完成、需要补充什么、报告是否有用。记录只保留在本页，刷新会清空；点击下载后可自行保存，不会自动发送。</p>
      <label>本次任务<select v-model="task" :disabled="startedAt !== null"><option>完成一次真实项目的升级评估</option><option>从一条新版本提醒评估业务影响</option><option>复用上次配置并保存新的研究报告</option></select></label>
      <div class="trial-actions"><button class="primary-button" :disabled="startedAt !== null" @click="beginTrial">开始计时</button><a href="/upgrade-assessment" target="_blank" rel="noopener">在新标签页开始研究</a><button class="secondary-button" :disabled="startedAt === null || finishedAt !== null" @click="finishTrial">结束本次试用</button></div>
      <p v-if="elapsedMinutes !== null">本次记录耗时：{{ elapsedMinutes }} 分钟（包括等待与中途停留）。</p>
      <fieldset :disabled="finishedAt === null">
        <legend>记录实际体验</legend>
        <label>完成情况<select v-model="outcome"><option value="">请选择</option><option value="independent">独立完成</option><option value="assisted">他人协助后完成</option><option value="incomplete">未能完成</option></select></label>
        <label class="trial-check"><input v-model="evidenceChecked" type="checkbox" /> 我已打开关键来源并核对版本及原文</label>
        <label>报告是否用于下一步工作<select v-model="adoption"><option value="undecided">尚未决定</option><option value="used">已经采用</option><option value="needs_revision">修改后才可采用</option><option value="not_useful">没有帮助</option></select></label>
        <label>平时用其他方式完成同一任务的耗时（分钟，选填）<input v-model="baselineMinutes" type="number" min="0.1" step="0.1" placeholder="仅填写实际测量值" /></label>
        <label>卡住的步骤或需要补充的内容（选填）<textarea v-model="obstacles" rows="3" maxlength="2000" placeholder="请勿填写密码、密钥或项目机密。" /></label>
        <button class="secondary-button" :disabled="!outcome" @click="downloadTrial">下载试用记录</button>
      </fieldset>
      <p v-if="notice" role="status">{{ notice }}</p>
    </details>
  </section>
</template>

<style scoped>
.guide { max-width: 1050px; }.guide p { color: var(--muted); line-height: 1.8; }
.guide .section-heading { display: block; }
.guide a { color: var(--accent); margin-right: 16px; }.guide-steps { display: grid; grid-template-columns: 1fr 1fr; gap: 16px; padding: 0; list-style: none; }
.guide .panel { padding: 22px; }.guide h3 { margin-top: 0; }.help-panel,.trial-panel { margin-top: 20px; }
.trial-panel summary { cursor: pointer; font-weight: 700; }.trial-panel label { display: grid; gap: 8px; margin: 14px 0; }
.trial-panel select,.trial-panel input:not([type=checkbox]),.trial-panel textarea { background: var(--panel-soft); color: inherit; border: 1px solid var(--line); border-radius: 8px; padding: 10px; width: 100%; }
.trial-panel .trial-check { display: flex; align-items: center; }.trial-actions { display: flex; gap: 12px; flex-wrap: wrap; align-items: center; margin: 16px 0; }
.trial-panel fieldset { border: 1px solid var(--line); border-radius: 10px; padding: 16px; }.trial-panel fieldset:disabled { opacity: .55; }
@media (max-width: 740px) { .guide-steps { grid-template-columns: 1fr; } }
</style>
