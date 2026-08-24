<template>
  <div class="soar-editor">
    <aside class="node-palette">
      <h3>节点</h3><p>点击添加到画布</p>
      <button v-for="item in palette" :key="item.type" type="button" :data-node-type="item.type" :style="{ '--color': item.color }" @click="addNode(item.type)">
        <component :is="item.icon" /><span><strong>{{ item.label }}</strong><small>{{ item.hint }}</small></span>
      </button>
      <a-divider />
      <a-alert type="info" show-icon message="连接规则" description="普通节点一条 next；条件 true/false；人工 approve/reject；并行使用配置的分支标签并在 JOIN 汇合。" />
    </aside>

    <section class="flow-canvas">
      <VueFlow v-model:nodes="flowNodes" v-model:edges="flowEdges" :fit-view-on-init="true" :min-zoom="0.35" :max-zoom="1.8"
        @connect="connectNodes" @node-click="selectNode" @edge-click="selectEdge" @node-drag-stop="moveNode" @pane-click="clearSelection">
        <template #node-soar="{ data, selected }">
          <div class="soar-node" :class="[`node-${data.node.type}`, { selected }]" :style="{ '--color': color[data.node.type] }">
            <Handle v-if="data.node.type !== 'start'" type="target" :position="Position.Left" />
            <span>{{ label[data.node.type] }}</span><strong>{{ data.node.name }}</strong><code>{{ data.node.id }}</code>
            <Handle v-if="data.node.type !== 'end'" type="source" :position="Position.Right" />
          </div>
        </template>
      </VueFlow>
      <div class="canvas-tip">拖动节点调整位置 · 从右侧连接点拖到下游节点 · 点击连线后可删除</div>
      <a-button v-if="selectedEdge" class="edge-delete" danger @click="deleteEdge">删除选中连线（{{ selectedEdge.label }}）</a-button>
    </section>

    <aside class="node-inspector-panel">
      <SoarNodeInspector :node="selectedNode" :graph-nodes="localGraph.nodes" :fields="fields" :actions="actions" @update="updateNode" @delete="deleteNode" />
    </aside>
  </div>
</template>

<script setup>
import { computed, markRaw, ref, watch } from 'vue'
import { message } from 'ant-design-vue'
import { CheckCircleOutlined, ClockCircleOutlined, ForkOutlined, SendOutlined } from '@ant-design/icons-vue'
import { Handle, MarkerType, Position, VueFlow } from '@vue-flow/core'
import SoarNodeInspector from './SoarNodeInspector.vue'
import { appendEdge, appendNode, cloneGraph, moveNode as moveGraphNode, removeEdge, removeNode, updateNode as updateGraphNode } from './soarGraph.js'

const props = defineProps({ modelValue: { type: Object, required: true }, fields: { type: Array, default: () => [] }, actions: { type: Array, default: () => [] } })
const emit = defineEmits(['update:modelValue'])
const flowNodes = ref([])
const flowEdges = ref([])
const selectedNodeId = ref('')
const selectedEdgeId = ref('')
const localGraph = ref(cloneGraph(props.modelValue))
let localSignature = graphSignature(localGraph.value)

const palette = [
  { type: 'condition', label: '条件判断', hint: '字段字典 + AND', color: '#7651a8', icon: markRaw(ForkOutlined) },
  { type: 'business', label: '业务动作', hint: '调用平台服务', color: '#217aa5', icon: markRaw(SendOutlined) },
  { type: 'human', label: '人工审批', hint: '批准 / 拒绝', color: '#bf771d', icon: markRaw(CheckCircleOutlined) },
  { type: 'wait', label: '等待', hint: '分钟 / 小时', color: '#258887', icon: markRaw(ClockCircleOutlined) },
  { type: 'parallel', label: '并行分发', hint: '持久 fan-out', color: '#9a5a2c', icon: markRaw(ForkOutlined) },
  { type: 'join', label: '并行汇合', hint: '等待分支到齐', color: '#9a5a2c', icon: markRaw(ForkOutlined) },
  { type: 'loop', label: '有界循环', hint: '串行复用 body', color: '#4766ad', icon: markRaw(ClockCircleOutlined) },
  { type: 'loop_end', label: '循环体结束', hint: '迭代边界', color: '#4766ad', icon: markRaw(ClockCircleOutlined) },
  { type: 'connector', label: '设备连接器', hint: 'HTTP / 厂商适配', color: '#a13f67', icon: markRaw(SendOutlined) },
]
const color = { start: '#278463', end: '#596b78', condition: '#7651a8', business: '#217aa5', human: '#bf771d', wait: '#258887', parallel: '#9a5a2c', join: '#9a5a2c', loop: '#4766ad', loop_end: '#4766ad', connector: '#a13f67' }
const label = { start: 'START', end: 'END', condition: 'CONDITION', business: 'BUSINESS', human: 'HUMAN', wait: 'WAIT', parallel: 'PARALLEL', join: 'JOIN', loop: 'LOOP', loop_end: 'LOOP END', connector: 'CONNECTOR' }
const selectedNode = computed(() => localGraph.value.nodes?.find((node) => node.id === selectedNodeId.value) || null)
const selectedEdge = computed(() => flowEdges.value.find((edge) => edge.id === selectedEdgeId.value))

