// 前端页面路由表。页面使用 pathname，保留 /#/xxx 旧链接的兼容解析。
export const ROUTES = {
  wizard: '/wizard',
  rules: '/rules',
  alerts: '/alerts',
  cases: '/cases',
  soar: '/soar',
  health: '/health',
  'ops-health': '/ops/health',
  criticality: '/criticality',
  notify: '/notifications',
  rbac: '/rbac',
}

export function routeKeyFromPath(pathname = window.location.pathname) {
  const normalized = pathname.replace(/\/$/, '') || '/'
  const found = Object.entries(ROUTES).find(([, path]) => normalized === path)
  return found?.[0] || 'wizard'
}

export function pathFromRouteKey(key) {
  return ROUTES[key] || ROUTES.wizard
}
