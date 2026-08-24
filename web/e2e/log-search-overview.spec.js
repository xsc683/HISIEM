import { expect, test } from '@playwright/test'

test('日志检索和运营大屏展示 Elasticsearch、告警与案件数据', async ({ page }) => {
  const searchBodies = []
  let raceMode = false
  let raceSearchCount = 0
  let releaseFirst
  const firstResponse = new Promise((resolve) => { releaseFirst = resolve })
  const event = {
    _id: 'event-e2e-1', _index: 'siem-events-2026.08.24', '@timestamp': '2026-08-24T14:10:00Z',
    'event.category': 'authentication', 'event.action': 'authentication_failure',
    'source.ip': '198.51.100.8', 'host.name': 'edge-01', message: 'Failed password for analyst',
  }
  await page.addInitScript(() => {
    localStorage.setItem('siem_token', 'e2e-token')
    localStorage.setItem('siem_tenant', 'default')
  })
  await page.route('**/api/**', async (route) => {
    const request = route.request()
    const path = new URL(request.url()).pathname
    if (!path.startsWith('/api/')) return route.continue()
    const json = (body, status = 200) => route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(body) })
    if (path === '/api/auth/me') return json({ username: 'e2e-admin', role: 'admin', passwordChangeRequired: false })
    if (path === '/api/tenants/mine') return json([{ id: 'default', name: '默认租户' }])
    if (path === '/api/log-search/fields') return json({ fields: [{
      name: 'source.ip', label: '源 IP', type: 'ip',
      operators: ['is', 'exist', 'is_one_of', 'not_is', 'not_exist', 'not_is_one_of'],
    }] })
    if (path === '/api/log-search' && request.method() === 'POST') {
      searchBodies.push(request.postDataJSON())
      if (raceMode) {
        raceSearchCount += 1
        if (raceSearchCount === 1) {
          await firstResponse
          return json({ items: [{ _id: 'old', message: '旧查询结果' }], page: 0, size: 25, total: 1, tookMs: 900 })
        }
        return json({ items: [{ _id: 'new', message: '最新查询结果' }], page: 0, size: 25, total: 1, tookMs: 5 })
      }
      return json({ items: [event], page: 0, size: 25, total: 27, tookMs: 4 })
    }
    if (path === '/api/alerts/summary') return json({
      total: 3, linked: 2, statuses: { open: 1, investigating: 1, closed: 1 }, recent: [
        { _id: 'a-1', 'alert.rule_name': 'SSH 暴力破解', 'alert.status': 'open', 'alert.severity': 'critical', 'alert.risk_score': 92, 'alert.entity': '198.51.100.8', '@timestamp': '2026-08-24T14:10:00Z' },
        { _id: 'a-2', 'alert.rule_name': '异常登录', 'alert.status': 'investigating', 'alert.severity': 'high', 'alert.risk_score': 74, 'alert.case_id': 'c-1', '@timestamp': '2026-08-24T14:08:00Z' },
      ],
    })
    if (path === '/api/cases/summary') return json({ total: 2, statuses: { open: 1, resolved: 1 }, recent: [
      { 'case.id': 'c-1', 'case.title': '外部暴力破解调查', 'case.status': 'open', 'case.updated_at': '2026-08-24T14:11:00Z', alert_ids: ['a-1', 'a-2'], entities: [{ type: 'ip', value: '198.51.100.8' }] },
      { 'case.id': 'c-2', 'case.title': '端口扫描已闭环', 'case.status': 'resolved', 'case.updated_at': '2026-08-24T13:50:00Z', alert_ids: ['a-3'], entities: [] },
    ] })
    return json([])
  })

  await page.goto('/logs')
  await expect(page.getByRole('heading', { name: '日志检索' })).toBeVisible()
  await expect(page.locator('.topbar')).toHaveCount(0)
  const accountTrigger = page.locator('.account-trigger')
  await expect(accountTrigger).toContainText('e2e-admin')
  await expect(accountTrigger).toContainText('默认租户')
  await accountTrigger.click()
  await expect(page.getByRole('button', { name: '修改密码' })).toBeVisible()
  await expect(page.getByRole('button', { name: '退出登录' })).toBeVisible()
  await page.keyboard.press('Escape')
  await expect(page.locator('.account-popover')).not.toBeVisible()
  await page.getByRole('button', { name: '收起导航' }).click()
  await expect(page.locator('.brand-copy')).toHaveCount(0)
  await page.getByRole('button', { name: '展开导航' }).click()
  await expect(page.locator('.brand-copy')).toBeVisible()
  await expect(page.getByText('Failed password for analyst')).toBeVisible()
  await expect(page.getByText('共 27 条').first()).toBeVisible()
  expect(searchBodies[0]).toMatchObject({ page: 0, size: 25, sort: 'desc', logic: 'AND', conditions: [] })
  await page.locator('.field-select').click()
  await page.locator('.ant-select-item-option-content').filter({ hasText: '源 IP' }).click()
  await page.locator('.operator-select').click()
  const relationMenu = page.locator('.ant-select-dropdown').filter({ hasText: '等于（is）' }).last()
  await expect(relationMenu).toBeVisible()
  await expect(relationMenu).not.toContainText('包含（contain）')
  await page.keyboard.press('Escape')
  await page.getByRole('button', { name: '查看 JSON' }).click()
  await expect(page.locator('.log-json')).toContainText('198.51.100.8')

  await page.goto('/overview')
  await expect(page.getByRole('heading', { name: '安全运营态势大屏' })).toBeVisible()
  await expect(page.locator('.metric').filter({ hasText: '24 小时事件' })).toContainText('27')
  await expect(page.locator('.metric').filter({ hasText: '待处置告警' })).toContainText('2')
  await expect(page.locator('.metric').filter({ hasText: '案件闭环率' })).toContainText('50%')
  await expect(page.getByText('SSH 暴力破解')).toBeVisible()
  await expect(page.getByText('外部暴力破解调查')).toBeVisible()
  await page.getByText('运行与治理', { exact: true }).click()
  await expect(page.getByText('Kibana 分析 ↗')).toBeVisible()

  await page.goto('/logs')
  await expect(page.getByText('Failed password for analyst')).toBeVisible()
  raceMode = true
  const searchButton = page.getByRole('button', { name: '检索' })
  await searchButton.click()
  await expect.poll(() => raceSearchCount).toBe(1)
  await page.getByPlaceholder('输入匹配值').press('Enter')
  await expect(page.getByText('最新查询结果')).toBeVisible()
  releaseFirst()
  await page.waitForTimeout(100)
  await expect(page.getByText('最新查询结果')).toBeVisible()
  await expect(page.getByText('旧查询结果')).toHaveCount(0)

  await page.setViewportSize({ width: 390, height: 844 })
  await expect(page.locator('.mobile-header')).toBeVisible()
  await expect(page.getByRole('button', { name: '打开导航' })).toBeVisible()
  await page.getByRole('button', { name: '打开导航' }).click()
  await expect(page.locator('.app-sider.mobile-open')).toBeVisible()
  await expect(page.locator('.mobile-nav-mask')).toBeVisible()
  await expect(page.locator('.account-trigger')).toContainText('e2e-admin')
  await page.locator('.mobile-nav-mask').click({ position: { x: 380, y: 400 } })
  await expect(page.locator('.mobile-nav-mask')).toHaveCount(0)
  const viewportWidths = await page.evaluate(() => ({
    client: document.documentElement.clientWidth,
    scroll: document.documentElement.scrollWidth,
  }))
  expect(viewportWidths.scroll).toBeLessThanOrEqual(viewportWidths.client + 1)
})