watch(() => props.modelValue, (graph) => {
  const nextSignature = graphSignature(graph)
  if (nextSignature === localSignature) return
  localGraph.value = cloneGraph(graph)
  localSignature = nextSignature
  rebuild()
}, { deep: true })

rebuild()

function rebuild() {
  flowNodes.value = (localGraph.value.nodes || []).map((node) => ({ id: node.id, type: 'soar', position: { x: node.x, y: node.y }, data: { node } }))
  flowEdges.value = (localGraph.value.edges || []).map(edgeView)
}

function edgeView(edge) {
  return { ...edge, label: branchLabel(edge.branch), markerEnd: MarkerType.ArrowClosed,
    style: { stroke: ['false', 'reject'].includes(edge.branch) ? '#bc4651' : '#53798e', strokeWidth: 2 },
    labelStyle: { fill: ['false', 'reject'].includes(edge.branch) ? '#a12f3a' : '#315c73', fontWeight: 700 } }
}

function branchLabel(branch) { return ({ next: 'NEXT', true: 'TRUE', false: 'FALSE', approve: 'APPROVE', reject: 'REJECT' })[branch] || branch }
function graphSignature(graph) { return JSON.stringify(graph || {}) }
function change(graph) {
  const snapshot = cloneGraph(graph)
  localGraph.value = snapshot
  localSignature = graphSignature(snapshot)
  rebuild()
  emit('update:modelValue', cloneGraph(snapshot))
}
function uniqueId(prefix) {
  const suffix = globalThis.crypto?.randomUUID?.() || `${Date.now().toString(36)}-${Math.random().toString(36).slice(2)}`
  return `${prefix}-${suffix}`
}

function addNode(type) {
  const graph = localGraph.value
  const id = uniqueId(type)
  const config = type === 'condition' ? { mode: 'AND', conditions: [{ field: props.fields[0]?.path || '', operator: props.fields[0]?.operators?.[0]?.id || 'eq', value: '' }] }
    : type === 'business' ? { action: props.actions[0]?.id || '', parameters: {} }
      : type === 'human' ? { prompt: `请复核 \${${props.fields[0]?.path || 'alert.id'}}` }
        : type === 'parallel' ? { branches: ['left', 'right'], joinNode: '' }
          : type === 'loop' ? { bodyStart: '', bodyEnd: '', items: ['item-1'], maxIterations: 10 }
            : type === 'connector' ? { runtimeKey: 'http', action: 'GET', parameters: { url: '', body: '' }, timeoutMs: 10000 }
              : ['join', 'loop_end'].includes(type) ? {} : { amount: 5, unit: 'minutes' }
  const count = graph.nodes.length
  const policy = { maxAttempts: 0, initialDelaySeconds: 2, backoffMultiplier: 2, maxDelaySeconds: 60 }
  const node = { id, name: palette.find((item) => item.type === type).label, type, config, x: 260 + (count % 3) * 220, y: 80 + Math.floor(count / 3) * 140, policy }
  change(appendNode(graph, node))
  selectedNodeId.value = id
}

