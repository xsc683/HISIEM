export function cloneGraph(graph) {
  // Vue 将 v-model 对象包装为 Proxy；structuredClone(Proxy) 会在浏览器中抛 DataCloneError。
  // Playbook 图是纯 JSON 契约，JSON 快照既能剥离响应式代理，也与后端持久化边界一致。
  return JSON.parse(JSON.stringify(graph || { nodes: [], edges: [] }))
}

export function appendNode(graph, node) {
  return { ...graph, nodes: [...graph.nodes, node] }
}

export function updateNode(graph, node) {
  return { ...graph, nodes: graph.nodes.map((item) => item.id === node.id ? node : item) }
}

export function moveNode(graph, nodeId, position) {
  return {
    ...graph,
    nodes: graph.nodes.map((item) => item.id === nodeId
      ? { ...item, x: Math.round(position.x), y: Math.round(position.y) }
      : item),
  }
}

export function removeNode(graph, nodeId) {
  return {
    ...graph,
    nodes: graph.nodes.filter((item) => item.id !== nodeId),
    edges: graph.edges.filter((edge) => edge.source !== nodeId && edge.target !== nodeId),
  }
}

export function appendEdge(graph, edge, replaceOutgoing = false) {
  const edges = replaceOutgoing ? graph.edges.filter((item) => item.source !== edge.source) : graph.edges
  return { ...graph, edges: [...edges, edge] }
}

export function removeEdge(graph, edgeId) {
  return { ...graph, edges: graph.edges.filter((edge) => edge.id !== edgeId) }
}
