<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import {
  MAP_HEIGHT, MAP_WIDTH, MAX_ZOOM, boundaries, bubbleRadius, buildCities, constrainView,
  filterCities, geoCoverage, groupProvinces, locationCsv, project, provinceView, rankRows,
  share, sumRows, zoomView,
  type GeoLevel, type GeoMetric, type GeoRow, type LocationData, type MapView,
} from '../geo'

const props = defineProps<{ locations: LocationData; rangeLabel: string }>()
const metric = ref<GeoMetric>('anonymousIds')
const level = ref<GeoLevel>('city')
const display = ref<'bubbles' | 'intensity'>('bubbles')
const province = ref('')
const query = ref('')
const selectedCode = ref('')
const svg = ref<SVGSVGElement | null>(null)
const view = ref<MapView>({ scale: 1, x: 0, y: 0 })
const dragging = ref(false)
const downloadNotice = ref('')
let pointer: { id: number; startX: number; startY: number; x: number; y: number; moved: boolean } | null = null
let ignoreClick = false

const number = (value: number) => new Intl.NumberFormat('zh-CN').format(value)
const percent = (value: number, denominator: number) => {
  const ratio = share(value, denominator)
  return ratio == null ? '—' : new Intl.NumberFormat('zh-CN', { style: 'percent', maximumFractionDigits: 1 }).format(ratio)
}
const metricLabel = computed(() => metric.value === 'anonymousIds' ? '匿名标识' : '推荐请求')
const metricUnit = computed(() => metric.value === 'anonymousIds' ? '个' : '次')
const cities = computed(() => buildCities(props.locations))
const provinces = computed(() => [...new Set(cities.value.map((city) => city.province))].sort((a, b) => a.localeCompare(b, 'zh-CN')))
const filteredCities = computed(() => filterCities(cities.value, province.value, query.value))
const provinceRows = computed(() => groupProvinces(filteredCities.value, props.locations.lowSampleThreshold || 3))
const rows = computed(() => rankRows(level.value === 'city' ? filteredCities.value : provinceRows.value, metric.value))
const coverage = computed(() => geoCoverage(props.locations, cities.value))
const total = computed(() => metric.value === 'anonymousIds' ? coverage.value.totalIds : coverage.value.totalRequests)
const filteredTotal = computed(() => sumRows(filteredCities.value, metric.value))
const selected = computed(() => rows.value.find((row) => row.code === selectedCode.value))
const maxValue = computed(() => Math.max(0, ...rows.value.map((row) => row[metric.value])))
const provinceMax = computed(() => Math.max(0, ...provinceRows.value.map((row) => row[metric.value])))
const provinceLookup = computed(() => new Map(provinceRows.value.map((row) => [row.province, row])))
const mapCities = computed(() => rankRows(filteredCities.value.filter((city) => city.hasCoordinates && city[metric.value] > 0), metric.value))
const paintCities = computed(() => [...mapCities.value].reverse())
const lowSamples = computed(() => filteredCities.value.filter((city) => city.lowSample).length)
const transform = computed(() => `translate(${view.value.x} ${view.value.y}) scale(${view.value.scale})`)
const coloredRegions = computed(() => level.value === 'province' || display.value === 'intensity')

watch([province, query, level, () => props.locations], () => {
  if (!rows.value.some((row) => row.code === selectedCode.value)) selectedCode.value = ''
  if (province.value && !provinces.value.includes(province.value)) province.value = ''
})
watch(province, (value) => { view.value = provinceView(value) })

function regionFill(name: string) {
  const value = provinceLookup.value.get(name)?.[metric.value] || 0
  if (!coloredRegions.value || !value) return undefined
  // A square-root scale keeps smaller regions visible without suggesting a linear legend.
  return `color-mix(in srgb, var(--geo-accent) ${Math.round(15 + Math.sqrt(value / provinceMax.value) * 72)}%, var(--geo-region))`
}

function regionLabel(name: string) {
  const row = provinceLookup.value.get(name)
  return `${name}，${row ? `${number(row[metric.value])} ${metricUnit.value}${metricLabel.value}，${level.value === 'province' ? '选择省级详情' : '筛选该省城市'}` : '当前筛选无数据'}`
}

function selectRegion(name: string) {
  if (ignoreClick || !provinceLookup.value.has(name)) return
  if (level.value === 'province') selectedCode.value = name
  else {
    province.value = province.value === name ? '' : name
    selectedCode.value = ''
  }
}

