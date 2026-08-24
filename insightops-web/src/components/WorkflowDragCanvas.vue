<script setup lang="ts">
import { computed, onUnmounted, ref } from 'vue'

export interface WorkflowCanvasNode {
  id: string
  toolName: string
  dependsOn: string
  condition: string
  required: boolean
  x?: number
  y?: number
}

const props = defineProps<{
  nodes: WorkflowCanvasNode[]
  riskLevels: Record<string, string>
}>()
const emit = defineEmits<{
  'update:nodes': [nodes: WorkflowCanvasNode[]]
  dirty: []
}>()

const canvas = ref<globalThis.HTMLElement | null>(null)
const dragging = ref<{ index: number; offsetX: number; offsetY: number } | null>(null)
const cardWidth = 210
const cardHeight = 124

const positioned = computed(() => props.nodes.map((node, index) => ({
  ...node,
  x: node.x ?? 24 + (index % 3) * 250,
  y: node.y ?? 28 + Math.floor(index / 3) * 165,
})))

const canvasHeight = computed(() => Math.max(
  340,
  ...positioned.value.map((node) => (node.y ?? 0) + cardHeight + 36),
))

const edges = computed(() => positioned.value.flatMap((node) => dependencies(node.dependsOn)
  .map((sourceId) => {
    const source = positioned.value.find((item) => item.id === sourceId)
    if (!source) return null
    const startX = (source.x ?? 0) + cardWidth
    const startY = (source.y ?? 0) + cardHeight / 2
    const endX = node.x ?? 0
    const endY = (node.y ?? 0) + cardHeight / 2
    const bend = Math.max(38, Math.abs(endX - startX) / 2)
    return {
      id: `${sourceId}->${node.id}`,
      path: `M ${startX} ${startY} C ${startX + bend} ${startY}, ${endX - bend} ${endY}, ${endX} ${endY}`,
    }
  }).filter((edge): edge is { id: string; path: string } => Boolean(edge))))

function dependencies(value: string) {
  return value.split(',').map((item) => item.trim()).filter(Boolean)
}

function updateNode(index: number, patch: Partial<WorkflowCanvasNode>) {
  emit('update:nodes', props.nodes.map((node, current) => current === index
    ? { ...node, ...patch }
    : node))
  emit('dirty')
}

function startDrag(event: globalThis.PointerEvent, index: number) {
  if (!canvas.value) return
  const rect = canvas.value.getBoundingClientRect()
  const node = positioned.value[index]
  dragging.value = {
    index,
    offsetX: event.clientX - rect.left - (node.x ?? 0),
    offsetY: event.clientY - rect.top - (node.y ?? 0),
  }
  globalThis.addEventListener('pointermove', moveDrag)
  globalThis.addEventListener('pointerup', stopDrag, { once: true })
  event.preventDefault()
}

function moveDrag(event: globalThis.PointerEvent) {
  if (!dragging.value || !canvas.value) return
  const rect = canvas.value.getBoundingClientRect()
  const maxX = Math.max(0, rect.width - cardWidth - 12)
  const x = Math.round(Math.max(0, Math.min(maxX,
    event.clientX - rect.left - dragging.value.offsetX)) / 8) * 8
  const y = Math.round(Math.max(0,
    event.clientY - rect.top - dragging.value.offsetY) / 8) * 8
  updateNode(dragging.value.index, { x, y })
}

function stopDrag() {
  dragging.value = null
  globalThis.removeEventListener('pointermove', moveDrag)
}

function addDependency(index: number, event: globalThis.Event) {
  const select = event.target as globalThis.HTMLSelectElement
  const dependency = select.value
  select.value = ''
  if (!dependency) return
  const current = dependencies(props.nodes[index]?.dependsOn ?? '')
  if (!current.includes(dependency)) current.push(dependency)
  updateNode(index, { dependsOn: current.join(', ') })
}

function removeDependency(index: number, dependency: string) {
  updateNode(index, {
    dependsOn: dependencies(props.nodes[index]?.dependsOn ?? '')
      .filter((item) => item !== dependency).join(', '),
  })
}

onUnmounted(() => {
  globalThis.removeEventListener('pointermove', moveDrag)
  globalThis.removeEventListener('pointerup', stopDrag)
})
</script>

