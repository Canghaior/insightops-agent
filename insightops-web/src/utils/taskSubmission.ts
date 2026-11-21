import { ref } from 'vue'
import { evidenceError } from '@/api/buildEvidence'

/** Keep the original receipt and file identity when a transport failure hides a committed write. */
export function useTaskSubmission() {
  const busy = ref(false)
  const unconfirmed = ref(false)
  const error = ref('')
  let last: { key: string; file: File | null; requestId: string } | null = null

  async function submit<T>(key: string, file: File | null, write: (id: string) => Promise<T>, onConflict: () => void) {
    if (busy.value) return
    if (!last || last.key !== key || last.file !== file) {
      if (unconfirmed.value) return
      last = { key, file, requestId: globalThis.crypto.randomUUID() }
    }
    busy.value = true
    error.value = ''
    try {
      const saved = await write(last.requestId)
      unconfirmed.value = false
      return saved
    } catch (cause) {
      const status = (cause as { response?: { status?: number } }).response?.status
      unconfirmed.value = status === undefined || status === 408 || status >= 500
      if (unconfirmed.value) error.value = '保存结果尚未确认。可以用同一请求再次核对，避免生成重复版本。'
      else error.value = evidenceError(cause, '未能保存，请核对表单和资料后重试。')
      if (status === 409) onConflict()
    } finally { busy.value = false }
  }
  return { busy, error, unconfirmed, submit }
}
