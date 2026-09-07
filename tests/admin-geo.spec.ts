import { mount } from '@vue/test-utils'
import { afterEach, describe, expect, it, vi } from 'vitest'
import GeoExplorer from '../output/admin-console/src/components/GeoExplorer.vue'
import {
  boundaries, buildCities, constrainView, filterCities, geoCoverage, groupProvinces,
  locationCsv, provinceView, rankRows, share, sumRows, zoomView, type LocationData,
} from '../output/admin-console/src/geo'

const locations: LocationData = {
  totalAnonymousIds: 12, totalRequests: 35, unmappedRequests: 3,
  points: [
    { code: '430100', label: '长沙市', province: '湖南省', longitude: 112.94, latitude: 28.23, anonymousIds: 4, requests: 9, lowSample: false },
    { code: '430200', label: '株洲市', province: '湖南省', longitude: 113.13, latitude: 27.83, anonymousIds: 2, requests: 12, lowSample: true },
    { code: '440100', label: '广州市', province: '广东省', longitude: 113.26, latitude: 23.12, anonymousIds: 5, requests: 11, lowSample: false },
  ],
}

afterEach(() => { vi.restoreAllMocks(); vi.useRealTimers(); vi.unstubAllGlobals() })

describe('地域聚合与口径', () => {
  it('跨城市聚合省份，保留每项指标的分母，不将请求排名混作标识排名', () => {
    const cities = buildCities(locations)
    const provinces = rankRows(groupProvinces(cities), 'anonymousIds')
    expect(provinces.map((row) => [row.label, row.anonymousIds, row.requests, row.cityCount])).toEqual([
      ['湖南', 6, 21, 2], ['广东', 5, 11, 1],
    ])
    expect(rankRows(cities, 'anonymousIds')[0].label).toBe('广州市')
    expect(rankRows(cities, 'requests')[0].label).toBe('株洲市')
    const filtered = filterCities(cities, '湖南省')
    expect(sumRows(filtered, 'anonymousIds')).toBe(6)
    expect(share(filtered[0].anonymousIds, 12)).toBeCloseTo(1 / 3)
    expect(share(filtered[0].anonymousIds, 6)).toBeCloseTo(2 / 3)
    expect(provinces[0].hasCoordinates).toBe(false)
  })

  it('联合省份与城市搜索，支持行政区代码和无匹配情况', () => {
    const cities = buildCities(locations)
    expect(filterCities(cities, '湖南', '  株洲  ').map((row) => row.label)).toEqual(['株洲市'])
    expect(filterCities(cities, '', '440100').map((row) => row.label)).toEqual(['广州市'])
    expect(filterCities(cities, '广东', '株洲')).toEqual([])
  })

  it('未归属数据保持在全区间分母，缺少坐标的数据保留在排名', () => {
    const withoutCoordinates = {
      ...locations,
      points: locations.points.map((point) => ({ ...point, longitude: point.code === '440100' ? NaN : point.longitude })),
    }
    const cities = buildCities(withoutCoordinates)
    expect(cities).toHaveLength(3)
    expect(geoCoverage(withoutCoordinates, cities)).toMatchObject({
      totalIds: 12, totalRequests: 35, mappedIds: 11, mappedRequests: 32,
      unmappedIds: 1, unmappedRequests: 3, missingCoordinates: 1, inconsistent: false,
    })
  })

  it('标准化自治区名称，合并重复城市但不生成用户平均位置', () => {
    const data: LocationData = {
      totalAnonymousIds: 5, totalRequests: 7,
      points: [
        { code: '450100', label: '南宁市', province: '广西壮族自治区', longitude: 108.36, latitude: 22.81, anonymousIds: 2, requests: 3, lowSample: false },
        { code: '450100', label: '南宁市', province: '广西', longitude: 108.36, latitude: 22.81, anonymousIds: 3, requests: 4, lowSample: false },
      ],
    }
    expect(buildCities(data)).toMatchObject([{ province: '广西', anonymousIds: 5, requests: 7, lowSample: false }])
    expect(groupProvinces(buildCities(data))[0].hasCoordinates).toBe(false)
  })

  it('零分母不伪装成 0% 或 100%，错误总量会被标记', () => {
    expect(share(0, 0)).toBeNull()
    expect(geoCoverage({ totalAnonymousIds: 0, totalRequests: 0, points: [] })).toMatchObject({ mappedIds: 0, unmappedIds: 0, inconsistent: false })
    expect(geoCoverage({ ...locations, totalAnonymousIds: 2 }).inconsistent).toBe(true)
  })

  it('CSV 只导出筛选行，并携带全区间与筛选分母、防止公式注入', () => {
    const rows = filterCities(buildCities(locations), '湖南', '长沙')
    const csv = locationCsv([{ ...rows[0], label: '=HYPERLINK("bad")' }], {
      level: 'city', metric: 'requests', total: 35, filteredTotal: 9, rangeLabel: '2026-09-01 至 2026-09-07',
    })
    expect(csv.startsWith('\uFEFF')).toBe(true)
    expect(csv).toContain('"\'=HYPERLINK(""bad"")"')
    expect(csv).not.toContain('株洲')
    expect(csv).toContain('"35","9","25.71","100.00"')
    expect(csv).toContain('全区间指标分母（含未归属）')
  })
})

