<script setup lang="ts">
import { kindLabels, originalUrl, type BaselineContent, type BaselineSummary } from '@/api/engineeringBaselines'
defineProps<{ content: BaselineContent; saved?: BaselineSummary; downloads?: boolean }>()
</script>

<template>
  <div class="facts">
    <h4>{{ saved ? '工程基线 v' + saved.version + ' · ' : '工程资料预览 · ' }}{{ content.label }}</h4>
    <p>资料中的来源标识 <code>{{ content.evidence.sourceLabel }}</code></p>
    <table aria-label="工程事实及来源">
      <thead><tr><th>来源类型</th><th>事实</th><th>文件位置</th></tr></thead>
      <tbody>
        <tr v-for="(f, i) in content.evidence.facts" :key="i">
          <td>{{ kindLabels[f.kind] }}</td><td>{{ f.subject }} · <strong>{{ f.value }}</strong></td>
          <td>{{ f.source.path }}:{{ f.source.line }}<small v-for="(via, j) in f.via" :key="j">属性来源 {{ via.path }}:{{ via.line }}</small></td>
        </tr>
      </tbody>
    </table>
    <h5>未知项与适用范围</h5><ul><li v-for="g in content.evidence.gaps" :key="g">{{ g }}</li></ul>
    <h5>用户提供的信息</h5><p>导入时档案修订 {{ content.profileAtImport.revision }} · {{ content.profileAtImport.technologyStack || '未填写技术栈' }}</p>
    <p v-if="content.expectedJava">导入者填写的 POM Java 版本 · {{ content.expectedJava }}</p><p v-if="content.note">{{ content.note }}</p>
    <p v-for="g in content.conflicts" :key="g" class="conflict" role="note">待核对 · {{ g }}</p>
    <details>
      <summary>原件与 SHA-256（{{ content.evidence.files.length }} 份）</summary>
      <p>哈希可核对文件内容。源码提交的归属需要另行查证。原始提取清单可含源码或测试入口线索；本版事实表只解读 POM 和可识别的执行记录。外部检索引用另行统计。</p>
      <a v-if="downloads && saved" :href="originalUrl(saved.systemId, saved.id)" download>下载完整原件 · {{ content.originalName }}</a>
      <ul><li v-for="file in content.evidence.files" :key="file.path"><a v-if="downloads && saved" :href="originalUrl(saved.systemId, saved.id, file.path)" download>{{ file.path }}</a><span v-else>{{ file.path }}</span><small>{{ file.size }} 字节 · {{ file.sha256 }}</small></li></ul>
    </details>
  </div>
</template>
<style scoped>
.facts { overflow-wrap: anywhere; } h4 { margin-block: 12px; } h5 { margin: 18px 0 6px; font-size: 1rem; }
table { width: 100%; border-collapse: collapse; } th,td { text-align: left; vertical-align: top; border-bottom: 1px solid var(--line); padding: 8px 6px; }
small { display:block; color:var(--muted); } summary { cursor:pointer; } .conflict { border-left:3px solid #dc9e50; padding:8px 14px; }
</style>

