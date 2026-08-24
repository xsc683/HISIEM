export const LOGIC_OPTIONS = [
  { value: 'AND', label: '满足全部条件（AND）' },
  { value: 'OR', label: '满足任一条件（OR）' },
]

export const OPERATOR_OPTIONS = [
  { value: 'is', label: '等于（is）', needsValue: true },
  { value: 'contain', label: '包含（contain）', needsValue: true },
  { value: 'exist', label: '存在（exist）', needsValue: false },
  { value: 'is_one_of', label: '属于任一值（is one of）', needsValue: true, multiple: true },
  { value: 'not_is', label: '不等于（not is）', needsValue: true },
  { value: 'not_contain', label: '不包含（not contain）', needsValue: true },
  { value: 'not_exist', label: '不存在（not exist）', needsValue: false },
  { value: 'not_is_one_of', label: '不属于任一值（not is one of）', needsValue: true, multiple: true },
]

const OPERATOR_MAP = new Map(OPERATOR_OPTIONS.map((option) => [option.value, option]))

export function createEmptyFilter(id = `${Date.now()}-${Math.random().toString(16).slice(2)}`) {
  return { id, field: undefined, operator: 'is', value: '' }
}

export function operatorMeta(operator) {
  return OPERATOR_MAP.get(operator) || OPERATOR_MAP.get('is')
}

export function normalizeFieldOptions(body) {
  const source = Array.isArray(body) ? body : body?.fields
  if (!Array.isArray(source)) return []
  const seen = new Set()
  return source.flatMap((field) => {
    const name = typeof field === 'string' ? field : field?.name
    if (!name || seen.has(name)) return []
    seen.add(name)
    const type = typeof field === 'string' ? '' : field.type || ''
    const label = typeof field === 'string' ? field : field.label || field.name
    const operators = typeof field === 'string' || !Array.isArray(field.operators)
      ? [] : field.operators.filter((operator) => OPERATOR_MAP.has(operator))
    return [{ value: name, label, type, operators }]
  })
}

export function validateLogFilters(filters = []) {
  for (let index = 0; index < filters.length; index += 1) {
    const filter = filters[index] || {}
    const hasField = Boolean(String(filter.field || '').trim())
    const hasAnyValue = Array.isArray(filter.value)
      ? filter.value.some((item) => String(item).trim())
      : Boolean(String(filter.value ?? '').trim())
    const isBlankRow = !hasField && !hasAnyValue
    if (isBlankRow) continue
    if (!hasField) return `条件 ${index + 1} 尚未选择字段`

    const meta = OPERATOR_MAP.get(filter.operator)
    if (!meta) return `条件 ${index + 1} 的关系无效`
    if (meta.needsValue && !hasAnyValue) return `条件 ${index + 1} 尚未填写匹配值`
  }
  return ''
}

export function buildLogSearchRequest(criteria = {}) {
  const conditions = (criteria.filters || []).flatMap((filter) => {
    const field = String(filter?.field || '').trim()
    const operator = filter?.operator
    const meta = OPERATOR_MAP.get(operator)
    if (!field || !meta) return []

    if (!meta.needsValue) return [{ field, operator }]
    if (meta.multiple) {
      const value = Array.isArray(filter.value)
        ? filter.value.map((item) => String(item).trim()).filter(Boolean)
        : String(filter.value || '').split(',').map((item) => item.trim()).filter(Boolean)
      if (!value.length) return []
      return [{ field, operator, value }]
    }
    const value = String(filter.value ?? '').trim()
    return value ? [{ field, operator, value }] : []
  })

  const request = {
    page: Math.max(0, Number(criteria.page) || 0),
    size: Math.min(200, Math.max(1, Number(criteria.size) || 25)),
    sort: criteria.sort === 'asc' ? 'asc' : 'desc',
    logic: criteria.logic === 'OR' ? 'OR' : 'AND',
    conditions,
  }
  if (criteria.from) request.from = criteria.from
  if (criteria.to) request.to = criteria.to
  return request
}
