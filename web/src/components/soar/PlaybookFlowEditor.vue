<template>
  <div class="editor-shell">
    <aside class="palette">
      <h4>节点工具箱</h4>
      <p class="muted">拖到画布，或点击添加</p>
      <button v-for="item in nodeTypes" :key="item.type" class="palette-item" draggable="true"
        :style="{ '--node-color': item.color }" @dragstart="startDrag($event, item.type)" @click="addNode(item.type)">
        <component :is="item.icon" /><span><strong>{{ item.label }}</strong><small>{{ item.hint }}</small></span>
      </button>
      <a-divider />
      <h4>流程入口</h4>
      <a-select :value="definition.entrypoint || undefined" placeholder="选择唯一入口" style="width: 100%" :options="businessNodes.map((node) => ({ value: node.id, label: node.name }))" @change="setEntrypoint" />
      <a-alert v-if="issues.length" type="error" show-icon :message="`${issues.length} 个图校验问题`" style="margin-top: 12px">
        <template #description><ul class="issue-list"><li v-for="issue in issues" :key="issue">{{ issue }}</li></ul></template>
      </a-alert>
      <a-alert v-else type="success" show-icon message="路径已闭合" style="margin-top: 12px" />
    </aside>

    <section ref="canvas" class="flow-canvas" @dragover.prevent @drop="dropNode">
      <VueFlow v-model:nodes="flowNodes" v-model:edges="flowEdges" :fit-view-on-init="true" :min-zoom="0.35" :max-zoom="1.7"
        :nodes-connectable="editable" :nodes-draggable="editable" :elements-selectable="true"
        @connect="connect" @node-click="selectNode" @edge-click="selectEdge" @node-drag-stop="positionChanged" @pane-click="clearSelection">
        <template #node-playbook="{ data, selected }">
          <div class="flow-node" :class="[`kind-${data.kind}`, { selected }]" :style="{ '--node-color': data.color }">
            <Handle v-if="data.kind !== 'start'" type="target" :position="Position.Left" class="node-handle input-handle" />
            <Handle v-if="data.kind !== 'end'" type="source" :position="Position.Right" class="node-handle output-handle" />
            <div class="node-type">{{ data.kind === 'start' ? 'START' : data.label }}</div>
            <strong>{{ data.name }}</strong>
            <code v-if="data.id !== '__start__'">{{ data.id }}</code>
          </div>
        </template>
      </VueFlow>
      <div class="canvas-help">从节点右侧输出桩拖到另一个节点左侧输入桩完成连线 · 单击连线可设置事件/条件</div>
    </section>

    <aside class="inspector">
      <template v-if="selectedNode && selectedNode.id !== '__start__'">
        <div class="inspector-title"><h4>节点检查器</h4><a-button danger type="text" size="small" :disabled="!editable" @click="removeSelectedNode"><DeleteOutlined /></a-button></div>
        <a-form layout="vertical" size="small">
          <a-form-item label="节点 ID"><a-input :value="selectedNode.data.id" disabled /></a-form-item>
          <a-form-item label="显示名称"><a-input :value="selectedNode.data.name" :disabled="!editable" @change="patchNode({ name: $event.target.value })" /></a-form-item>
          <a-form-item label="汇聚策略"><a-select :value="selectedNode.data.node.join || 'any'" :disabled="!editable" :options="joinOptions" @change="patchNode({ join: $event })" /></a-form-item>
          <a-form-item v-if="selectedNode.data.kind === 'decision'" label="分支策略"><a-select :value="Boolean(selectedNode.data.node.exclusive)" :disabled="!editable" :options="exclusiveOptions" @change="patchNode({ exclusive: $event })" /></a-form-item>
          <a-form-item v-if="selectedNode.data.kind === 'action'" label="受控动作"><a-select :value="selectedNode.data.node.action" show-search :disabled="!editable" :options="actions.map((value) => ({ value, label: value }))" @change="patchNode({ action: $event })" /></a-form-item>
          <a-form-item v-if="selectedNode.data.kind === 'delay'" label="延迟秒数"><a-input-number :value="selectedNode.data.node.delaySeconds" :min="1" :max="86400" :disabled="!editable" style="width: 100%" @change="patchNode({ delaySeconds: $event })" /></a-form-item>
          <a-form-item v-if="selectedNode.data.kind === 'end'" label="结束结果"><a-select :value="selectedNode.data.node.result" :disabled="!editable" :options="resultOptions" @change="patchNode({ result: $event })" /></a-form-item>
          <a-form-item v-if="parameterNodeTypes.includes(selectedNode.data.kind)" label="with 参数（JSON）"><JsonField :model-value="selectedNode.data.node.with || {}" :rows="9" @update:model-value="patchNode({ with: $event })" /></a-form-item>
          <a-form-item label="节点执行条件（JSON）"><JsonField :model-value="selectedNode.data.node.when || {}" :rows="7" @update:model-value="patchNode({ when: Object.keys($event).length ? $event : null })" /></a-form-item>
        </a-form>
      </template>
      <template v-else-if="selectedEdge">
        <div class="inspector-title"><h4>连线检查器</h4><a-button danger type="text" size="small" :disabled="!editable || selectedEdge.source === '__start__'" @click="removeSelectedEdge"><DeleteOutlined /></a-button></div>
        <a-descriptions size="small" :column="1" bordered style="margin-bottom: 12px"><a-descriptions-item label="起点">{{ selectedEdge.source }}</a-descriptions-item><a-descriptions-item label="终点">{{ selectedEdge.target }}</a-descriptions-item></a-descriptions>
        <template v-if="selectedEdge.source === '__start__'"><a-alert type="info" show-icon message="START 连线定义唯一 entrypoint" /></template>
        <a-form v-else layout="vertical">
          <a-form-item label="路由事件"><a-select :value="selectedEdge.data?.event || 'success'" :disabled="!editable" :options="eventOptions" @change="patchEdge({ event: $event })" /></a-form-item>
          <a-form-item label="边条件（JSON，空对象表示无条件）"><JsonField :model-value="selectedEdge.data?.when || {}" :rows="9" @update:model-value="patchEdge({ when: Object.keys($event).length ? $event : null })" /></a-form-item>
        </a-form>
      </template>
      <a-empty v-else description="选择节点或连线进行编辑" />
    </aside>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { message } from 'ant-design-vue'
