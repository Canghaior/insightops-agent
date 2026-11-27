<script setup lang="ts">
import { backgroundFields, type BusinessSystem } from '@/api/businessSystems'

defineProps<{ system: BusinessSystem }>()
</script>

<template>
  <section class="system-background" aria-label="业务系统背景">
    <header>
      <h3>{{ system.background.name }}</h3>
      <span>用户提供 · 档案修订 {{ system.revision }}</span>
    </header>
    <dl>
      <div v-for="field in backgroundFields" :key="field.key">
        <dt>{{ field.label }}</dt>
        <dd>{{ system.background[field.key] || '未提供' }}</dd>
      </div>
      <div>
        <dt>档案资料</dt>
        <dd>{{ system.attachments.map(file => file.name ?? '资料已不可用').join('、') || '未关联' }}</dd>
      </div>
    </dl>
  </section>
</template>

<style scoped>
.system-background header { display: flex; flex-wrap: wrap; align-items: baseline; gap: 8px 16px; }
.system-background h3 { margin: 0; font-size: 1.15rem; overflow-wrap: anywhere; }
.system-background header span, dt { color: var(--muted); font-size: .83rem; }
dl { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); margin: 16px 0 0; gap: 16px 28px; }
dl > div { border-top: 1px solid var(--line); padding-top: 10px; }
dd { margin: 5px 0 0; white-space: pre-wrap; overflow-wrap: anywhere; line-height: 1.55; }
@media (max-width: 640px) { dl { grid-template-columns: 1fr; } }
</style>
