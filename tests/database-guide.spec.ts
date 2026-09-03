import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'

import { JSDOM, VirtualConsole } from 'jsdom'
import { describe, expect, it } from 'vitest'

const root = resolve(__dirname, '..')
const guidePath = resolve(root, 'output/database-guide/index.html')
const fixturePath = resolve(root, 'tests/database_guide/fixtures/sample_snapshot.json')
const template = readFileSync(guidePath, 'utf8')
const fixture = JSON.parse(readFileSync(fixturePath, 'utf8'))

const startMarker = '<!-- ELMA_DASHBOARD_DATA_START -->'
const endMarker = '<!-- ELMA_DASHBOARD_DATA_END -->'

function safeJson(value: unknown): string {
  return JSON.stringify(value)
    .replaceAll('&', '\\u0026')
    .replaceAll('<', '\\u003c')
    .replaceAll('>', '\\u003e')
    .replaceAll('\u2028', '\\u2028')
    .replaceAll('\u2029', '\\u2029')
}

function withSnapshot(snapshot: unknown): string {
  const start = template.indexOf(startMarker)
  const end = template.indexOf(endMarker)
  return `${template.slice(0, start)}${startMarker}
  <script id="elma-dashboard-data" type="application/json">${safeJson(snapshot)}</script>
  ${endMarker}${template.slice(end + endMarker.length)}`
}

async function openHtml(html: string) {
  const errors: string[] = []
  const virtualConsole = new VirtualConsole()
  virtualConsole.on('jsdomError', error => errors.push(String(error)))
  virtualConsole.on('error', error => errors.push(String(error)))
  const dom = new JSDOM(html, {
    runScripts: 'dangerously',
    pretendToBeVisual: true,
    url: 'file:///elma-dashboard.html',
    virtualConsole,
  })
  await new Promise(resolvePromise => dom.window.setTimeout(resolvePromise, 30))
  return { dom, errors }
}

async function openDashboard(snapshot: unknown = fixture) {
  return openHtml(withSnapshot(snapshot))
}

