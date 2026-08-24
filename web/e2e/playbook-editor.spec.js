import { expect, test } from '@playwright/test'

const starterGraph = {
  nodes: [
    { id: 'start', name: '开始', type: 'start', config: {}, x: 80, y: 240 },
    { id: 'end', name: '结束', type: 'end', config: {}, x: 620, y: 240 },
  ],
  edges: [{ id: 'start-end', source: 'start', target: 'end', branch: 'next' }],
}

test('新建草稿后可进入设计器，离开保存最新画布且保存失败会阻止导航', async ({ page }) => {
  let revision = 1
  let graph = structuredClone(starterGraph)
  let savedPayload = null
  let rejectSave = false
  await page.addInitScript(() => {
    localStorage.setItem('siem_token', 'e2e-token')
    localStorage.setItem('siem_tenant', 'default')
  })
  await page.route('**/api/**', async (route) => {
    const request = route.request()
    const url = new URL(request.url())
    const path = url.pathname
    if (!path.startsWith('/api/')) return route.continue()
    const json = (body, status = 200) => route.fulfill({
      status,
      contentType: 'application/json',
      body: JSON.stringify(body),
    })
    if (path === '/api/auth/me') return json({ username: 'e2e-admin', role: 'admin', passwordChangeRequired: false })
    if (path === '/api/tenants/mine') return json([{ id: 'default', name: '默认租户' }])
    if (path === '/api/soar/field-dictionary') {
      return json([{ path: 'alert.id', label: '告警 ID', type: 'string', operators: [{ id: 'eq', label: '等于' }] }])
    }
    if (path === '/api/soar/action-dictionary') return json([])
    if (path === '/api/soar/playbooks' && request.method() === 'POST') {
      const body = request.postDataJSON()
      return json({ id: 'pb-e2e', ...body, graph, revision, status: 'draft', enabled: false })
    }
    if (path === '/api/soar/playbooks/pb-e2e' && request.method() === 'GET') {
      return json({ id: 'pb-e2e', name: 'E2E 离开保存', description: '', entryType: 'alert',
        eventTypes: ['alert.created'], graph, revision, status: 'draft', enabled: false })
    }
    if (path === '/api/soar/playbooks/pb-e2e' && request.method() === 'PUT') {
      if (rejectSave) return json({ message: 'E2E 模拟保存失败' }, 503)
      savedPayload = request.postDataJSON()
      graph = structuredClone(savedPayload.graph)
      revision += 1
      return json({ id: 'pb-e2e', ...savedPayload, graph, revision, status: 'draft', enabled: false })
    }
    if (path === '/api/soar/playbooks' && request.method() === 'GET') return json([])
    return json([])
  })

  await page.goto('/soar/playbooks/new')
  await page.getByPlaceholder('例如：高危告警人工复核').fill('E2E 离开保存')
  await page.getByRole('button', { name: '创建草稿并进入设计器' }).click()
  await expect(page).toHaveURL(/\/soar\/playbooks\/pb-e2e\/edit$/)
  await expect(page.getByText('拖动节点调整位置')).toBeVisible()
  await page.locator('.node-palette button').filter({ hasText: '条件判断' }).click()
  await expect(page.locator('.soar-node').filter({ hasText: 'CONDITION' })).toHaveCount(1)
  const beforeUnloadAllowed = await page.evaluate(() => window.dispatchEvent(
    new Event('beforeunload', { cancelable: true }),
  ))
  expect(beforeUnloadAllowed).toBe(false)

  await page.getByRole('button', { name: '返回列表' }).click()
  await expect(page).toHaveURL(/\/soar\/playbooks$/)
  expect(savedPayload).not.toBeNull()
  expect(savedPayload.graph.nodes.some((node) => node.type === 'condition')).toBe(true)
  expect(savedPayload.graph.edges).toEqual(starterGraph.edges)

  rejectSave = true
  await page.goto('/soar/playbooks/pb-e2e/edit')
  await page.locator('.node-palette button[data-node-type="wait"]').click()
  await page.getByRole('button', { name: '返回列表' }).click()
  await expect(page).toHaveURL(/\/soar\/playbooks\/pb-e2e\/edit$/)
  await expect(page.getByText('保存失败', { exact: true })).toBeVisible()
})

