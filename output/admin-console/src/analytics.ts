import type { Metric } from './types'

export function isMetric(value: Metric): value is number {
  return typeof value === 'number' && Number.isFinite(value)
}

export function formatNumber(value: Metric, decimals = 0): string {
  return isMetric(value) ? value.toLocaleString('zh-CN', { maximumFractionDigits: decimals }) : '—'
}

export function formatPercent(value: Metric): string {
  return isMetric(value) ? `${(value * 100).toFixed(1)}%` : '—'
}

export function safeRatio(numerator: Metric, denominator: Metric): number | null {
  return isMetric(numerator) && isMetric(denominator) && denominator > 0 ? numerator / denominator : null
}

export function comparison(current: Metric, previous: Metric, rate = false): { label: string; direction: 'up' | 'down' | 'flat' | 'unknown' } {
  if (!isMetric(current) || !isMetric(previous)) return { label: '暂无可比数据', direction: 'unknown' }
  if (rate) {
    const delta = (current - previous) * 100
    return { label: `${delta > 0 ? '+' : ''}${delta.toFixed(1)} 个百分点`, direction: delta > 0 ? 'up' : delta < 0 ? 'down' : 'flat' }
  }
  if (previous === 0) return { label: '前期为 0 · 不可比', direction: 'unknown' }
  const delta = (current - previous) / previous * 100
  return { label: `${delta > 0 ? '+' : ''}${delta.toFixed(1)}%`, direction: delta > 0 ? 'up' : delta < 0 ? 'down' : 'flat' }
}

export function shanghaiDate(now = new Date()): string {
  const shifted = new Date(now.getTime() + 8 * 60 * 60 * 1000)
  return shifted.toISOString().slice(0, 10)
}

export function presetRange(days: number, today = shanghaiDate()): { from: string; to: string } {
  const date = new Date(`${today}T00:00:00Z`)
  date.setUTCDate(date.getUTCDate() - days + 1)
  return { from: date.toISOString().slice(0, 10), to: today }
}

export function validateRange(from: string, to: string, today = shanghaiDate()): string | null {
  const validDate = (value: string) => /^\d{4}-\d{2}-\d{2}$/.test(value) && Number.isFinite(Date.parse(value)) && new Date(value).toISOString().slice(0, 10) === value
  if (!validDate(from) || !validDate(to)) return '请选择完整、有效的起止日期。'
  if (from > to) return '开始日期不能晚于结束日期。'
  if (to > today) return '结束日期不能晚于上海时区的今天。'
  if ((Date.parse(to) - Date.parse(from)) / 86400000 + 1 > 90) return '单次分析最多支持 90 天，请缩短日期区间。'
  return null
}

export function csvText(rows: Record<string, unknown>[], columns?: string[]): string {
  const keys = columns ?? [...new Set(rows.flatMap(row => Object.keys(row)))]
  const escape = (value: unknown) => {
    let text = value == null ? '' : String(value)
    // Values beginning with spreadsheet operators must remain text on import.
    if (/^[\s]*[=+@-]/.test(text)) text = `'${text}`
    return `"${text.replaceAll('"', '""')}"`
  }
  return '\uFEFF' + [keys.map(escape).join(','), ...rows.map(row => keys.map(key => escape(row[key])).join(','))].join('\r\n')
}

export function downloadFile(name: string, text: string, type: string): void {
  const url = URL.createObjectURL(new Blob([text], { type }))
  const anchor = document.createElement('a')
  anchor.href = url
  anchor.download = name
  document.body.appendChild(anchor)
  anchor.click()
  anchor.remove()
  setTimeout(() => URL.revokeObjectURL(url), 1000)
}

export class RequestError extends Error {
  constructor(message: string, public status: number) { super(message) }
}

export async function requestJson<T>(path: string, init: RequestInit = {}): Promise<T> {
  const controller = new AbortController()
  const timeout = setTimeout(() => controller.abort(), 25000)
  try {
    const response = await fetch(`/console/data/v1/${path}`, { ...init, credentials: 'same-origin', cache: 'no-store', signal: controller.signal })
    if (!response.ok) {
      const messages: Record<number, string> = {
        400: '日期区间无效，请检查后重试。',
        401: '登录状态已失效，请重新登录控制台。',
        403: '安全校验未通过。请重新连接后再刷新数据。',
        404: '尚未找到这份快照，请手动拉取最新数据。',
        409: '已有刷新任务运行中，正在等待结果。',
        429: '请求较频繁，请稍后重试。',
      }
      throw new RequestError(messages[response.status] ?? `数据服务暂时不可用（${response.status}），请稍后重试。`, response.status)
    }
    return await response.json() as T
  } catch (error) {
    if (error instanceof RequestError) throw error
    if (error && typeof error === 'object' && 'name' in error && error.name === 'AbortError') throw new RequestError('连接超时，请重新连接。当前已载入的数据仍然保留。', 0)
    throw new RequestError('无法连接数据服务，请检查网络后重试。当前已载入的数据仍然保留。', 0)
  } finally {
    clearTimeout(timeout)
  }
}
