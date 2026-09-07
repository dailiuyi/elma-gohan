import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { afterEach, describe, expect, it, vi } from 'vitest'
import App from '../output/admin-console/src/App.vue'
import TrendChart from '../output/admin-console/src/components/TrendChart.vue'
import { comparison, csvText, formatNumber, formatPercent, isMetric, presetRange,
  requestJson, RequestError, safeRatio, shanghaiDate, validateRange } from '../output/admin-console/src/analytics'
import type { ConsoleState, Snapshot } from '../output/admin-console/src/types'

const mounted: VueWrapper[] = []
afterEach(() => {
  mounted.splice(0).forEach(wrapper => wrapper.unmount())
  vi.clearAllTimers()
  vi.useRealTimers()
  vi.restoreAllMocks()
  vi.unstubAllGlobals()
  localStorage.clear()
})

function snapshot(id = 'sample-current', day = '2026-09-01'): Snapshot {
  return {
    id, meta: { snapshotAt: `${day}T18:00:00+08:00`, periodStart: day, periodEnd: day,
      windowDays: 1, timezone: 'Asia/Shanghai', sourceMode: 'fixture', readOnlyVerified: false,
      queryCount: 1, queryDurationMs: 1, warnings: [] },
    overview: { totalRecommendations: 3, totalAnonymousIds: 2, totalRestaurants: 2,
      totalFeedbacks: 1, periodRecommendations: 3, periodActiveIds: 2, periodNewIds: 1,
      periodReturningIds: 1, averageCandidateCount: 4 },
    funnel: { recommendationSessions: 3, acceptedSessions: null, navigatedSessions: null,
      feedbackSessions: 1, feedbackCount: 1, dislikedSessions: 0,
      acceptanceRate: null, navigationRate: null, feedbackRate: 1 / 3 },
    daily: [{ metricDate: day, recommendations: 3, activeIds: 2, newIds: 1,
      accepts: null, navigations: null, rerolls: null, feedbacks: 1, dislikes: 0 }],
    behaviors: [], feedback: [{ result: 'LIKE', feedbackCount: 1 }], risks: [], categories: [],
    algorithms: [], evidenceReliability: {}, tableRows: { recommendation_log: 3 },
    locations: { totalAnonymousIds: 2, totalRequests: 3, points: [], unmappedRequests: 3 },
    capabilities: { behaviorMetrics: false }, analytics: { heatmap: [], retention: [],
      quality: { mappingStatuses: [], deepStatuses: [], totalMappings: null,
        mappingsWithRatings: null, freshMappings: null } },
  }
}

function stateFor(current: Snapshot | null): ConsoleState {
  return { csrfToken: 'test-token', refresh: { running: false, error: null },
    currentId: current?.id ?? null, history: current ? [{ id: current.id,
      snapshotAt: current.meta.snapshotAt, from: current.meta.periodStart, to: current.meta.periodEnd }] : [] }
}

const response = (body: unknown, status = 200) => ({ ok: status >= 200 && status < 300,
  status, json: async () => JSON.parse(JSON.stringify(body)) })

async function open(current: Snapshot | null = snapshot()) {
  vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout', 'Date'] })
  vi.setSystemTime(new Date('2026-09-07T04:00:00Z'))
  const server = { state: stateFor(current), current, postStatus: 202, stateFailure: false,
    history: new Map<string, Snapshot>(), calls: [] as { path: string; method: string; body?: string }[] }
  const fetch = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = new URL(String(input), 'http://localhost')
    server.calls.push({ path: url.pathname + url.search, method: init?.method ?? 'GET', body: init?.body as string | undefined })
    if (url.pathname.endsWith('/state')) {
      if (server.stateFailure) throw new TypeError('local simulated offline')
      return response(server.state)
    }
    if (url.pathname.endsWith('/refresh')) {
      if (server.postStatus === 202 || server.postStatus === 409) server.state.refresh = { running: true, error: null }
      return response({}, server.postStatus)
    }
    if (url.pathname.endsWith('/snapshot')) {
      const item = url.searchParams.has('id') ? server.history.get(url.searchParams.get('id')!) : server.current
      return response(item ?? {}, item ? 200 : 404)
    }
    return response({}, 404)
  })
  vi.stubGlobal('fetch', fetch)
  const errors: unknown[] = []
  const wrapper = mount(App, { global: { config: { errorHandler: error => errors.push(error) } } })
  mounted.push(wrapper)
  await flushPromises()
  return { wrapper, server, fetch, errors }
}