test('旧保存响应不会恢复连续删除的节点和关联连线', async ({ page }) => {
  let revision = 7
  let saveCount = 0
  let releaseFirstSave
  let notifyFirstSave
  const firstSaveStarted = new Promise((resolve) => { notifyFirstSave = resolve })
  const firstSaveBlocked = new Promise((resolve) => { releaseFirstSave = resolve })
  const node = (id, name, x, y) => ({
    id, name, type: 'business', config: { action: '', parameters: {} }, x, y,
    policy: { maxAttempts: 0, initialDelaySeconds: 2, backoffMultiplier: 2, maxDelaySeconds: 60 },
  })
  let graph = {
    nodes: [
      starterGraph.nodes[0],
      node('node-1', '动作一', 220, 80),
      node('node-2', '动作二', 420, 80),
      node('node-3', '动作三', 220, 260),
      node('node-4', '动作四', 420, 260),
      node('node-5', '动作五', 320, 440),
      starterGraph.nodes[1],
    ],
    edges: [
      { id: 'edge-start-1', source: 'start', target: 'node-1', branch: 'next' },
      { id: 'edge-1-2', source: 'node-1', target: 'node-2', branch: 'next' },
      { id: 'edge-2-3', source: 'node-2', target: 'node-3', branch: 'next' },
      { id: 'edge-3-4', source: 'node-3', target: 'node-4', branch: 'next' },
      { id: 'edge-4-5', source: 'node-4', target: 'node-5', branch: 'next' },
      { id: 'edge-5-end', source: 'node-5', target: 'end', branch: 'next' },
    ],
  }

  await page.addInitScript(() => {
    localStorage.setItem('siem_token', 'e2e-token')
    localStorage.setItem('siem_tenant', 'default')
  })
  await page.route('**/api/**', async (route) => {
    const request = route.request()
    const path = new URL(request.url()).pathname
    if (!path.startsWith('/api/')) return route.continue()
    const json = (body, status = 200) => route.fulfill({
      status,
      contentType: 'application/json',
      body: JSON.stringify(body),
    })
    if (path === '/api/auth/me') return json({ username: 'e2e-admin', role: 'admin', passwordChangeRequired: false })
    if (path === '/api/tenants/mine') return json([{ id: 'default', name: '默认租户' }])
    if (path === '/api/soar/field-dictionary') return json([])
    if (path === '/api/soar/action-dictionary') return json([])
    if (path === '/api/soar/playbooks/pb-delete' && request.method() === 'GET') {
      return json({ id: 'pb-delete', name: '删除回归', description: '', entryType: 'alert',
        eventTypes: ['alert.created'], graph, revision, status: 'draft', enabled: false })
    }
    if (path === '/api/soar/playbooks/pb-delete' && request.method() === 'PUT') {
      const body = request.postDataJSON()
      saveCount += 1
      if (saveCount === 1) {
        notifyFirstSave()
        await firstSaveBlocked
      }
      graph = structuredClone(body.graph)
      revision += 1
      return json({ id: 'pb-delete', ...body, graph, revision, status: 'draft', enabled: false })
    }
    return json([])
  })

  await page.goto('/soar/playbooks/pb-delete/edit')
  await expect(page.locator('.soar-node')).toHaveCount(7)

  // 先拖动一次触发并阻塞旧快照保存，再继续删除，覆盖真实的自动保存竞态。
  const firstBox = await page.locator('.soar-node').filter({ hasText: '动作一' }).boundingBox()
  await page.mouse.move(firstBox.x + firstBox.width / 2, firstBox.y + firstBox.height / 2)
  await page.mouse.down()
  await page.mouse.move(firstBox.x + firstBox.width / 2 + 30, firstBox.y + firstBox.height / 2 + 20, { steps: 5 })
  await page.mouse.up()
  await firstSaveStarted

  for (const name of ['动作二', '动作四', '动作三']) {
    await page.locator('.soar-node').filter({ hasText: name }).click()
    await page.getByRole('button', { name: '删除节点' }).click()
    await expect(page.locator('.soar-node').filter({ hasText: name })).toHaveCount(0)
  }
  const fifthBox = await page.locator('.soar-node').filter({ hasText: '动作五' }).boundingBox()
  await page.mouse.move(fifthBox.x + fifthBox.width / 2, fifthBox.y + fifthBox.height / 2)
  await page.mouse.down()
  await page.mouse.move(fifthBox.x + fifthBox.width / 2 + 35, fifthBox.y + fifthBox.height / 2 - 20, { steps: 5 })
  await page.mouse.up()
  releaseFirstSave()

  await expect.poll(() => saveCount).toBeGreaterThanOrEqual(2)
  await expect(page.getByText(/已保存 · revision/)).toBeVisible()
  await expect(page.locator('.soar-node')).toHaveCount(4)
  expect(graph.nodes.map((item) => item.id)).toEqual(['start', 'node-1', 'node-5', 'end'])
  expect(graph.edges.map((edge) => edge.id)).toEqual(['edge-start-1', 'edge-5-end'])
})
