export function landingRoute(role) {
  return role === 'ops' ? '/health' : '/overview'
}

export function canAccessRoles(role, roles) {
  return !Array.isArray(roles) || roles.length === 0 || roles.includes(role)
}
