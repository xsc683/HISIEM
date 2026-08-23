import assert from 'node:assert/strict'
import test from 'node:test'
import { appendEdge, appendNode, cloneGraph, moveNode, removeNode } from './soarGraph.js'

function baseGraph() {
  return {
    nodes: [
      { id: 'start', type: 'start', x: 0, y: 0 },
      { id: 'end', type: 'end', x: 500, y: 0 },
    ],
    edges: [{ id: 'start-end', source: 'start', target: 'end', branch: 'next' }],
  }
}

test('连续删除使用最新图快照，之前删除的节点不会恢复', () => {
  let graph = baseGraph()
  for (let index = 1; index <= 5; index += 1) graph = appendNode(graph, { id: `node-${index}`, type: 'business' })
  graph = removeNode(graph, 'node-2')
  graph = removeNode(graph, 'node-4')
  graph = removeNode(graph, 'node-3')
  assert.deepEqual(graph.nodes.map((node) => node.id), ['start', 'end', 'node-1', 'node-5'])
})

test('替换 next 后移动节点不会恢复旧连线', () => {
  let graph = appendNode(baseGraph(), { id: 'condition-1', type: 'condition', x: 200, y: 100 })
  graph = appendEdge(graph, { id: 'start-condition', source: 'start', target: 'condition-1', branch: 'next' }, true)
  graph = moveNode(graph, 'condition-1', { x: 240.4, y: 80.6 })
  assert.deepEqual(graph.edges, [{ id: 'start-condition', source: 'start', target: 'condition-1', branch: 'next' }])
  assert.deepEqual(graph.nodes.find((node) => node.id === 'condition-1'), { id: 'condition-1', type: 'condition', x: 240, y: 81 })
})

test('Vue 响应式代理可以转换为独立 JSON 快照', () => {
  const source = baseGraph()
  const reactiveLike = new Proxy(source, {})
  const snapshot = cloneGraph(reactiveLike)
  snapshot.nodes[0].x = 99
  assert.equal(source.nodes[0].x, 0)
  assert.deepEqual(snapshot.edges, source.edges)
})