describe('offline database dashboard', () => {
  it('remains a self-contained file and preserves all database guide modules', () => {
    expect(template).toContain('connect-src \'none\'')
    expect(template).not.toMatch(/<script\b[^>]*\bsrc\s*=/i)
    expect(template).not.toMatch(/<link\b[^>]*\brel\s*=\s*["']?stylesheet/i)
    expect(template).not.toMatch(/\bfetch\s*\(/i)
    expect(template).not.toMatch(/\bXMLHttpRequest\b|\bWebSocket\b/)
    expect(template).not.toMatch(/SELECT\s+(?:\w+\.)?\s*\*/i)
    expect(template).toContain('@media (prefers-reduced-motion: reduce)')

    for (const view of ['schema', 'catalog', 'locations', 'guide', 'queries']) {
      expect(template).toContain(`id="${view}-view"`)
      expect(template).toContain(`data-view="${view}"`)
    }
    expect(template).toContain('ELMA Gohan 数据库表关系图')
    expect(template).toContain('人工连接')
    expect(template).toContain('常用查询')
    expect(template).toContain('THIRD_PARTY_NOTICES.md')
  })

  it('executes the currently embedded snapshot without script errors', async () => {
    const { dom, errors } = await openHtml(template)
    const { document } = dom.window
    document.querySelector<HTMLButtonElement>('[data-view="locations"]')!.click()
    const labels = [...document.querySelectorAll('.location-bubble text')]
    expect(labels.length).toBeGreaterThan(0)
    expect(labels.every(label => /^.+(?:市|州|盟|地区|特别行政区)$/.test(label.textContent || ''))).toBe(true)
    expect(labels.every(label => !/[\d/]/.test(label.textContent || ''))).toBe(true)
    const boxes = [...document.querySelectorAll<SVGRectElement>('.location-label-bg')].map(rect => ({
      left: Number(rect.getAttribute('x')),
      top: Number(rect.getAttribute('y')),
      right: Number(rect.getAttribute('x')) + Number(rect.getAttribute('width')),
      bottom: Number(rect.getAttribute('y')) + Number(rect.getAttribute('height')),
    }))
    for (const [index, first] of boxes.entries()) {
      for (const [offset, second] of boxes.slice(index + 1).entries()) {
        const overlaps = first.left < second.right && first.right > second.left
          && first.top < second.bottom && first.bottom > second.top
        expect(
          overlaps,
          `${labels[index].textContent} overlaps ${labels[index + offset + 1].textContent}`,
        ).toBe(false)
      }
    }
    expect(errors).toEqual([])
    expect(document.querySelector('main')?.textContent).not.toMatch(
      /\b(?:NaN|Infinity|undefined)\b/,
    )
    dom.window.close()
  })

  it('renders animated charts from aggregate data without script errors', async () => {
    const { dom, errors } = await openDashboard()
    const { document } = dom.window

    expect(errors).toEqual([])
    expect(document.querySelector('#overview-view')?.hasAttribute('hidden')).toBe(false)
    expect(document.querySelectorAll('#trend-chart .trend-line').length).toBeGreaterThan(0)
    expect(document.querySelectorAll('#behavior-bars .fill').length).toBe(fixture.behaviors.length)
    expect(document.querySelectorAll('#feedback-chart .donut-segment').length).toBe(fixture.feedback.length)
    expect(document.querySelectorAll('#algorithm-table-body tr').length).toBe(fixture.algorithms.length)
    expect(document.querySelector('#snapshot-status-dot')?.classList.contains('is-ok')).toBe(true)
    expect(document.querySelector('main')?.textContent).not.toMatch(/\b(?:NaN|Infinity|undefined)\b/)

    const usersButton = document.querySelector<HTMLButtonElement>('[data-trend-mode="users"]')!
    usersButton.click()
    expect(usersButton.getAttribute('aria-pressed')).toBe('true')
    expect(document.querySelectorAll('#trend-chart .trend-line')).toHaveLength(2)
    dom.window.close()
  })

  it('opens the embedded map, zooms, switches metrics, and resets without losing data', async () => {
    const { dom, errors } = await openDashboard()
    const { document, KeyboardEvent, MouseEvent } = dom.window
    document.querySelector<HTMLButtonElement>('[data-view="locations"]')!.click()

    const map = document.querySelector<SVGElement>('#user-location-map')!
    expect(document.querySelector('#locations-view')?.hasAttribute('hidden')).toBe(false)
    expect(document.querySelectorAll('.location-bubble')).toHaveLength(fixture.locations.points.length)
    expect([...document.querySelectorAll('.location-bubble text')].map(label => label.textContent)).toEqual(
      fixture.locations.points.map((point: { label: string }) => point.label),
    )
    expect(document.querySelectorAll('.location-label-bg')).toHaveLength(fixture.locations.points.length)
    expect(document.querySelector('#locations-view')?.textContent).not.toMatch(/网格\s+\d/)
    expect(map.getAttribute('viewBox')).toBe('0 0 860 620')

    document.querySelector<HTMLButtonElement>('[data-map-action="zoom-in"]')!.click()
    const zoomedView = map.getAttribute('viewBox')!.split(' ').map(Number)
    expect(zoomedView[2]).toBeLessThan(860)
    expect(document.querySelector('#map-zoom-label')?.textContent).not.toBe('100%')

    Object.defineProperty(map, 'getBoundingClientRect', {
      value: () => ({ left: 0, top: 0, width: 860, height: 620, right: 860, bottom: 620, x: 0, y: 0, toJSON: () => ({}) }),
    })
    map.dispatchEvent(new MouseEvent('pointerdown', { bubbles: true, button: 0, clientX: 430, clientY: 310 }))
    map.dispatchEvent(new MouseEvent('pointermove', { bubbles: true, button: 0, clientX: 330, clientY: 310 }))
    map.dispatchEvent(new MouseEvent('pointerup', { bubbles: true, button: 0, clientX: 330, clientY: 310 }))
    const draggedView = map.getAttribute('viewBox')!.split(' ').map(Number)
    expect(draggedView[0]).toBeGreaterThan(zoomedView[0])

    const requestsButton = document.querySelector<HTMLButtonElement>('[data-location-metric="requests"]')!
    requestsButton.click()
    expect(requestsButton.getAttribute('aria-pressed')).toBe('true')
    expect(map.getAttribute('viewBox')!.split(' ').map(Number)[2]).toBeCloseTo(zoomedView[2])

    map.dispatchEvent(new KeyboardEvent('keydown', { key: '+', bubbles: true }))
    expect(map.getAttribute('viewBox')!.split(' ').map(Number)[2]).toBeLessThan(zoomedView[2])

    document.querySelector<HTMLButtonElement>('[data-map-action="reset"]')!.click()
    expect(map.getAttribute('viewBox')).toBe('0 0 860 620')
    expect(document.querySelector('#map-zoom-label')?.textContent).toBe('100%')
    expect(errors).toEqual([])
    dom.window.close()
  })

  it('handles an empty migrated database without NaN or uncaught errors', async () => {
    const empty = structuredClone(fixture)
    empty.overview = {
      totalRecommendations: 0,
      totalAnonymousIds: 0,
      totalRestaurants: 0,
      totalFeedbacks: 0,
      periodRecommendations: 0,
      periodActiveIds: 0,
      periodNewIds: 0,
      periodReturningIds: 0,
    }
    empty.funnel = {
      recommendationSessions: 0,
      acceptedSessions: 0,
      navigatedSessions: 0,
      feedbackSessions: 0,
      feedbackCount: 0,
      dislikedSessions: 0,
      acceptanceRate: null,
      navigationRate: null,
      feedbackRate: null,
    }
    empty.daily = empty.daily.map((row: Record<string, unknown>) => ({
      ...row,
      recommendations: 0,
      activeIds: 0,
      newIds: 0,
      accepts: 0,
      navigations: 0,
      rerolls: 0,
      feedbacks: 0,
      dislikes: 0,
    }))
    empty.behaviors = []
    empty.feedback = []
    empty.risks = []
    empty.categories = []
    empty.algorithms = []
    empty.locations = { ...empty.locations, totalAnonymousIds: 0, totalRequests: 0, points: [] }

    const { dom, errors } = await openDashboard(empty)
    const { document } = dom.window
    document.querySelector<HTMLButtonElement>('[data-view="locations"]')!.click()
    expect(errors).toEqual([])
    expect(document.querySelectorAll('.location-bubble')).toHaveLength(0)
    expect(document.querySelector('#location-detail')?.textContent).toContain('没有可绘制')
    expect(document.querySelector('main')?.textContent).not.toMatch(/\b(?:NaN|Infinity|undefined)\b/)
    expect(document.querySelector('#schema-view')).not.toBeNull()
    dom.window.close()
  })
})
