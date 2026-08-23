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
  await page.locator('.node-palette button').filter({ hasText: '等待' }).click()
  await page.getByRole('button', { name: '返回列表' }).click()
  await expect(page).toHaveURL(/\/soar\/playbooks\/pb-e2e\/edit$/)
  await expect(page.getByText('保存失败', { exact: true })).toBeVisible()
})