function selectCity(row: GeoRow) {
  if (!ignoreClick) selectedCode.value = row.code
}

function clearFilters() {
  province.value = ''
  query.value = ''
  selectedCode.value = ''
  resetMap()
}

function resetMap() {
  view.value = { scale: 1, x: 0, y: 0 }
}

function zoom(factor: number) {
  view.value = zoomView(view.value, factor)
}

function localPoint(clientX: number, clientY: number): [number, number] {
  const bounds = svg.value?.getBoundingClientRect()
  if (!bounds?.width || !bounds.height) return [MAP_WIDTH / 2, MAP_HEIGHT / 2]
  // The canvas keeps the viewBox aspect ratio, including on narrow screens.
  return [(clientX - bounds.left) * MAP_WIDTH / bounds.width, (clientY - bounds.top) * MAP_HEIGHT / bounds.height]
}

function pointerDown(event: PointerEvent) {
  if (event.button !== 0) return
  const [x, y] = localPoint(event.clientX, event.clientY)
  pointer = { id: event.pointerId, startX: x, startY: y, x: view.value.x, y: view.value.y, moved: false }
  ignoreClick = false
  // Capture on the pressed element so an ordinary city click keeps its original target.
  ;(event.target as Element).setPointerCapture?.(event.pointerId)
}

function pointerMove(event: PointerEvent) {
  if (!pointer || pointer.id !== event.pointerId) return
  const [x, y] = localPoint(event.clientX, event.clientY)
  if (Math.hypot(x - pointer.startX, y - pointer.startY) > 5) pointer.moved = true
  if (pointer.moved) {
    dragging.value = true
    view.value = constrainView({ scale: view.value.scale, x: pointer.x + x - pointer.startX, y: pointer.y + y - pointer.startY })
  }
}

function pointerEnd() {
  ignoreClick = Boolean(pointer?.moved)
  pointer = null
  dragging.value = false
  // The click from this pointer sequence is dispatched before the timer.
  if (ignoreClick) window.setTimeout(() => { ignoreClick = false }, 0)
}

function wheel(event: WheelEvent) {
  // Keep the normal page scroll unless the user explicitly zooms the map.
  if (!event.ctrlKey && !event.metaKey) return
  event.preventDefault()
  view.value = zoomView(view.value, event.deltaY < 0 ? 1.18 : 1 / 1.18, localPoint(event.clientX, event.clientY))
}

function mapKey(event: KeyboardEvent) {
  if (event.target !== event.currentTarget) return
  const movement: Record<string, [number, number]> = {
    ArrowLeft: [55, 0], ArrowRight: [-55, 0], ArrowUp: [0, 55], ArrowDown: [0, -55],
  }
  if (movement[event.key]) {
    event.preventDefault()
    const [x, y] = movement[event.key]
    view.value = constrainView({ scale: view.value.scale, x: view.value.x + x, y: view.value.y + y })
  } else if (['+', '=', '-', '0', 'Home'].includes(event.key)) {
    event.preventDefault()
    if (event.key === '0' || event.key === 'Home') resetMap()
    else zoom(event.key === '-' ? 1 / 1.3 : 1.3)
  }
}

function exportCsv() {
  const content = locationCsv(rows.value, {
    level: level.value, metric: metric.value, total: total.value, filteredTotal: filteredTotal.value, rangeLabel: props.rangeLabel,
  })
  const url = URL.createObjectURL(new Blob([content], { type: 'text/csv;charset=utf-8' }))
  const link = document.createElement('a')
  link.href = url
  link.download = `ELMA-地域分析-${level.value}-${props.rangeLabel.replace(/[^\d-]/g, '_')}.csv`
  document.body.append(link)
  link.click()
  link.remove()
  window.setTimeout(() => URL.revokeObjectURL(url), 1000)
  downloadNotice.value = `已导出当前筛选的 ${rows.value.length} 个${level.value === 'city' ? '城市' : '省级地区'}。`
}
</script>