import { CheckCircleOutlined, ClockCircleOutlined, DeleteOutlined, ForkOutlined, NodeIndexOutlined, SendOutlined, StopOutlined, SyncOutlined } from '@ant-design/icons-vue'
import { Handle, MarkerType, Position, VueFlow } from '@vue-flow/core'
import JsonField from '../common/JsonField.vue'

const definition = defineModel('definition', { type: Object, required: true })
const layout = defineModel('layout', { type: Object, default: () => ({}) })
const props = defineProps({ editable: { type: Boolean, default: true } })
const canvas = ref()
const flowNodes = ref([])
const flowEdges = ref([])
const selectedNodeId = ref('')
const selectedEdgeId = ref('')
let nextNodeNumber = 1

const nodeTypes = [
  { type: 'action', label: 'Action', hint: '执行受控动作', color: '#2479a9', icon: SendOutlined },
  { type: 'decision', label: 'Condition', hint: '条件路由与分支', color: '#7353a6', icon: ForkOutlined },
  { type: 'approval', label: 'Approval', hint: '人工审批闸门', color: '#c07a22', icon: CheckCircleOutlined },
  { type: 'delay', label: 'Delay', hint: '延迟后继续', color: '#268a8b', icon: ClockCircleOutlined },
  { type: 'subplaybook', label: 'Sub Playbook', hint: '调用子流程', color: '#5943a5', icon: NodeIndexOutlined },
  { type: 'loop', label: 'Loop', hint: '有界循环', color: '#a94578', icon: SyncOutlined },
  { type: 'map', label: 'Map', hint: '批量并行映射', color: '#418342', icon: NodeIndexOutlined },
  { type: 'end', label: 'End', hint: '明确终止结果', color: '#506171', icon: StopOutlined },
]
const colors = Object.fromEntries(nodeTypes.map((item) => [item.type, item.color]))
const labels = Object.fromEntries(nodeTypes.map((item) => [item.type, item.label]))
const actions = ['context.set', 'notification.create', 'alert.set_status', 'alert.set_verdict', 'case.set_status', 'case.add_alert', 'case.add_evidence', 'connector.call']
const parameterNodeTypes = ['action', 'approval', 'subplaybook', 'loop', 'map']
const joinOptions = [{ value: 'any', label: '任一上游到达即可运行' }, { value: 'all', label: '等待全部上游完成' }]
const exclusiveOptions = [{ value: false, label: '所有匹配边（可并行）' }, { value: true, label: '只走第一条匹配边' }]
const resultOptions = ['succeeded', 'failed', 'rejected'].map((value) => ({ value, label: value }))
const eventOptions = ['success', 'failure', 'approved', 'rejected', 'complete', 'always'].map((value) => ({ value, label: value }))