function connectNodes(connection) {
  const graph = localGraph.value
  const source = graph.nodes.find((node) => node.id === connection.source)
  const target = graph.nodes.find((node) => node.id === connection.target)
  if (!source || !target || source.id === target.id || source.type === 'end' || target.type === 'start') return
  const outgoing = graph.edges.filter((edge) => edge.source === source.id)
  let branch = 'next'
  if (source.type === 'condition') branch = ['true', 'false'].find((value) => !outgoing.some((edge) => edge.branch === value))
  else if (source.type === 'human') branch = ['approve', 'reject'].find((value) => !outgoing.some((edge) => edge.branch === value))
  else if (source.type === 'parallel') branch = (source.config.branches || []).find((value) => !outgoing.some((edge) => edge.branch === value))
    if (!branch) return message.warning('该节点的分支已经全部连接')
  if (outgoing.some((edge) => edge.branch === branch && edge.target === target.id)) return
  const replaced = !['condition', 'human', 'parallel'].includes(source.type) && outgoing.length > 0
  const edge = { id: uniqueId('edge'), source: source.id, target: target.id, branch }
  change(appendEdge(graph, edge, replaced))
  if (replaced) message.info('已用新的 next 连线替换原连线')
}

function selectNode({ node }) { selectedNodeId.value = node.id; selectedEdgeId.value = '' }
function selectEdge({ edge }) { selectedEdgeId.value = edge.id; selectedNodeId.value = '' }
function clearSelection() { selectedNodeId.value = ''; selectedEdgeId.value = '' }
function moveNode({ node }) {
  change(moveGraphNode(localGraph.value, node.id, node.position))
}
function updateNode(node) {
  change(updateGraphNode(localGraph.value, node))
}
function deleteNode() {
  const node = selectedNode.value
  if (!node || ['start', 'end'].includes(node.type)) return
  change(removeNode(localGraph.value, node.id))
  selectedNodeId.value = ''
}
function deleteEdge() {
  if (!selectedEdge.value) return
  change(removeEdge(localGraph.value, selectedEdge.value.id))
  selectedEdgeId.value = ''
}
</script>

<style scoped>
.soar-editor { display:grid; grid-template-columns:190px minmax(620px, 1fr) 330px; height:680px; overflow:hidden; border:1px solid #d8e3ea; border-radius:10px; background:white; }
.node-palette, .node-inspector-panel { padding:14px; overflow:auto; background:#f7fafb; }
.node-palette { border-right:1px solid #d8e3ea; }.node-inspector-panel { border-left:1px solid #d8e3ea; }
.node-palette h3 { margin:0; color:#19364a; }.node-palette > p { margin:3px 0 12px; color:#738694; font-size:12px; }
.node-palette > button { width:100%; display:flex; align-items:center; gap:9px; margin:8px 0; padding:10px; color:var(--color); border:1px solid #d8e3ea; border-left:3px solid var(--color); border-radius:7px; background:white; text-align:left; cursor:pointer; }
.node-palette > button span, .node-palette > button strong, .node-palette > button small { display:block; }.node-palette > button small { margin-top:2px; color:#758795; }
.flow-canvas { position:relative; background-color:#f4f8fa; background-image:radial-gradient(#bacad4 1px, transparent 1px); background-size:20px 20px; }
.flow-canvas :deep(.vue-flow) { height:100%; }.canvas-tip { position:absolute; left:12px; bottom:10px; z-index:5; padding:6px 9px; color:#617787; background:rgb(255 255 255 / 90%); border-radius:6px; font-size:11px; }
.edge-delete { position:absolute; right:12px; bottom:10px; z-index:6; }
.soar-node { width:170px; min-height:72px; padding:10px 12px; border:2px solid var(--color); border-radius:9px; background:white; box-shadow:0 7px 19px rgb(20 52 72 / 13%); }
.soar-node.selected { box-shadow:0 0 0 3px color-mix(in srgb, var(--color) 24%, transparent); }.soar-node span, .soar-node strong, .soar-node code { display:block; }.soar-node span { color:var(--color); font-size:10px; font-weight:800; letter-spacing:.08em; }.soar-node strong { margin:5px 0; color:#173347; }.soar-node code { overflow:hidden; color:#718491; font-size:10px; text-overflow:ellipsis; }
.node-start, .node-end { width:120px; min-height:58px; border-radius:30px; text-align:center; }.node-start { background:#eaf7f2; }.node-end { background:#f1f4f6; }
.soar-node :deep(.vue-flow__handle) { width:11px; height:11px; border:2px solid white; background:var(--color); }
</style>