async function click(wrapper: VueWrapper, text: string) {
  const button = wrapper.findAll('button').find(item => item.text() === text || item.text().endsWith(text))
  expect(Boolean(button), `Button exists: ${text}`).toBe(true)
  await button!.trigger('click')
  await flushPromises()
}

describe('console metric, date and export helpers', () => {
  it('distinguishes missing and non-finite values from real zero', () => {
    for (const value of [null, undefined, NaN, Infinity]) {
      expect(isMetric(value)).toBe(false)
      expect(formatNumber(value)).toBe('—')
      expect(formatPercent(value)).toBe('—')
    }
    expect(formatNumber(0)).toBe('0')
    expect(formatPercent(0)).toBe('0.0%')
    expect(formatNumber(1234.56, 1)).toBe('1,234.6')
  })

  it('requires meaningful denominators and separates relative change from percentage points', () => {
    expect(safeRatio(0, 0)).toBeNull()
    expect(safeRatio(null, 3)).toBeNull()
    expect(safeRatio(2, 4)).toBe(0.5)
    expect(comparison(4, 2)).toEqual({ label: '+100.0%', direction: 'up' })
    expect(comparison(0.4, 0.2, true)).toEqual({ label: '+20.0 个百分点', direction: 'up' })
    expect(comparison(2, 0).direction).toBe('unknown')
    expect(comparison(null, 0.2, true).direction).toBe('unknown')
  })

  it('uses Shanghai date rollover and inclusive preset windows', () => {
    expect(shanghaiDate(new Date('2026-09-06T15:59:59Z'))).toBe('2026-09-06')
    expect(shanghaiDate(new Date('2026-09-06T16:00:00Z'))).toBe('2026-09-07')
    expect(presetRange(7, '2026-09-07')).toEqual({ from: '2026-09-01', to: '2026-09-07' })
    expect(validateRange('2026-06-10', '2026-09-07', '2026-09-07')).toBeNull()
    for (const [from, to] of [['2026-06-09', '2026-09-07'], ['2026-09-08', '2026-09-08'],
      ['2026-09-03', '2026-09-01'], ['2026-02-30', '2026-03-01'], ['', '2026-09-01']]) {
      expect(validateRange(from, to, '2026-09-07')).not.toBeNull()
    }
  })

  it('exports UTF-8 CSV with quoted newlines and formula-injection protection', () => {
    const csv = csvText([{ name: '=HYPERLINK("example")', note: 'first\nsecond', missing: null },
      { name: '  @formula', note: 'with,comma', missing: 0 }])
    expect(csv.startsWith('\uFEFF')).toBe(true)
    expect(csv).toContain('"\'=HYPERLINK(""example"")"')
    expect(csv).toContain('"\'  @formula"')
    expect(csv).toContain('"first\nsecond"')
    expect(csv).toContain('"with,comma","0"')
    expect(csv).not.toContain('undefined')
  })

  it.each([401, 403, 404, 409, 429, 503])('preserves HTTP status %s for retry handling', async status => {
    vi.stubGlobal('fetch', vi.fn(async () => response({}, status)))
    await expect(requestJson('state')).rejects.toMatchObject({ status })
  })

  it('turns abort and network failure into retryable status zero', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => { throw new DOMException('aborted', 'AbortError') }))
    await expect(requestJson('state')).rejects.toMatchObject({ status: 0, message: expect.stringContaining('超时') })
    vi.stubGlobal('fetch', vi.fn(async () => { throw new TypeError('offline') }))
    await expect(requestJson('state')).rejects.toBeInstanceOf(RequestError)
  })
})