<template>
  <section class="geo-explorer" aria-label="地域分析">
    <div class="geo-heading">
      <div><p class="eyebrow">GEOGRAPHIC INSIGHTS</p><h2>每一次推荐，发生在哪里</h2><p class="subtle">{{ rangeLabel }} · 按主要活动城市归属匿名标识，按请求发生地统计推荐请求</p></div>
      <button class="geo-button" type="button" :disabled="!rows.length" @click="exportCsv">↓ 导出当前筛选</button>
    </div>

    <div class="geo-summary">
      <div><span>覆盖城市</span><strong>{{ number(cities.length) }}<small> / {{ number(provinces.length) }} 个省级地区</small></strong></div>
      <div><span>已归属匿名标识</span><strong>{{ number(coverage.mappedIds) }}<small>{{ percent(coverage.mappedIds, coverage.totalIds) }} 的区间总量</small></strong></div>
      <div><span>已归属推荐请求</span><strong>{{ number(coverage.mappedRequests) }}<small>{{ percent(coverage.mappedRequests, coverage.totalRequests) }} 的区间总量</small></strong></div>
      <div><span>未归属</span><strong>{{ number(coverage.unmappedIds) }}<small>个标识 · {{ number(coverage.unmappedRequests) }} 次请求</small></strong></div>
    </div>

    <div class="geo-toolbar">
      <fieldset class="toggle-group"><legend>统计指标</legend><button type="button" :aria-pressed="metric === 'anonymousIds'" @click="metric = 'anonymousIds'">匿名标识</button><button type="button" :aria-pressed="metric === 'requests'" @click="metric = 'requests'">推荐请求</button></fieldset>
      <fieldset class="toggle-group"><legend>汇总层级</legend><button type="button" :aria-pressed="level === 'city'" @click="level = 'city'">城市</button><button type="button" :aria-pressed="level === 'province'" @click="level = 'province'">省级</button></fieldset>
      <label class="geo-field"><span>省级地区</span><select v-model="province" aria-label="筛选省级地区"><option value="">全部省级地区</option><option v-for="name in provinces" :key="name" :value="name">{{ name }}</option></select></label>
      <label class="geo-field geo-search"><span>搜索城市 / 省份</span><input v-model="query" type="search" placeholder="例如：长沙、广东" aria-label="搜索城市或省份" /></label>
      <button v-if="province || query" class="text-button clear-filters" type="button" @click="clearFilters">清除筛选</button>
    </div>

    <p class="filter-context" aria-live="polite">当前筛选：{{ filteredCities.length }} 个城市，{{ number(filteredTotal) }} {{ metricUnit }}{{ metricLabel }}。全区间分母为 {{ number(total) }}，包含未归属数据；筛选内占比以 {{ number(filteredTotal) }} 为分母。</p>
    <div v-if="coverage.inconsistent" class="geo-warning" role="status">城市合计高于区间总量，快照口径可能不一致。请刷新数据后再比较占比。</div>
    <div v-if="coverage.missingCoordinates" class="geo-warning">{{ coverage.missingCoordinates }} 个城市缺少有效行政区中心，保留在排名中，地图暂不标记。</div>

    <div class="geo-workspace">
      <div class="geo-map-panel">
        <div class="map-topbar"><div><strong>{{ province || '全国' }}分布</strong><span>{{ level === 'province' ? '省级汇总强度' : display === 'bubbles' ? '城市气泡' : '城市气泡 + 省级强度' }}</span></div><fieldset v-if="level === 'city'" class="toggle-group compact"><legend>地图样式</legend><button type="button" :aria-pressed="display === 'bubbles'" @click="display = 'bubbles'">气泡</button><button type="button" :aria-pressed="display === 'intensity'" @click="display = 'intensity'">强度</button></fieldset></div>
        <div class="map-canvas" :class="{ dragging }">
          <svg ref="svg" :viewBox="`0 0 ${MAP_WIDTH} ${MAP_HEIGHT}`" :style="{ touchAction: view.scale > 1 ? 'none' : 'pan-y' }" class="geo-map" tabindex="0" role="group" aria-label="中国地域分布交互地图" aria-describedby="geo-map-help" @pointerdown="pointerDown" @pointermove="pointerMove" @pointerup="pointerEnd" @pointercancel="pointerEnd" @wheel="wheel" @keydown="mapKey">
            <title>ELMA 区间地域分布</title><desc>离线省级边界。城市气泡位于行政区中心，省级强度表示汇总数量。可通过旁边排名完整访问数据。</desc>
            <g :transform="transform">
              <path v-for="boundary in boundaries" :key="boundary.name" :d="boundary.path" class="geo-region" :class="{ 'region-selected': province === boundary.name || (level === 'province' && selectedCode === boundary.name), 'region-muted': province && province !== boundary.name, 'region-active': provinceLookup.has(boundary.name) }" :style="{ fill: regionFill(boundary.name) }" vector-effect="non-scaling-stroke" :tabindex="level === 'province' && provinceLookup.has(boundary.name) ? 0 : -1" :role="provinceLookup.has(boundary.name) ? 'button' : undefined" :aria-label="regionLabel(boundary.name)" :aria-pressed="level === 'province' ? selectedCode === boundary.name : province === boundary.name" @click.stop="selectRegion(boundary.name)" @keydown.enter.prevent.stop="selectRegion(boundary.name)" @keydown.space.prevent.stop="selectRegion(boundary.name)"><title>{{ regionLabel(boundary.name) }}</title></path>
              <template v-if="level === 'city'">
                <g v-for="city in paintCities" :key="city.code" :transform="`translate(${project([city.longitude, city.latitude]).join(' ')})`" class="city-marker" :class="{ 'city-selected': selectedCode === city.code }" tabindex="0" role="button" :aria-pressed="selectedCode === city.code" :aria-label="`${city.province}${city.label}，${number(city[metric])} ${metricUnit}${metricLabel}${city.lowSample ? '，低样本' : ''}`" @click.stop="selectCity(city)" @keydown.enter.prevent.stop="selectCity(city)" @keydown.space.prevent.stop="selectCity(city)">
                  <circle :r="(bubbleRadius(city[metric], maxValue) + 6) / view.scale" class="marker-hit" />
                  <circle :r="bubbleRadius(city[metric], maxValue) / view.scale" class="marker-ring" :class="{ 'low-sample': city.lowSample }" vector-effect="non-scaling-stroke" />
                  <circle :r="2 / view.scale" class="marker-center" />
                  <text v-if="selectedCode === city.code || (province && mapCities.length <= 12) || (!province && mapCities.length <= 4)" :y="-(bubbleRadius(city[metric], maxValue) + 7) / view.scale" :font-size="12 / view.scale" text-anchor="middle" class="marker-label">{{ city.label }}</text>
                  <title>{{ city.label }} · {{ number(city[metric]) }} {{ metricUnit }}{{ metricLabel }}{{ city.lowSample ? ' · 低样本' : '' }}</title>
                </g>
              </template>
            </g>
          </svg>
          <div v-if="!filteredCities.length" class="map-empty" role="status"><strong>{{ cities.length ? '没有符合筛选的地区' : '这个区间暂无已归属地域数据' }}</strong><span>{{ cities.length ? '试试其他城市名称，或清除筛选条件。' : '刷新其他日期，或查看未归属数据量。' }}</span><button v-if="cities.length" class="geo-button" type="button" @click="clearFilters">清除筛选</button></div>
          <div class="map-controls" aria-label="地图缩放"><button type="button" aria-label="放大地图" :disabled="view.scale >= MAX_ZOOM" @click="zoom(1.4)">＋</button><output aria-label="当前缩放">{{ Math.round(view.scale * 100) }}%</output><button type="button" aria-label="缩小地图" :disabled="view.scale <= 1" @click="zoom(1 / 1.4)">−</button><button type="button" class="reset-map" @click="resetMap">复位</button></div>
        </div>
        <div class="map-legend"><span v-if="level === 'city'"><i class="legend-bubble"></i>气泡大小随数量增加</span><span v-if="level === 'city'"><i class="legend-low"></i>低样本</span><span v-if="coloredRegions"><i class="legend-gradient"></i>省级数量 0 → {{ number(provinceMax) }}（平方根色阶）</span><span class="legend-center">行政区中心示意</span></div>
        <p id="geo-map-help" class="map-help">放大后可拖动；Ctrl / ⌘ + 滚轮缩放。聚焦地图后用方向键移动、+ / − 缩放、0 复位。地区可按 Tab 选择并按 Enter 查看。</p>
      </div>

      <aside class="geo-inspector" aria-label="地域排名和详情">
        <div class="ranking-heading"><div><h3>{{ level === 'city' ? '城市' : '省级' }}排名</h3><span>{{ rows.length }} 个地区 · 按{{ metricLabel }}排序</span></div><span class="rank-unit">筛选内占比</span></div>
        <ol v-if="rows.length" class="geo-ranking">
          <li v-for="(row, index) in rows" :key="row.code"><button type="button" class="rank-row" :class="{ selected: selectedCode === row.code }" :aria-pressed="selectedCode === row.code" @click="selectedCode = row.code"><span class="rank-index">{{ index + 1 }}</span><span class="rank-main"><span class="rank-title">{{ row.label }}<span v-if="row.lowSample" class="sample-dot" aria-label="低样本"></span></span><span class="rank-province">{{ level === 'city' ? row.province : `${row.cityCount} 个城市` }}</span><span class="rank-track"><span :style="{ width: `${maxValue > 0 ? row[metric] / maxValue * 100 : 0}%` }"></span></span></span><span class="rank-values"><strong>{{ number(row[metric]) }}</strong><small>{{ percent(row[metric], filteredTotal) }}</small></span></button></li>
        </ol>
        <p v-else class="rank-empty">暂无匹配地区</p>
        <section v-if="selected" class="geo-detail" aria-label="选中地区详情" aria-live="polite">
          <div class="detail-top"><div><span>{{ level === 'city' ? selected.province : '省级汇总' }}</span><h3>{{ selected.label }}</h3></div><button type="button" class="detail-close" aria-label="关闭地区详情" @click="selectedCode = ''">×</button></div>
          <dl><div><dt>主要活动匿名标识</dt><dd>{{ number(selected.anonymousIds) }}</dd></div><div><dt>推荐请求</dt><dd>{{ number(selected.requests) }}</dd></div><div><dt>占全区间{{ metricLabel }}</dt><dd>{{ percent(selected[metric], total) }}</dd></div><div><dt>占当前筛选{{ metricLabel }}</dt><dd>{{ percent(selected[metric], filteredTotal) }}</dd></div><div v-if="level === 'province'"><dt>覆盖城市</dt><dd>{{ selected.cityCount }}</dd></div></dl>
          <p v-if="selected.lowSample" class="sample-note">匿名标识少于 {{ locations.lowSampleThreshold || 3 }} 或被源数据标记为低样本，仅供观察，不宜推断稳定趋势。</p>
          <button v-if="level === 'province'" type="button" class="geo-button drill-button" @click="province = selected.province; level = 'city'; selectedCode = ''">查看该省城市 →</button>
          <p v-else class="detail-note">城市中心仅用于定位行政区，不代表任何标识的精确位置。匿名标识不等同于真实人数。</p>
        </section>
        <p v-else class="selection-hint">点击气泡、行政区或排名，查看数量、全区间份额与筛选内份额。</p>
      </aside>
    </div>

    <div class="geo-notes"><p><strong>口径与覆盖</strong> 匿名标识在区间内仅归属一个主要活动城市；推荐请求按发生地归属，两项指标的地域分布可能不同。当前筛选有 {{ lowSamples }} 个低样本城市。未归属数据不在地图和排名中，但计入全区间占比分母。</p><p v-if="locations.note">{{ locations.note }}</p><p>省级边界来源：chinese-global-compliant-geodata（MIT，上游标注为天地图 2024-05），沿用本项目离线处理版本。<a href="./THIRD_PARTY_NOTICES.md" target="_blank" rel="noreferrer">第三方许可与来源</a>。底图仅作统计示意。</p></div>
    <p class="sr-only" role="status">{{ downloadNotice }}</p>
  </section>
