import axios from 'axios'

export const apiClient = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL ?? '/api/v1',
  timeout: 15_000,
})

apiClient.interceptors.request.use((config) => {
  config.headers.set('X-Trace-Id', crypto.randomUUID())
  return config
})
