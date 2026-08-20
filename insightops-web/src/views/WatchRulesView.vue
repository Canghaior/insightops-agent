<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { listProjects, type ProjectWatch } from '@/api/projects'
import {
  createWatchRule, deleteWatchRule, EVENT_TYPES, listWatchRules, updateWatchRule,
  type EventType, type WatchRule, type WatchRuleCommand,
} from '@/api/watchRules'

const rules=ref<WatchRule[]>([])
const projects=ref<ProjectWatch[]>([])
const loading=ref(false)
const saving=ref(false)
const error=ref('')
const editingId=ref('')
const form=reactive({
  name:'',projectId:'',keywords:'',excludedKeywords:'',eventTypes:[] as EventType[],
  minimumImportance:1,immediateNotification:true,includeInDigest:true,enabled:true,
})
const watchedProjects=computed(()=>projects.value.filter(project=>project.enabled))
const labels:Record<EventType,string>={
  GITHUB_RELEASE:'Release',GITHUB_ISSUE:'Issue',GITHUB_PULL_REQUEST:'Pull Request',
  GITHUB_SECURITY_ADVISORY:'安全公告',
}

function words(value:string){return value.split(/[,，\n]/).map(item=>item.trim()).filter(Boolean)}
function command():WatchRuleCommand{return{
  name:form.name.trim(),projectId:form.projectId||null,keywords:words(form.keywords),
  excludedKeywords:words(form.excludedKeywords),eventTypes:[...form.eventTypes],
  minimumImportance:Number(form.minimumImportance),immediateNotification:form.immediateNotification,
  includeInDigest:form.includeInDigest,enabled:form.enabled,
}}
function reset(){editingId.value='';Object.assign(form,{name:'',projectId:'',keywords:'',excludedKeywords:'',eventTypes:[],minimumImportance:1,immediateNotification:true,includeInDigest:true,enabled:true})}
function edit(rule:WatchRule){editingId.value=rule.id;Object.assign(form,{name:rule.name,projectId:rule.projectId??'',keywords:rule.keywords.join(', '),excludedKeywords:rule.excludedKeywords.join(', '),eventTypes:[...rule.eventTypes],minimumImportance:rule.minimumImportance,immediateNotification:rule.immediateNotification,includeInDigest:rule.includeInDigest,enabled:rule.enabled});globalThis.scrollTo({top:0,behavior:'smooth'})}
async function load(){loading.value=true;error.value='';try{[rules.value,projects.value]=await Promise.all([listWatchRules(),listProjects()])}catch{error.value='关注规则加载失败，请稍后重试。'}finally{loading.value=false}}
async function save(){if(!form.name.trim()){error.value='请填写规则名称。';return}if(!form.projectId&&!words(form.keywords).length&&!form.eventTypes.length){error.value='至少选择项目、关键词或事件类型之一。';return}saving.value=true;error.value='';try{if(editingId.value)await updateWatchRule(editingId.value,command());else await createWatchRule(command());reset();await load()}catch{error.value='规则保存失败，请检查项目和匹配条件。'}finally{saving.value=false}}
async function remove(rule:WatchRule){if(!globalThis.confirm(`删除关注规则“${rule.name}”？`))return;await deleteWatchRule(rule.id);await load()}
onMounted(load)
</script>

<template>
  <section>
    <div class="section-heading"><div><span class="eyebrow">主动技术情报</span><h2>关注规则</h2><p>新事件命中规则后生成一次去重站内通知。</p></div></div>
    <div class="panel rule-editor">
      <div class="settings-grid">
        <label>规则名称<input v-model="form.name" maxlength="128" placeholder="例如：高风险安全更新" /></label>
        <label>关注项目<select v-model="form.projectId"><option value="">全部已关注项目</option><option v-for="project in watchedProjects" :key="project.id" :value="project.id">{{ project.owner }}/{{ project.name }}</option></select></label>
        <label>包含关键词<input v-model="form.keywords" placeholder="embedding, breaking change" /></label>
        <label>排除关键词<input v-model="form.excludedKeywords" placeholder="documentation, typo" /></label>
        <label>最低重要度<select v-model.number="form.minimumImportance"><option v-for="level in 5" :key="level" :value="level">{{ level }} / 5</option></select></label>
      </div>
      <div class="rule-event-types">
        <strong>事件类型</strong>
        <label v-for="type in EVENT_TYPES" :key="type" class="check-label"><input v-model="form.eventTypes" type="checkbox" :value="type" /> {{ labels[type] }}</label>
      </div>
      <div class="rule-options"><label class="check-label"><input v-model="form.immediateNotification" type="checkbox" /> 即时站内通知</label><label class="check-label"><input v-model="form.includeInDigest" type="checkbox" /> 加入摘要</label><label class="check-label"><input v-model="form.enabled" type="checkbox" /> 启用规则</label></div>
      <div class="form-actions"><button class="send-button" :disabled="saving" @click="save">{{ saving ? '保存中…' : editingId ? '更新规则' : '创建规则' }}</button><button v-if="editingId" class="secondary-button" @click="reset">取消编辑</button></div>
    </div>
    <p v-if="error" class="stream-error">{{ error }}</p><p v-if="loading" class="run-loading">正在加载规则…</p>
    <div v-else class="update-list">
      <article v-for="rule in rules" :key="rule.id" class="update-card">
        <header><div><span class="eyebrow">{{ rule.projectName || '全部项目' }}</span><h3>{{ rule.name }}</h3></div><div class="update-badges"><i v-if="rule.enabled">已启用</i><code>命中 {{ rule.matchCount }}</code></div></header>
        <p>包含：{{ rule.keywords.join('、') || '不限关键词' }}<br />排除：{{ rule.excludedKeywords.join('、') || '无' }}</p>
        <div class="tag-row"><span v-for="type in rule.eventTypes" :key="type">{{ labels[type] }}</span><span>重要度 ≥ {{ rule.minimumImportance }}</span></div>
        <footer><span>{{ rule.immediateNotification ? '即时通知' : '不即时通知' }} · {{ rule.includeInDigest ? '加入摘要' : '不加入摘要' }}</span><div><button class="secondary-button" @click="edit(rule)">编辑</button><button class="text-button" @click="remove(rule)">删除</button></div></footer>
      </article>
      <div v-if="!rules.length" class="conversation-empty"><strong>还没有关注规则</strong><p>创建后，新采集的项目事件会自动匹配并通知。</p></div>
    </div>
  </section>
</template>