const businessNodes = computed(() => flowNodes.value.filter((node) => node.id !== '__start__'))
const selectedNode = computed(() => flowNodes.value.find((node) => node.id === selectedNodeId.value))
const selectedEdge = computed(() => flowEdges.value.find((edge) => edge.id === selectedEdgeId.value))
const issues = computed(validateGraph)

function buildFlow() {
  const nodes = definition.value.nodes || []
  flowNodes.value = [
    { id: '__start__', type: 'playbook', position: layout.value.__start__ || { x: 30, y: 230 }, draggable: false, data: { id: '__start__', name: '流程入口', kind: 'start', label: 'Start', color: '#2a8a6d' } },
    ...nodes.map((node, index) => ({
      id: node.id, type: 'playbook', position: layout.value[node.id] || { x: 250 + (index % 4) * 230, y: 60 + Math.floor(index / 4) * 150 },
      data: { id: node.id, name: node.name, kind: node.type, label: labels[node.type] || node.type, color: colors[node.type] || '#667788', node },
    })),
  ]
  flowEdges.value = []
  if (definition.value.entrypoint) flowEdges.value.push(edgeView('__start__', definition.value.entrypoint, 'start', null, 'start-entry'))
  nodes.forEach((node) => (node.transitions || []).forEach((transition, index) => {
    flowEdges.value.push(edgeView(node.id, transition.target, transition.on || 'success', transition.when, `${node.id}:${index}:${transition.target}`))
  }))
}

function edgeView(source, target, event, when, id) {
  const failure = ['failure', 'rejected'].includes(event)
  return { id, source, target, type: 'default', label: event === 'start' ? 'START' : event, markerEnd: MarkerType.ArrowClosed,
    style: { stroke: failure ? '#c94a52' : '#66869a', strokeWidth: 2 }, labelStyle: { fill: failure ? '#a62e38' : '#456274', fontWeight: 600 }, data: { event, when } }
}