<template>
  <section class="canvas-shell">
    <header>
      <div><span class="eyebrow">P2.4-C · Visual DAG</span><h3>拖拽任务画布</h3></div>
      <span class="subtle">拖动卡片布局；在卡片内添加或移除依赖</span>
    </header>
    <div ref="canvas" class="drag-canvas" :style="{ height: `${canvasHeight}px` }">
      <svg class="edge-layer" width="100%" :height="canvasHeight" aria-hidden="true">
        <defs>
          <marker id="workflow-arrow" markerWidth="8" markerHeight="8" refX="7" refY="4" orient="auto">
            <path d="M0,0 L8,4 L0,8 Z" />
          </marker>
        </defs>
        <path v-for="edge in edges" :key="edge.id" :d="edge.path" marker-end="url(#workflow-arrow)" />
      </svg>
      <article
        v-for="(node, index) in positioned"
        :key="`${node.id}-${index}`"
        class="canvas-node"
        :class="{ mutating: riskLevels[node.toolName] === 'MUTATING' }"
        :style="{ transform: `translate(${node.x}px, ${node.y}px)` }"
      >
        <header class="drag-handle" @pointerdown="startDrag($event, index)">
          <strong>{{ node.id || `节点 ${index + 1}` }}</strong><span>⠿</span>
        </header>
        <code>{{ node.toolName || '未选择工具' }}</code>
        <small>{{ node.condition }} · {{ node.required ? '必需' : '可选' }}</small>
        <div class="dependency-row">
          <button
            v-for="dependency in dependencies(node.dependsOn)"
            :key="dependency"
            type="button"
            title="移除依赖"
            @click="removeDependency(index, dependency)"
          >
            {{ dependency }} ×
          </button>
          <select aria-label="添加依赖" @change="addDependency(index, $event)">
            <option value="">＋依赖</option>
            <option
              v-for="candidate in positioned.filter((item) => item.id && item.id !== node.id && !dependencies(node.dependsOn).includes(item.id))"
              :key="candidate.id"
              :value="candidate.id"
            >
              {{ candidate.id }}
            </option>
          </select>
        </div>
      </article>
    </div>
  </section>
</template>

<style scoped>
.canvas-shell{border:1px solid var(--line);border-radius:18px;padding:18px;margin-top:22px;background:rgba(255,255,255,.012)}
.canvas-shell>header{display:flex;align-items:center;justify-content:space-between;gap:12px;margin-bottom:14px}.canvas-shell h3{margin:3px 0 0}
.drag-canvas{position:relative;overflow:auto;min-width:650px;border:1px dashed rgba(99,214,181,.28);border-radius:15px;background-image:radial-gradient(rgba(99,214,181,.12) 1px,transparent 1px);background-size:16px 16px}
.edge-layer{position:absolute;inset:0;overflow:visible;pointer-events:none}.edge-layer path{fill:none;stroke:rgba(99,214,181,.55);stroke-width:2}.edge-layer marker path{fill:var(--accent)}
.canvas-node{position:absolute;left:0;top:0;width:210px;min-height:124px;padding:12px;border:1px solid rgba(99,214,181,.5);border-radius:14px;background:#10201f;box-shadow:0 12px 28px rgba(0,0,0,.24);display:grid;gap:6px;user-select:none}.canvas-node.mutating{border-color:#d99a5d;background:#211c17}.canvas-node code{color:var(--accent);overflow:hidden;text-overflow:ellipsis}.canvas-node small{color:var(--muted)}
.drag-handle{display:flex;justify-content:space-between;align-items:center;cursor:grab;touch-action:none}.drag-handle:active{cursor:grabbing}.drag-handle span{color:var(--muted);font-size:18px}
.dependency-row{display:flex;align-items:center;gap:5px;flex-wrap:wrap}.dependency-row button,.dependency-row select{font-size:10px;padding:4px 6px;border-radius:8px;background:rgba(99,214,181,.08);border:1px solid var(--line);color:var(--muted)}
@media(max-width:800px){.canvas-shell{overflow-x:auto}.canvas-shell>header{align-items:flex-start;flex-direction:column}}
</style>