</template>

<style scoped>
.geo-explorer { --geo-accent: var(--accent, #2268d8); --geo-text: var(--text, #172c47); --geo-muted: var(--muted, #687b91); --geo-panel: var(--panel, #fff); --geo-soft: var(--surface-soft, #f5f8fc); --geo-border: var(--border, #dde5ef); --geo-region: var(--map-region, #e8eef6); color: var(--geo-text); }
.geo-explorer * { box-sizing: border-box; }
.geo-heading { display: flex; align-items: center; justify-content: space-between; gap: 20px; margin-bottom: 24px; }
.eyebrow { margin: 0 0 9px; font-size: 10px; font-weight: 750; letter-spacing: .16em; color: var(--geo-accent); }
h2 { margin: 0; font-size: clamp(21px, 2.5vw, 29px); letter-spacing: -.035em; }
h3 { margin: 0; font-size: 16px; }
.subtle { margin: 10px 0 0; color: var(--geo-muted); font-size: 12px; line-height: 1.7; }
button, input, select { font: inherit; }
button { cursor: pointer; }
button:disabled { cursor: default; opacity: .4; }
button:focus-visible, input:focus-visible, select:focus-visible, svg:focus-visible { outline: 3px solid color-mix(in srgb, var(--geo-accent) 55%, transparent); outline-offset: 3px; }
.geo-button { display: inline-flex; justify-content: center; align-items: center; gap: 6px; border: 1px solid var(--geo-border); border-radius: 10px; background: var(--geo-panel); color: var(--geo-text); padding: 10px 14px; font-size: 12px; font-weight: 650; white-space: nowrap; }
.geo-button:hover:not(:disabled) { border-color: var(--geo-accent); color: var(--geo-accent); }
.geo-summary { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 1px; margin-bottom: 24px; background: var(--geo-border); border: 1px solid var(--geo-border); border-radius: 14px; overflow: hidden; }
.geo-summary > div { padding: 20px; background: var(--geo-panel); }
.geo-summary span { display: block; font-size: 12px; color: var(--geo-muted); margin-bottom: 10px; }
.geo-summary strong { display: block; font-size: 26px; font-variant-numeric: tabular-nums; font-weight: 700; letter-spacing: -.025em; }
.geo-summary small { display: block; color: var(--geo-muted); font-size: 10px; font-weight: 450; margin-top: 7px; letter-spacing: 0; }
.geo-toolbar { display: flex; align-items: flex-end; flex-wrap: wrap; gap: 16px; }
.toggle-group { border: 0; margin: 0; padding: 0; display: flex; gap: 3px; min-width: 0; }
.toggle-group legend, .geo-field > span { margin-bottom: 7px; padding: 0; display: block; font-size: 11px; color: var(--geo-muted); }
.toggle-group button { border: 1px solid var(--geo-border); border-radius: 8px; padding: 10px 13px; background: var(--geo-panel); color: var(--geo-muted); font-size: 12px; white-space: nowrap; }
.toggle-group button[aria-pressed="true"] { background: color-mix(in srgb, var(--geo-accent) 10%, var(--geo-panel)); color: var(--geo-accent); border-color: color-mix(in srgb, var(--geo-accent) 35%, var(--geo-border)); font-weight: 650; }
.geo-field select, .geo-field input { background: var(--geo-panel); color: var(--geo-text); width: 100%; border: 1px solid var(--geo-border); border-radius: 8px; padding: 10px 12px; font-size: 12px; height: 38px; }
.geo-field select { min-width: 142px; }
.geo-search { flex: 1; min-width: 180px; }
.text-button { border: 0; background: none; color: var(--geo-accent); font-size: 12px; padding: 11px 4px; }
.filter-context { font-size: 11px; line-height: 1.8; color: var(--geo-muted); margin: 16px 0; }
.geo-warning { font-size: 12px; line-height: 1.6; padding: 12px 16px; border: 1px solid #d8a844; background: color-mix(in srgb, #f3bf48 12%, var(--geo-panel)); border-radius: 9px; margin: 10px 0; }
.geo-workspace { display: grid; grid-template-columns: minmax(0, 1fr) 310px; border: 1px solid var(--geo-border); border-radius: 16px; overflow: hidden; background: var(--geo-panel); }
.geo-map-panel { min-width: 0; display: flex; flex-direction: column; }
.map-topbar { display: flex; justify-content: space-between; align-items: center; gap: 16px; padding: 21px 22px 12px; }
.map-topbar strong { display: block; font-size: 14px; }
.map-topbar span { display: block; margin-top: 6px; font-size: 11px; color: var(--geo-muted); }
.compact legend { position: absolute; width: 1px; height: 1px; overflow: hidden; clip-path: inset(50%); }
.compact button { padding: 7px 10px; font-size: 11px; }
.map-canvas { position: relative; background: radial-gradient(ellipse at 45% 45%, var(--geo-panel), var(--geo-soft)); overflow: hidden; }
.geo-map { display: block; width: 100%; aspect-ratio: 900 / 640; height: auto; user-select: none; touch-action: pan-y; cursor: grab; }
.dragging .geo-map { cursor: grabbing; }
.geo-region { fill: var(--geo-region); stroke: var(--geo-panel); stroke-width: 1.2; transition: fill .18s ease; }
.geo-region.region-active { cursor: pointer; }
.geo-region.region-active:hover, .geo-region:focus { stroke: var(--geo-accent); stroke-width: 1.8; outline: none; }
.geo-region.region-selected { stroke: var(--geo-accent); stroke-width: 2; }
.geo-region.region-muted { opacity: .38; }
.city-marker { cursor: pointer; outline: none; }
.marker-hit { fill: transparent; }
.marker-ring { fill: color-mix(in srgb, var(--geo-accent) 28%, transparent); stroke: var(--geo-accent); stroke-width: 1.5; }
.marker-center { fill: var(--geo-accent); }
.marker-ring.low-sample { stroke-dasharray: 3 3; fill: color-mix(in srgb, var(--geo-accent) 14%, transparent); }
.city-selected .marker-ring, .city-marker:focus .marker-ring, .city-marker:hover .marker-ring { fill: color-mix(in srgb, var(--geo-accent) 55%, transparent); stroke-width: 3; stroke: var(--geo-text); }
.marker-label { fill: var(--geo-text); paint-order: stroke; stroke: var(--geo-panel); stroke-width: 3px; stroke-linejoin: round; font-weight: 650; pointer-events: none; }
.map-controls { position: absolute; left: 17px; bottom: 15px; display: flex; align-items: center; border: 1px solid var(--geo-border); border-radius: 9px; background: var(--geo-panel); padding: 3px; box-shadow: 0 3px 12px #172c4708; }
.map-controls button { border: 0; background: none; color: var(--geo-text); width: 33px; height: 32px; font-size: 17px; border-radius: 6px; }
.map-controls button:hover { background: var(--geo-soft); }
.map-controls output { width: 43px; text-align: center; font-size: 10px; color: var(--geo-muted); font-variant-numeric: tabular-nums; }
.map-controls button.reset-map { width: 45px; border-left: 1px solid var(--geo-border); font-size: 11px; border-radius: 0; }
.map-empty { position: absolute; inset: 0; display: flex; flex-direction: column; align-items: center; justify-content: center; gap: 13px; text-align: center; background: color-mix(in srgb, var(--geo-panel) 65%, transparent); padding: 24px; }
.map-empty strong { font-size: 15px; }
.map-empty span { font-size: 12px; color: var(--geo-muted); }
.map-legend { display: flex; flex-wrap: wrap; align-items: center; gap: 13px; padding: 15px 20px 0; font-size: 10px; color: var(--geo-muted); }
.map-legend > span { display: flex; align-items: center; gap: 6px; }
.legend-bubble, .legend-low { display: inline-block; width: 10px; height: 10px; background: color-mix(in srgb, var(--geo-accent) 20%, var(--geo-panel)); border: 1px solid var(--geo-accent); border-radius: 50%; }
.legend-low { border-style: dashed; background: transparent; }
.legend-gradient { width: 43px; height: 7px; display: inline-block; background: linear-gradient(to right, var(--geo-region), var(--geo-accent)); border-radius: 4px; }
.legend-center { margin-left: auto; }
.map-help { color: var(--geo-muted); font-size: 10px; line-height: 1.8; margin: 12px 20px 18px; }
.geo-inspector { border-left: 1px solid var(--geo-border); min-width: 0; display: flex; flex-direction: column; }
.ranking-heading { display: flex; align-items: center; justify-content: space-between; padding: 23px 19px 16px; gap: 6px; }
.ranking-heading span { display: block; color: var(--geo-muted); font-size: 10px; margin-top: 7px; }
.rank-unit { white-space: nowrap; }
.geo-ranking { list-style: none; padding: 0 9px; margin: 0; max-height: 325px; min-height: 150px; overflow-y: auto; scrollbar-width: thin; }
.rank-row { display: flex; align-items: center; gap: 10px; background: none; border: 1px solid transparent; text-align: left; padding: 10px 9px; width: 100%; border-radius: 9px; color: var(--geo-text); }
.rank-row:hover { background: var(--geo-soft); }
.rank-row.selected { border-color: color-mix(in srgb, var(--geo-accent) 20%, var(--geo-border)); background: color-mix(in srgb, var(--geo-accent) 6%, var(--geo-panel)); }
.rank-index { font-size: 11px; font-variant-numeric: tabular-nums; color: var(--geo-muted); width: 19px; text-align: center; }
.rank-main { flex: 1; min-width: 0; }
.rank-title { display: flex; align-items: center; gap: 6px; font-size: 12px; font-weight: 650; overflow-wrap: anywhere; }
.sample-dot { display: inline-block; width: 6px; height: 6px; border: 1px solid #bd8933; border-radius: 50%; }
.rank-province { display: block; font-size: 10px; color: var(--geo-muted); margin-top: 3px; }
.rank-track { display: block; background: var(--geo-soft); height: 3px; margin-top: 8px; border-radius: 3px; overflow: hidden; }
.rank-track > span { display: block; height: 100%; background: var(--geo-accent); opacity: .65; border-radius: inherit; }
.rank-values { min-width: 49px; text-align: right; font-variant-numeric: tabular-nums; }
.rank-values strong { display: block; font-size: 13px; }
.rank-values small { display: block; margin-top: 6px; font-size: 10px; color: var(--geo-muted); }
.rank-empty, .selection-hint { color: var(--geo-muted); line-height: 1.8; font-size: 12px; padding: 20px; margin: 0; }
.selection-hint { border-top: 1px solid var(--geo-border); margin-top: 17px; }
.geo-detail { background: var(--geo-soft); padding: 19px; border-top: 1px solid var(--geo-border); margin-top: 14px; flex: 1; }
.detail-top { display: flex; justify-content: space-between; align-items: center; }
.detail-top span { color: var(--geo-muted); font-size: 10px; display: block; margin-bottom: 6px; }
.detail-top h3 { font-size: 20px; }
.detail-close { background: transparent; border: 0; font-size: 23px; color: var(--geo-muted); align-self: flex-start; width: 30px; height: 30px; }
.geo-detail dl { margin: 18px 0 0; }
.geo-detail dl > div { display: flex; justify-content: space-between; gap: 8px; margin-top: 12px; font-size: 11px; }
.geo-detail dt { color: var(--geo-muted); }
.geo-detail dd { margin: 0; font-weight: 700; font-variant-numeric: tabular-nums; }
.sample-note, .detail-note { font-size: 10px; line-height: 1.8; color: var(--geo-muted); margin-top: 17px; }
.sample-note { padding-left: 9px; border-left: 2px solid #d3a250; }
.drill-button { margin-top: 20px; width: 100%; background: transparent; }
.geo-notes { color: var(--geo-muted); font-size: 10px; line-height: 1.9; padding-top: 10px; }
.geo-notes strong { color: var(--geo-text); font-weight: 650; margin-right: 7px; }
.geo-notes a { color: var(--geo-accent); text-underline-offset: 3px; }
.sr-only { position: absolute; width: 1px; height: 1px; margin: -1px; overflow: hidden; clip-path: inset(50%); }
@media (min-width: 1500px) { .geo-workspace { grid-template-columns: minmax(0, 1fr) 350px; } .geo-ranking { max-height: 390px; } }
@media (max-width: 1100px) { .geo-workspace { grid-template-columns: minmax(0, 1fr); } .geo-inspector { border-left: 0; border-top: 1px solid var(--geo-border); display: grid; grid-template-columns: 1fr 1fr; } .ranking-heading { grid-column: 1; } .geo-ranking { grid-column: 1; grid-row: 2; max-height: 290px; margin-bottom: 18px; } .geo-detail, .selection-hint { grid-column: 2; grid-row: 1 / 3; margin: 0; border-top: 0; border-left: 1px solid var(--geo-border); } .rank-empty { grid-column: 1; } }
@media (max-width: 650px) { .geo-heading { align-items: flex-start; flex-direction: column; gap: 15px; } .geo-summary { grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 1px; margin-bottom: 20px; } .geo-summary > div { padding: 16px; } .geo-summary strong { font-size: 23px; } .geo-summary small { font-size: 10px; line-height: 1.6; } .geo-toolbar { gap: 14px 10px; } .geo-field { flex: 1; min-width: 135px; } .geo-search { flex-basis: 48%; } .toggle-group { flex: 1; } .toggle-group button { flex: 1; padding: 10px 8px; } .compact { flex: none; } .compact button { padding: 7px 9px; } .map-topbar { padding: 17px 14px 10px; } .geo-workspace { border-radius: 12px; } .map-controls { left: 10px; bottom: 8px; } .map-controls button { height: 35px; width: 34px; } .map-legend { padding: 13px 14px 0; gap: 10px; } .legend-center { margin-left: 0; } .map-help { margin: 10px 14px 15px; } .geo-inspector { display: flex; } .geo-ranking { max-height: 305px; margin-bottom: 0; } .geo-detail, .selection-hint { margin-top: 14px; border-left: 0; border-top: 1px solid var(--geo-border); } .rank-row { min-height: 67px; } .geo-notes { font-size: 10px; } .map-empty strong { font-size: 13px; } .map-empty span { font-size: 11px; } }
@media (prefers-reduced-motion: reduce) { .geo-region { transition: none; } }
</style>