function startDrag(event, type) { if (props.editable) event.dataTransfer.setData('application/soar-node', type) }
function dropNode(event) {
  if (!props.editable) return
  const type = event.dataTransfer.getData('application/soar-node')
  if (!type) return
  const bounds = canvas.value.getBoundingClientRect()
  addNode(type, { x: event.clientX - bounds.left - 90, y: event.clientY - bounds.top - 35 })
}
function addNode(type, position) {
  if (!props.editable) return
  let id
  do { id = `${type}-${nextNodeNumber++}` } while ((definition.value.nodes || []).some((node) => node.id === id))
  const node = defaultNode(type, id)
  const pos = position || { x: 250 + (businessNodes.value.length % 3) * 220, y: 70 + Math.floor(businessNodes.value.length / 3) * 145 }
  definition.value = { ...definition.value, nodes: [...(definition.value.nodes || []), node] }
  layout.value = { ...layout.value, [id]: pos }
  flowNodes.value.push({ id, type: 'playbook', position: pos, data: { id, name: node.name, kind: type, label: labels[type], color: colors[type], node } })
  selectedNodeId.value = id; selectedEdgeId.value = ''
}
function setEntrypoint(target) {
  definition.value = { ...definition.value, entrypoint: target }
  flowEdges.value = [edgeView('__start__', target, 'start', null, 'start-entry'), ...flowEdges.value.filter((edge) => edge.source !== '__start__')]
}
function connect(connection) {
  if (!props.editable || connection.target === '__start__' || connection.source === connection.target) return
  if (connection.source === '__start__') { setEntrypoint(connection.target); return }
  const sourceNode = definition.value.nodes.find((node) => node.id === connection.source)
  if (!sourceNode || sourceNode.type === 'end') return message.warning('End 节点不能再连接下游')
  if (flowEdges.value.some((edge) => edge.source === connection.source && edge.target === connection.target)) return message.warning('这两个节点已经连接')
  const event = sourceNode.type === 'approval' ? 'approved' : sourceNode.type === 'decision' ? 'always' : 'success'
  flowEdges.value.push(edgeView(connection.source, connection.target, event, null, `${connection.source}:${Date.now()}:${connection.target}`))
  syncTransitions()
}
function syncTransitions() {
  const transitions = new Map()
  flowEdges.value.filter((edge) => edge.source !== '__start__').forEach((edge) => {
    const list = transitions.get(edge.source) || []
    list.push({ target: edge.target, on: edge.data?.event || 'success', ...(edge.data?.when ? { when: edge.data.when } : {}) })
    transitions.set(edge.source, list)
  })
  definition.value = { ...definition.value, nodes: definition.value.nodes.map((node) => ({ ...node, transitions: transitions.get(node.id) || [] })) }
  flowNodes.value.forEach((view) => { if (view.id !== '__start__') { view.data.node = definition.value.nodes.find((node) => node.id === view.id); view.data.name = view.data.node.name } })
}
function selectNode({ node }) { selectedNodeId.value = node.id; selectedEdgeId.value = '' }
function selectEdge({ edge }) { selectedEdgeId.value = edge.id; selectedNodeId.value = '' }
function clearSelection() { selectedNodeId.value = ''; selectedEdgeId.value = '' }
function positionChanged({ node }) { layout.value = { ...layout.value, [node.id]: { x: Math.round(node.position.x), y: Math.round(node.position.y) } } }
function patchNode(patch) {
  if (!selectedNode.value || !props.editable) return
  const id = selectedNode.value.id
  definition.value = { ...definition.value, nodes: definition.value.nodes.map((node) => node.id === id ? { ...node, ...patch } : node) }
  const updated = definition.value.nodes.find((node) => node.id === id)
  selectedNode.value.data.node = updated; selectedNode.value.data.name = updated.name
}
function removeSelectedNode() {
  const id = selectedNode.value?.id
  if (!id || !props.editable) return
  flowNodes.value = flowNodes.value.filter((node) => node.id !== id)
  flowEdges.value = flowEdges.value.filter((edge) => edge.source !== id && edge.target !== id)
  const nextNodes = definition.value.nodes.filter((node) => node.id !== id)
  definition.value = { ...definition.value, nodes: nextNodes, entrypoint: definition.value.entrypoint === id ? '' : definition.value.entrypoint }
  const nextLayout = { ...layout.value }; delete nextLayout[id]; layout.value = nextLayout
  syncTransitions(); selectedNodeId.value = ''
}
function patchEdge(patch) {
  if (!selectedEdge.value || selectedEdge.value.source === '__start__' || !props.editable) return
  Object.assign(selectedEdge.value.data, patch)
  selectedEdge.value.label = selectedEdge.value.data.event
  const failure = ['failure', 'rejected'].includes(selectedEdge.value.data.event)
  selectedEdge.value.style = { stroke: failure ? '#c94a52' : '#66869a', strokeWidth: 2 }
  syncTransitions()
}
function removeSelectedEdge() {
  if (!selectedEdge.value || !props.editable) return
  const id = selectedEdge.value.id
  flowEdges.value = flowEdges.value.filter((edge) => edge.id !== id)
  selectedEdgeId.value = ''; syncTransitions()
}

function validateGraph() {
  const errors = []
  const nodes = definition.value.nodes || []
  const ids = new Set(nodes.map((node) => node.id))
  if (!definition.value.entrypoint || !ids.has(definition.value.entrypoint)) errors.push('START 必须连接一个有效入口节点')
  if (!nodes.some((node) => node.type === 'end')) errors.push('至少需要一个 End 节点')
  nodes.forEach((node) => {
    if (node.type === 'end' && node.transitions?.length) errors.push(`End 节点 ${node.id} 不能有下游`)
    if (node.type !== 'end' && !(node.transitions || []).length) errors.push(`路径在 ${node.id} 处未闭合`)
    ;(node.transitions || []).forEach((transition) => { if (!ids.has(transition.target)) errors.push(`${node.id} 指向不存在的 ${transition.target}`) })
  })
  if (definition.value.entrypoint && ids.has(definition.value.entrypoint)) {
    const reached = new Set(); const stack = [definition.value.entrypoint]
    while (stack.length) { const id = stack.pop(); if (reached.has(id)) continue; reached.add(id); const node = nodes.find((item) => item.id === id); (node?.transitions || []).forEach((edge) => stack.push(edge.target)) }
    nodes.filter((node) => !reached.has(node.id)).forEach((node) => errors.push(`节点 ${node.id} 无法从 START 到达`))
  }
  return [...new Set(errors)]
}

