import axios from 'axios'

export const apiClient = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL ?? '/api/v1',
  timeout: 15_000,
  withCredentials: true,
})

apiClient.interceptors.response.use(
  (response) => response,
  (error) => {
    const requestUrl = String(error?.config?.url ?? '')
    const isAuthProbe = requestUrl.endsWith('/auth/login') || requestUrl.endsWith('/auth/me')
    if (error?.response?.status === 401 && !isAuthProbe && globalThis.location.pathname !== '/login') {
      const target = `${globalThis.location.pathname}${globalThis.location.search}`
      globalThis.location.assign(`/login?redirect=${encodeURIComponent(target)}`)
    }
    if (error?.response?.status === 428 && globalThis.location.pathname !== '/settings') {
      globalThis.location.assign('/settings?required=1')
    }
    return Promise.reject(error)
  },
)

apiClient.interceptors.request.use((config) => {
  config.headers.set('X-Trace-Id', crypto.randomUUID())
  return config
})