describe('地图操作约束', () => {
  it('离线底图包含 34 个省级行政区且生成有效 SVG path', () => {
    expect(boundaries).toHaveLength(34)
    expect(boundaries.every((boundary) => boundary.path.startsWith('M') && !boundary.path.includes('NaN'))).toBe(true)
  })

  it('缩放以焦点为锚并限制拖拽范围，复位恢复全国视图', () => {
    expect(zoomView({ scale: 1, x: 0, y: 0 }, 2, [450, 320])).toEqual({ scale: 2, x: -450, y: -320 })
    expect(constrainView({ scale: 10, x: 100, y: -100000 })).toEqual({ scale: 5, x: 0, y: -2560 })
    expect(zoomView({ scale: 2, x: -450, y: -320 }, 0.01)).toEqual({ scale: 1, x: 0, y: 0 })
    expect(provinceView('湖南省').scale).toBeGreaterThan(1)
    expect(provinceView('未知')).toEqual({ scale: 1, x: 0, y: 0 })
  })
})

describe('地域分析交互', () => {
  it('筛选更新排名与分母；键盘城市选择显示详情，清除筛选保留全国口径', async () => {
    const wrapper = mount(GeoExplorer, { props: { locations, rangeLabel: '2026-09-01 至 2026-09-07' } })
    await wrapper.get('select[aria-label="筛选省级地区"]').setValue('湖南')
    expect(wrapper.findAll('.rank-row')).toHaveLength(2)
    expect(wrapper.get('.filter-context').text()).toContain('全区间分母为 12')
    expect(wrapper.get('.filter-context').text()).toContain('以 6 为分母')
    await wrapper.get('.city-marker[aria-label^="湖南长沙"]').trigger('keydown', { key: 'Enter' })
    expect(wrapper.get('.geo-detail').text()).toContain('长沙市')
    expect(wrapper.get('.geo-detail').text()).toContain('33.3%')
    expect(wrapper.get('.geo-detail').text()).toContain('66.7%')
    await wrapper.get('input[type="search"]').setValue('不存在')
    expect(wrapper.findAll('.rank-row')).toHaveLength(0)
    expect(wrapper.find('.geo-detail').exists()).toBe(false)
    await wrapper.get('.clear-filters').trigger('click')
    expect(wrapper.findAll('.rank-row')).toHaveLength(3)
    wrapper.unmount()
  })

  it('按请求重新排序，省级详情能下钻到本省城市', async () => {
    const wrapper = mount(GeoExplorer, { props: { locations, rangeLabel: '本区间' } })
    const buttonWithText = (text: string) => wrapper.findAll('button').find((button) => button.text() === text)!
    await buttonWithText('推荐请求').trigger('click')
    expect(wrapper.findAll('.rank-row')[0].text()).toContain('株洲市')
    await buttonWithText('省级').trigger('click')
    expect(wrapper.findAll('.rank-row')).toHaveLength(2)
    expect(wrapper.find('.city-marker').exists()).toBe(false)
    await wrapper.get('.geo-region[aria-label^="湖南"]').trigger('keydown', { key: 'Enter' })
    expect(wrapper.get('.geo-detail').text()).toContain('21')
    await wrapper.get('.drill-button').trigger('click')
    expect((wrapper.get('select').element as HTMLSelectElement).value).toBe('湖南')
    expect(wrapper.findAll('.rank-row')).toHaveLength(2)
    expect(wrapper.find('.city-marker').exists()).toBe(true)
    wrapper.unmount()
  })

  it('键盘支持缩放、移动和复位，并使操作不溢出地图', async () => {
    const wrapper = mount(GeoExplorer, { props: { locations, rangeLabel: '本区间' } })
    const map = wrapper.get('svg.geo-map')
    await map.trigger('keydown', { key: '+' })
    expect(wrapper.get('.map-controls output').text()).toBe('130%')
    const before = wrapper.get('svg > g').attributes('transform')
    await map.trigger('keydown', { key: 'ArrowLeft' })
    expect(wrapper.get('svg > g').attributes('transform')).not.toBe(before)
    await map.trigger('keydown', { key: '0' })
    expect(wrapper.get('svg > g').attributes('transform')).toBe('translate(0 0) scale(1)')
    expect(wrapper.get('[aria-label="缩小地图"]').attributes('disabled')).toBeDefined()
    wrapper.unmount()
  })

  it('缩放后可指针拖动，拖动动作不误选城市；普通滚轮仍可滚动页面', async () => {
    vi.useFakeTimers()
    const wrapper = mount(GeoExplorer, { props: { locations, rangeLabel: '本区间' } })
    const map = wrapper.get('svg.geo-map')
    vi.spyOn(map.element, 'getBoundingClientRect').mockReturnValue({
      x: 0, y: 0, left: 0, top: 0, right: 900, bottom: 640, width: 900, height: 640, toJSON: () => ({}),
    })
    await wrapper.get('[aria-label="放大地图"]').trigger('click')
    const before = wrapper.get('svg > g').attributes('transform')
    await map.trigger('pointerdown', { pointerId: 1, button: 0, clientX: 400, clientY: 300 })
    await map.trigger('pointermove', { pointerId: 1, clientX: 490, clientY: 330 })
    expect(wrapper.get('svg > g').attributes('transform')).not.toBe(before)
    await map.trigger('pointerup', { pointerId: 1 })
    await wrapper.findAll('.city-marker')[0].trigger('click')
    expect(wrapper.find('.geo-detail').exists()).toBe(false)
    vi.runAllTimers()
    await wrapper.findAll('.city-marker')[0].trigger('click')
    expect(wrapper.find('.geo-detail').exists()).toBe(true)
    const unmodifiedWheel = new WheelEvent('wheel', { deltaY: -100, cancelable: true })
    map.element.dispatchEvent(unmodifiedWheel)
    expect(unmodifiedWheel.defaultPrevented).toBe(false)
    const zoomWheel = new WheelEvent('wheel', { deltaY: -100, ctrlKey: true, cancelable: true })
    map.element.dispatchEvent(zoomWheel)
    expect(zoomWheel.defaultPrevented).toBe(true)
    wrapper.unmount()
  })

  it('导出下载只包含当前筛选，并在完成后释放临时 URL', async () => {
    vi.useFakeTimers()
    const createUrl = vi.fn(() => 'blob:geo-csv')
    const revokeUrl = vi.fn()
    vi.stubGlobal('URL', Object.assign(class extends URL {}, { createObjectURL: createUrl, revokeObjectURL: revokeUrl }))
    const click = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => {})
    const wrapper = mount(GeoExplorer, { props: { locations, rangeLabel: '2026-09-01 至 2026-09-07' } })
    await wrapper.get('input[type="search"]').setValue('长沙')
    await wrapper.get('.geo-heading button').trigger('click')
    expect(createUrl).toHaveBeenCalledWith(expect.any(Blob))
    expect(click).toHaveBeenCalledOnce()
    expect(wrapper.get('[role="status"].sr-only').text()).toContain('1 个城市')
    vi.runAllTimers()
    expect(revokeUrl).toHaveBeenCalledWith('blob:geo-csv')
    wrapper.unmount()
    vi.unstubAllGlobals()
  })

  it('刷新后清除不存在的城市选择；空数据仍解释未归属和零分母', async () => {
    const wrapper = mount(GeoExplorer, { props: { locations, rangeLabel: '本区间' } })
    await wrapper.findAll('.rank-row')[0].trigger('click')
    expect(wrapper.find('.geo-detail').exists()).toBe(true)
    await wrapper.setProps({ locations: { totalAnonymousIds: 2, totalRequests: 8, points: [] } })
    expect(wrapper.find('.geo-detail').exists()).toBe(false)
    expect(wrapper.get('.map-empty').text()).toContain('暂无已归属地域数据')
    expect(wrapper.get('.geo-summary').text()).toContain('2个标识 · 8 次请求')
    expect(wrapper.get('.geo-heading button').attributes('disabled')).toBeDefined()
    wrapper.unmount()
  })
})