describe('console refresh and snapshot state', () => {
  it('offers first refresh without requesting a nonexistent snapshot', async () => {
    const { wrapper, server, errors } = await open(null)
    expect(wrapper.find('.empty-state').exists()).toBe(true)
    expect(wrapper.text()).toContain('先创建一份数据快照')
    expect(server.calls.some(call => call.path.endsWith('/snapshot'))).toBe(false)
    await click(wrapper, '生成首份快照')
    expect(server.calls.filter(call => call.method === 'POST')).toHaveLength(1)
    expect(wrapper.text()).toContain('首次快照生成后将自动显示分析结果')
    expect(errors).toEqual([])
  })

  it('keeps old snapshot and its date during refresh, then replaces both on completion', async () => {
    const { wrapper, server, errors } = await open()
    await wrapper.get('input[name="from"]').setValue('2026-09-02')
    await wrapper.get('input[name="to"]').setValue('2026-09-02')
    await wrapper.get('form.date-toolbar').trigger('submit')
    await flushPromises()
    expect(wrapper.get('.snapshot-strip').text()).toContain('2026-09-01 — 2026-09-01')
    expect(wrapper.get('.snapshot-strip').text()).toContain('尚未应用')
    const posted = server.calls.find(call => call.method === 'POST')!
    expect(JSON.parse(posted.body!)).toEqual({ from: '2026-09-02', to: '2026-09-02' })
    expect(wrapper.get('.heading-actions .button--primary').attributes('disabled')).toBeDefined()
    server.current = snapshot('sample-next', '2026-09-02')
    server.state = stateFor(server.current)
    await vi.advanceTimersByTimeAsync(2000)
    await flushPromises()
    expect(wrapper.get('.snapshot-strip').text()).toContain('2026-09-02 — 2026-09-02')
    expect(wrapper.find('.pending-range').exists()).toBe(false)
    expect(wrapper.text()).toContain('最新快照已载入')
    expect(errors).toEqual([])
  })

  it('retains the old snapshot when the background refresh fails', async () => {
    const { wrapper, server } = await open()
    await click(wrapper, '手动拉取数据')
    server.state.refresh = { running: false, error: '模拟统计超时，旧快照保留' }
    await vi.advanceTimersByTimeAsync(2000)
    await flushPromises()
    expect(wrapper.text()).toContain('模拟统计超时，旧快照保留')
    expect(wrapper.get('.snapshot-strip').text()).toContain('2026-09-01')
    expect(server.calls.filter(call => call.path.endsWith('/snapshot'))).toHaveLength(1)
    expect(wrapper.get('.heading-actions .button--primary').attributes('disabled')).toBeUndefined()
  })

  it('joins an existing refresh after 409 without launching another POST', async () => {
    const { wrapper, server } = await open()
    server.postStatus = 409
    await click(wrapper, '手动拉取数据')
    expect(wrapper.text()).toContain('数据刷新中')
    await vi.advanceTimersByTimeAsync(2000)
    await flushPromises()
    expect(server.calls.filter(call => call.method === 'POST')).toHaveLength(1)
    expect(server.calls.filter(call => call.path.endsWith('/state')).length).toBeGreaterThan(1)
    server.state.refresh.running = false
    await vi.advanceTimersByTimeAsync(2000)
    await flushPromises()
    expect(wrapper.text()).toContain('最新快照已载入')
  })

  it('keeps old data offline and reconnects to a refreshed CSRF token', async () => {
    const { wrapper, server, fetch } = await open()
    await click(wrapper, '手动拉取数据')
    server.stateFailure = true
    await vi.advanceTimersByTimeAsync(2000)
    await flushPromises()
    expect(wrapper.text()).toContain('连接需要重试')
    expect(wrapper.get('.snapshot-strip').text()).toContain('2026-09-01')
    server.stateFailure = false
    server.state.refresh.running = false
    server.state.csrfToken = 'reconnected-token'
    await click(wrapper, '重新连接')
    expect(wrapper.text()).not.toContain('连接需要重试')
    await click(wrapper, '手动拉取数据')
    const posts = fetch.mock.calls.filter(([, init]) => init?.method === 'POST')
    expect((posts.at(-1)![1]!.headers as Record<string, string>)['X-ELMA-CSRF']).toBe('reconnected-token')
  })

  it('shows forbidden refresh error and preserves visible data', async () => {
    const { wrapper, server } = await open()
    server.postStatus = 403
    await click(wrapper, '手动拉取数据')
    expect(wrapper.text()).toContain('安全校验未通过')
    expect(wrapper.get('.snapshot-strip').text()).toContain('2026-09-01')
  })

  it('rejects an invalid interval locally without issuing a refresh', async () => {
    const { wrapper, server } = await open()
    await wrapper.get('input[name="from"]').setValue('2026-09-03')
    await wrapper.get('input[name="to"]').setValue('2026-09-01')
    await wrapper.get('form.date-toolbar').trigger('submit')
    await flushPromises()
    expect(wrapper.get('.form-error').text()).toContain('开始日期不能晚于结束日期')
    expect(server.calls.filter(call => call.method === 'POST')).toHaveLength(0)
  })

  it('switches all displayed dates to selected history without changing the pending form range', async () => {
    const { wrapper, server } = await open()
    const historic = snapshot('sample-history', '2026-08-30')
    server.history.set(historic.id, historic)
    server.state.history.push(stateFor(historic).history[0])
    // Reconnect reloads the server history list, just as a page reload would.
    server.stateFailure = true
    await click(wrapper, '手动拉取数据')
    await vi.advanceTimersByTimeAsync(2000)
    await flushPromises()
    server.stateFailure = false
    server.state.refresh.running = false
    await click(wrapper, '重新连接')
    await click(wrapper, '数据管理')
    await click(wrapper, '查看快照')
    expect(wrapper.get('.snapshot-tag').text()).toBe('历史快照')
    expect(wrapper.get('.snapshot-strip').text()).toContain('2026-08-30 — 2026-08-30')
    expect((wrapper.get('input[name="from"]').element as HTMLInputElement).value).toBe('2026-09-01')
    await click(wrapper, '运营总览')
    expect(wrapper.get('.snapshot-strip').text()).toContain('2026-08-30')
  })

  it('renders unavailable behavior as dashes and completed empty heatmap buckets as zero', async () => {
    const { wrapper, errors } = await open()
    await click(wrapper, '推荐效果')
    const acceptance = wrapper.findAll('.metric-card').find(card => card.get('.metric-label').text() === '接受率')!
    expect(acceptance.get('.metric-value').text()).toBe('—')
    await click(wrapper, '使用与留存')
    expect(wrapper.findAll('.heat-cell')).toHaveLength(168)
    expect(wrapper.findAll('.heat-cell').every(cell => cell.attributes('aria-label')?.includes('0 次推荐'))).toBe(true)
    expect(wrapper.html()).not.toMatch(/\b(?:NaN|undefined)\b/)
    expect(errors).toEqual([])
  })
})