function defaultNode(type, id) {
  const base = { id, name: `${labels[type]} 节点`, type, exclusive: false, join: 'any', transitions: [] }
  if (type === 'action') return { ...base, action: 'context.set', with: { values: { note: 'value' } } }
  if (type === 'approval') return { ...base, with: { requiredRole: 'analyst', message: '请复核处置动作' } }
  if (type === 'delay') return { ...base, delaySeconds: 5 }
  if (type === 'subplaybook') return { ...base, with: { playbookId: 'replace-with-playbook-id', input: {} } }
  if (type === 'loop') return { ...base, with: { maxIterations: 10, iterationVariable: 'iteration' } }
  if (type === 'map') return { ...base, with: { items: [], action: 'notification.create', arguments: { message: '${item}' }, concurrency: 4, maxItems: 100, continueOnError: false } }
  if (type === 'end') return { ...base, result: 'succeeded' }
  return base
}
onMounted(buildFlow)
defineExpose({ issues })
</script>

<style scoped>
.editor-shell { display: grid; grid-template-columns: 190px minmax(620px, 1fr) 300px; height: 680px; border: 1px solid #dbe4ea; border-radius: 10px; overflow: hidden; background: white; }
.palette, .inspector { padding: 14px; overflow-y: auto; background: #f7fafb; }
.palette { border-right: 1px solid #dbe4ea; }.inspector { border-left: 1px solid #dbe4ea; }.palette h4, .inspector h4 { margin: 0; color: #173247; }
.palette-item { width: 100%; display: flex; align-items: center; gap: 9px; margin-top: 8px; padding: 9px 10px; text-align: left; border: 1px solid #d8e2e9; border-left: 3px solid var(--node-color); border-radius: 7px; background: white; color: var(--node-color); cursor: grab; }
.palette-item span { display: flex; flex-direction: column; }.palette-item small { margin-top: 2px; color: #758694; font-size: 10px; }.palette-item:hover { box-shadow: 0 5px 14px rgb(27 64 86 / 10%); transform: translateY(-1px); }
.flow-canvas { position: relative; background-color: #f4f8fa; background-image: radial-gradient(#bbcad4 1px, transparent 1px); background-size: 20px 20px; }
.flow-canvas :deep(.vue-flow) { height: 100%; }.canvas-help { position: absolute; left: 14px; bottom: 12px; z-index: 5; padding: 6px 9px; border-radius: 6px; background: rgb(255 255 255 / 88%); color: #607585; font-size: 11px; pointer-events: none; }
.flow-node { width: 175px; min-height: 72px; padding: 10px 12px; border: 2px solid var(--node-color); border-radius: 9px; background: white; box-shadow: 0 6px 18px rgb(24 57 78 / 12%); }.flow-node.selected { box-shadow: 0 0 0 3px color-mix(in srgb, var(--node-color) 24%, transparent), 0 8px 22px rgb(24 57 78 / 18%); }.flow-node strong, .flow-node code { display: block; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }.flow-node strong { margin: 4px 0; color: #173247; }.flow-node code { color: #6f8190; font-size: 10px; }.node-type { color: var(--node-color); font-size: 10px; font-weight: 800; letter-spacing: .08em; }.kind-start { width: 120px; min-height: 58px; border-radius: 30px; background: #e8f6f1; text-align: center; }.kind-end { border-radius: 24px; background: #f2f4f6; }
.node-handle { width: 11px; height: 11px; border: 2px solid white; }.input-handle { background: #6b8392; }.output-handle { background: var(--node-color); }.issue-list { margin: 5px 0 0; padding-left: 18px; font-size: 11px; }.inspector-title { display: flex; align-items: center; justify-content: space-between; }
</style>
