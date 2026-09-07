import { existsSync, readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, describe, expect, it, vi } from 'vitest'
import App from '../output/admin-console/src/App.vue'
import type { ConsoleState, Snapshot } from '../output/admin-console/src/types'

// Optional local smoke input: generated aggregate JSON stays in the ignored .deploy
// directory. CI/other checkouts without it skip this test; no production values are
// committed, snapshotted or printed. Override with ELMA_CONSOLE_SMOKE_FIXTURE.
const fixturePath = process.env.ELMA_CONSOLE_SMOKE_FIXTURE
  ?? resolve(__dirname, '../output/admin-console/.deploy/production-smoke.json')
const available = existsSync(fixturePath)

afterEach(() => {
  vi.restoreAllMocks()
  vi.unstubAllGlobals()
  document.body.innerHTML = ''
  localStorage.clear()
})

describe.skipIf(!available)('admin console with an optional local aggregate snapshot', () => {
  it('renders all six analysis topics, preserves mapping counts, opens history and exports without runtime errors', async () => {
    const data = JSON.parse(readFileSync(fixturePath, 'utf8')) as Snapshot
    const current: Snapshot = { ...data, id: data.id || 'local-smoke-current' }
    const historical: Snapshot = { ...data, id: 'local-smoke-history' }
    const state: ConsoleState = {
      csrfToken: 'local-test-token', refresh: { running: false, error: null }, currentId: current.id,
      history: [current, historical].map(snapshot => ({ id: snapshot.id,
        snapshotAt: snapshot.meta.snapshotAt, from: snapshot.meta.periodStart, to: snapshot.meta.periodEnd })),
    }
    const fetchMock = vi.fn(async (input: RequestInfo | URL) => {
      const url = new URL(String(input), 'http://localhost')
      if (url.pathname === '/console/data/v1/state') return { ok: true, json: async () => state }
      if (url.pathname === '/console/data/v1/snapshot') {
        const snapshot = url.searchParams.get('id') === historical.id ? historical : current
        return { ok: true, json: async () => snapshot }
      }
      throw new Error('Unexpected endpoint in local aggregate smoke test')
    })
    vi.stubGlobal('fetch', fetchMock)
    const createUrl = vi.fn(() => 'blob:local-console-export')
    vi.stubGlobal('URL', Object.assign(class extends URL {}, {
      createObjectURL: createUrl, revokeObjectURL: vi.fn(),
    }))
    const download = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => {})
    const consoleError = vi.spyOn(console, 'error').mockImplementation(() => {})
    const errors: unknown[] = []
    const wrapper = mount(App, {
      global: { config: { errorHandler: error => errors.push(error) } } })
    const assertHealthy = () => {
      expect(errors.length, 'Vue render/event handlers must not throw').toBe(0)
      expect(consoleError).not.toHaveBeenCalled()
      expect(/\b(?:NaN|undefined|Infinity)\b/.test(wrapper.html()),
        'Rendered text and SVG/chart attributes must contain finite values').toBe(false)
    }
    const clickButton = async (label: string) => {
      const button = wrapper.findAll('button').find(item => item.text() === label)
      expect(Boolean(button), 'Required console action must exist').toBe(true)
      await button!.trigger('click')
      await flushPromises()
    }
    try {
      await flushPromises()
      expect(wrapper.findAll('.main-nav button')).toHaveLength(6)
      expect(wrapper.find('.snapshot-strip').exists()).toBe(true)
      for (const topic of ['运营总览', '使用与留存', '推荐效果', '用户地图', '证据质量', '数据管理']) {
        const navigation = wrapper.findAll('.main-nav button').find(button => button.text() === topic)
        expect(Boolean(navigation)).toBe(true)
        await navigation!.trigger('click')
        await flushPromises()
        expect(wrapper.get('h1').text()).toBe(topic)
        assertHealthy()

        if (topic === '用户地图') {
          expect(wrapper.get('.ranking-heading h3').text()).toBe('城市排名')
          expect(wrapper.findAll('.rank-row').length).toBe(current.locations.points.length)
          if (current.locations.points.length) {
            await wrapper.findAll('.rank-row')[0].trigger('click')
            expect(wrapper.find('.geo-detail').exists()).toBe(true)
            await wrapper.get('.geo-heading button').trigger('click')
          }
        }

        if (topic === '证据质量') {
          const quality = current.analytics?.quality
          expect(Boolean(quality), 'Live aggregate must include quality data').toBe(true)
          const panel = wrapper.findAll('.panel').find(item => item.find('h2').text() === '映射状态分布')!
          // Check actual source statuses, including rated/unrated/NO_MATCH when present.
          for (const row of quality!.mappingStatuses) {
            const rendered = panel.findAll('.data-bars li').find(item => item.text().includes(row.status))
            expect(Boolean(rendered), 'Every returned mapping status must render').toBe(true)
            expect(rendered!.get('strong').text() === row.count.toLocaleString('zh-CN'),
              'Mapping count must match the selected aggregate snapshot').toBe(true)
          }
          const unrated = quality!.mappingStatuses.find(row => row.status === 'MATCHED_WITHOUT_RATING')
          if (unrated) {
            const card = wrapper.findAll('.metric-card').find(item => item.get('.metric-label').text() === '已匹配但无评分')!
            expect(card.get('.metric-value').text() === unrated.count.toLocaleString('zh-CN'),
              'Unrated matches must use the split mapping status count').toBe(true)
          }
        }
        assertHealthy()
      }

      const historyButton = wrapper.findAll('button').find(button => button.text() === '查看快照')
      expect(Boolean(historyButton)).toBe(true)
      await historyButton!.trigger('click')
      await flushPromises()
      expect(wrapper.get('.snapshot-tag').text()).toBe('历史快照')
      await clickButton('导出完整 JSON')
      expect(createUrl).toHaveBeenCalledWith(expect.any(Blob))
      expect(download).toHaveBeenCalled()
      expect(fetchMock.mock.calls.some(([input]) => String(input).includes(encodeURIComponent(historical.id)))).toBe(true)
      assertHealthy()
    } finally {
      wrapper.unmount()
    }
  })
})