describe('trend chart exact-value interaction', () => {
  it('selects exact dates with arrows and Home/End, and clears with Escape', async () => {
    const rows = [
      { date: '2026-09-01', value: 2, secondary: 1 },
      { date: '2026-09-02', value: null, secondary: 0 },
      { date: '2026-09-03', value: 4, secondary: null },
    ]
    const wrapper = mount(TrendChart, { props: { rows, label: '请求', secondaryLabel: '反馈' } })
    mounted.push(wrapper)
    const chart = wrapper.get('.chart-interactive')
    await chart.trigger('keydown', { key: 'ArrowRight' })
    expect(wrapper.get('.chart-tooltip').text()).toContain(rows[0].date)
    await chart.trigger('keydown', { key: 'ArrowRight' })
    expect(wrapper.get('.chart-tooltip').text()).toContain(rows[1].date)
    expect(wrapper.get('.chart-tooltip').text()).toContain('请求 —')
    await chart.trigger('keydown', { key: 'End' })
    expect(wrapper.get('.chart-tooltip').text()).toContain(rows[2].date)
    await chart.trigger('keydown', { key: 'ArrowRight' })
    expect(wrapper.get('.chart-tooltip').text()).toContain(rows[2].date)
    await chart.trigger('keydown', { key: 'ArrowLeft' })
    expect(wrapper.get('.chart-tooltip').text()).toContain(rows[1].date)
    await chart.trigger('keydown', { key: 'Home' })
    expect(wrapper.get('.chart-tooltip').text()).toContain(rows[0].date)
    await chart.trigger('keydown', { key: 'Escape' })
    expect(wrapper.find('.chart-tooltip').exists()).toBe(false)
    expect(wrapper.find('.chart-focus-line').exists()).toBe(false)
  })

  it('breaks the line across missing values and exposes zero separately in its data table', async () => {
    const rows = [
      { date: '2026-09-01', value: 2 }, { date: '2026-09-02', value: null },
      { date: '2026-09-03', value: 4 }, { date: '2026-09-04', value: 0 },
    ]
    const wrapper = mount(TrendChart, { props: { rows, label: '请求' } })
    mounted.push(wrapper)
    const path = wrapper.get('.chart-line').attributes('d') ?? ''
    expect(path.match(/M/g)).toHaveLength(2)
    expect(path.match(/L/g)).toHaveLength(1)
    expect(wrapper.findAll('.chart-dot')).toHaveLength(3)
    await wrapper.get('.chart-legend button').trigger('click')
    const cells = wrapper.findAll('tbody tr').map(row => row.get('td').text())
    expect(cells).toEqual(['2', '—', '4', '0'])
    await wrapper.setProps({ rows: [{ date: '2026-09-01', value: null }] })
    expect(wrapper.find('.chart-interactive').exists()).toBe(false)
    expect(wrapper.text()).toContain('暂无趋势数据')
  })
})
