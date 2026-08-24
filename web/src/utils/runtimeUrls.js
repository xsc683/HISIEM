export function kibanaUrl(configured = import.meta.env.VITE_KIBANA_URL) {
  if (configured) return configured
  const protocol = window.location.protocol === 'https:' ? 'https:' : 'http:'
  return `${protocol}//${window.location.hostname || 'localhost'}:5601/app/discover`
}
