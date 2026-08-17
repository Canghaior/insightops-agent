<script setup lang="ts">
import { onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { getIntelligence, listIntelligence, type AnalysisDetail, type AnalysisPage } from '@/api/intelligence'
import { listProjects, type ProjectWatch } from '@/api/projects'

const route=useRoute(), router=useRouter()
const page=ref<AnalysisPage>({items:[],page:0,size:20,total:0})
const projects=ref<ProjectWatch[]>([]), projectId=ref(''), risk=ref(''), loading=ref(false), error=ref('')
const detail=ref<AnalysisDetail|null>(null)

async function load(target=0){loading.value=true;error.value='';try{page.value=await listIntelligence({page:target,size:20,projectId:projectId.value||undefined,riskLevel:risk.value||undefined})}catch{error.value='情报列表加载失败'}finally{loading.value=false}}
async function open(id:string){await router.push({name:'intelligence-detail',params:{analysisId:id}})}
async function loadDetail(){const id=String(route.params.analysisId??'');if(!id){detail.value=null;return}try{detail.value=await getIntelligence(id)}catch{error.value='情报详情加载失败'}}
async function research(value:AnalysisDetail){const s=value.summary;await router.push({name:'chat',query:{question:`请基于官方 Release 证据深入研究 ${s.projectName} ${s.versionTag}，核实这份情报的风险、升级价值和推荐行动。`}})}
function label(value:string|null){return ({LOW:'低风险',MEDIUM:'中风险',HIGH:'高风险',WATCH:'继续观察',TRY:'建议试用',UPGRADE:'建议升级'} as Record<string,string>)[value??'']??value??'-'}
onMounted(async()=>{projects.value=await listProjects();await load();await loadDetail()})
watch(()=>route.params.analysisId,loadDetail)
</script>

<template>
  <section>
    <div class="section-heading"><div><span class="eyebrow">DeepSeek 结构化分析</span><h2>技术情报</h2></div><span class="subtle">事实与模型判断分开展示</span></div>
    <div class="panel update-controls"><label>项目<select v-model="projectId" @change="load(0)"><option value="">全部</option><option v-for="p in projects.filter(x=>x.enabled)" :key="p.id" :value="p.id">{{ p.name }}</option></select></label><label>风险<select v-model="risk" @change="load(0)"><option value="">全部</option><option value="HIGH">高风险</option><option value="MEDIUM">中风险</option><option value="LOW">低风险</option></select></label></div>
    <p v-if="error" class="stream-error">{{ error }}</p><p v-if="loading" class="run-loading">正在加载情报…</p>
    <div v-else class="intelligence-grid">
      <button v-for="item in page.items" :key="item.analysisId" class="panel intelligence-card" @click="open(item.analysisId)">
        <header><span>{{ item.projectName }} · {{ item.versionTag }}</span><i class="status-pill" :class="item.riskLevel==='HIGH'?'status-failed':item.riskLevel==='MEDIUM'?'status-cancelled':'status-succeeded'">{{ item.status==='SUCCEEDED'?label(item.riskLevel):item.status }}</i></header>
        <h3>{{ item.releaseTitle }}</h3><p>{{ item.oneLineSummary??'等待 Worker 完成分析…' }}</p>
        <footer><b>{{ label(item.recommendation) }}</b><time>{{ new Date(item.occurredAt).toLocaleDateString() }}</time></footer>
      </button>
      <div v-if="!page.items.length" class="conversation-empty"><strong>尚无情报分析</strong><p>历史 Release 需由系统管理员手动选择分析；P1.3 上线后新 Release 会自动进入队列。</p></div>
    </div>
    <div v-if="page.total>page.size" class="run-pagination"><span>第 {{ page.page+1 }} 页 · 共 {{ page.total }} 条</span><div><button :disabled="page.page===0" @click="load(page.page-1)">上一页</button><button :disabled="(page.page+1)*page.size>=page.total" @click="load(page.page+1)">下一页</button></div></div>

    <div v-if="detail" class="run-detail-backdrop" @click.self="router.push({name:'intelligence'})">
      <aside class="run-detail-panel intelligence-detail">
        <div class="detail-topbar"><div><span class="eyebrow">{{ detail.summary.projectName }} · {{ detail.summary.versionTag }}</span><h3>{{ detail.summary.releaseTitle }}</h3></div><button @click="router.push({name:'intelligence'})">×</button></div>
        <div class="risk-banner" :class="`risk-${detail.summary.riskLevel?.toLowerCase()}`"><strong>{{ label(detail.summary.riskLevel) }}</strong><span>{{ label(detail.summary.recommendation) }} · {{ detail.summary.evidenceStatus==='SUFFICIENT'?'证据充分':'证据不足' }}</span></div>
        <section class="detail-block"><span class="eyebrow">一句话结论</span><p>{{ detail.summary.oneLineSummary }}</p></section>
        <section class="detail-block"><span class="eyebrow">主要变化</span><ul><li v-for="x in detail.majorChanges" :key="x">{{ x }}</li></ul></section>
        <section class="detail-block"><span class="eyebrow">Java 项目影响</span><p>{{ detail.javaImpact }}</p></section>
        <section class="detail-block"><span class="eyebrow">升级价值</span><p>{{ detail.upgradeValue }}</p></section>
        <section class="detail-block"><span class="eyebrow">风险</span><ul><li v-for="x in detail.risks" :key="x">{{ x }}</li></ul></section>
        <section class="detail-block"><span class="eyebrow">推荐行动</span><ol><li v-for="x in detail.recommendedActions" :key="x">{{ x }}</li></ol></section>
        <section class="detail-block"><span class="eyebrow">官方证据</span><div class="detail-sources"><a v-for="url in detail.evidenceUrls" :key="url" :href="url" target="_blank" rel="noreferrer">{{ url }}</a></div></section>
        <dl class="detail-metrics"><div><dt>模型</dt><dd>{{ detail.modelName??'-' }}</dd></div><div><dt>输入/输出 Token</dt><dd>{{ detail.promptTokens??'-' }} / {{ detail.completionTokens??'-' }}</dd></div><div><dt>估算费用</dt><dd>¥{{ detail.estimatedCostCny??0 }}</dd></div><div><dt>尝试次数</dt><dd>{{ detail.attempts }}</dd></div></dl>
        <button class="send-button intelligence-research" @click="research(detail)">继续深入研究</button>
      </aside>
    </div>
  </section>
</template>
