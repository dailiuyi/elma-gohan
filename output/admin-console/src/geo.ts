import sourceGeo from './geo-data.json'

// Boundary data: chinese-global-compliant-geodata (MIT), Tianditu, May 2024.
// Copied from the existing offline dashboard. See THIRD_PARTY_NOTICES.md.
export type GeoMetric = 'anonymousIds' | 'requests'
export type GeoLevel = 'city' | 'province'
export interface LocationPoint {
  code: string
  label: string
  province: string
  longitude: number
  latitude: number
  anonymousIds: number
  requests: number
  lowSample: boolean
}
export interface LocationData {
  totalAnonymousIds: number
  totalRequests: number
  points: LocationPoint[]
  unmappedRequests?: number
  note?: string
  lowSampleThreshold?: number
}
export interface GeoRow extends LocationPoint {
  cityCount: number
  hasCoordinates: boolean
}
type Coordinate = number[]
interface Geometry {
  type: string
  coordinates: Coordinate[][] | Coordinate[][][]
}
export const MAP_WIDTH = 900
export const MAP_HEIGHT = 640
export const MAX_ZOOM = 5
export const MIN_ZOOM = 1
export const boundaries = sourceGeo.features.map((feature) => ({
  name: feature.properties.name,
  path: geometryPath(feature.geometry),
  bounds: geometryBounds(feature.geometry),
}))

function count(value: unknown): number {
  const numeric = Number(value)
  return Number.isFinite(numeric) ? Math.max(0, Math.floor(numeric)) : 0
}

export function normalizeProvince(value: string): string {
  return String(value || '').trim()
    .replace(/(壮族|回族|维吾尔)自治区$|特别行政区$|自治区$|省$|市$/u, '')
}

export function project([longitude, latitude]: Coordinate): [number, number] {
  return [32 + (longitude - 72) / 64 * 836, 28 + (54 - latitude) / 51 * 584]
}

function rings(geometry: Geometry): Coordinate[][] {
  return geometry.type === 'MultiPolygon'
    ? (geometry.coordinates as Coordinate[][][]).flat()
    : geometry.coordinates as Coordinate[][]
}

export function geometryPath(geometry: Geometry): string {
  return rings(geometry).map((ring) => ring.map((coordinate, index) => {
    const [x, y] = project(coordinate)
    return `${index ? 'L' : 'M'}${x.toFixed(2)},${y.toFixed(2)}`
  }).join(' ') + ' Z').join(' ')
}

function geometryBounds(geometry: Geometry): [number, number, number, number] {
  const projected = rings(geometry).flat().map(project)
  return [
    Math.min(...projected.map(([x]) => x)), Math.min(...projected.map(([, y]) => y)),
    Math.max(...projected.map(([x]) => x)), Math.max(...projected.map(([, y]) => y)),
  ]
}

export function buildCities(locations: LocationData): GeoRow[] {
  const cities = new Map<string, GeoRow>()
  for (const point of locations.points || []) {
    const province = normalizeProvince(point.province) || '未标注省份'
    const key = String(point.code || `${province}-${point.label}`)
    const existing = cities.get(key)
    if (existing) {
      existing.anonymousIds += count(point.anonymousIds)
      existing.requests += count(point.requests)
      existing.lowSample = existing.lowSample || Boolean(point.lowSample)
      continue
    }
    const longitude = Number(point.longitude)
    const latitude = Number(point.latitude)
    cities.set(key, {
      code: key, label: String(point.label || '未命名城市'), province,
      longitude, latitude,
      anonymousIds: count(point.anonymousIds), requests: count(point.requests),
      lowSample: Boolean(point.lowSample), cityCount: 1,
      hasCoordinates: point.longitude != null && point.latitude != null
        && Number.isFinite(longitude) && Number.isFinite(latitude)
        && longitude >= 72 && longitude <= 136 && latitude >= 3 && latitude <= 54,
    })
  }
  return [...cities.values()].map((city) => ({
    ...city,
    lowSample: city.lowSample || city.anonymousIds < (locations.lowSampleThreshold || 3),
  }))
}

export function filterCities(cities: GeoRow[], province = '', query = ''): GeoRow[] {
  const normalizedQuery = query.trim().toLocaleLowerCase()
  return cities.filter((city) => (!province || city.province === normalizeProvince(province))
    && (!normalizedQuery || `${city.label} ${city.province} ${city.code}`.toLocaleLowerCase().includes(normalizedQuery)))
}

export function groupProvinces(cities: GeoRow[], lowSampleThreshold = 3): GeoRow[] {
  const provinces = new Map<string, GeoRow>()
  for (const city of cities) {
    const row = provinces.get(city.province)
    if (row) {
      row.anonymousIds += city.anonymousIds
      row.requests += city.requests
      row.cityCount += 1
    } else {
      provinces.set(city.province, {
        ...city, code: city.province, label: city.province, cityCount: 1,
        // Province mode colors boundaries; no averaged user coordinates are created.
        longitude: NaN, latitude: NaN, hasCoordinates: false,
      })
    }
  }
  return [...provinces.values()].map((row) => ({ ...row, lowSample: row.anonymousIds < lowSampleThreshold }))
}

export function rankRows(rows: GeoRow[], metric: GeoMetric): GeoRow[] {
  return [...rows].sort((left, right) => right[metric] - left[metric]
    || left.label.localeCompare(right.label, 'zh-CN') || left.code.localeCompare(right.code))
}

export function sumRows(rows: GeoRow[], metric: GeoMetric): number {
  return rows.reduce((sum, row) => sum + row[metric], 0)
}

export function geoCoverage(locations: LocationData, cities = buildCities(locations)) {
  const mappedIds = sumRows(cities, 'anonymousIds')
  const mappedRequests = sumRows(cities, 'requests')
  const totalIds = count(locations.totalAnonymousIds)
  const totalRequests = count(locations.totalRequests)
  return {
    totalIds, totalRequests, mappedIds, mappedRequests,
    unmappedIds: Math.max(0, totalIds - mappedIds),
    unmappedRequests: Math.max(0, totalRequests - mappedRequests, count(locations.unmappedRequests)),
    missingCoordinates: cities.filter((city) => !city.hasCoordinates).length,
    inconsistent: mappedIds > totalIds || mappedRequests > totalRequests,
  }
}

export function share(value: number, denominator: number): number | null {
  return denominator > 0 ? value / denominator : null
}

export function bubbleRadius(value: number, maximum: number): number {
  return value > 0 && maximum > 0 ? 4 + Math.sqrt(value / maximum) * 17 : 0
}

export interface MapView { scale: number; x: number; y: number }
export function constrainView(view: MapView): MapView {
  const scale = Math.max(MIN_ZOOM, Math.min(MAX_ZOOM, view.scale))
  return {
    scale,
    x: Math.max(MAP_WIDTH * (1 - scale), Math.min(0, view.x)),
    y: Math.max(MAP_HEIGHT * (1 - scale), Math.min(0, view.y)),
  }
}

export function zoomView(view: MapView, factor: number, anchor: [number, number] = [MAP_WIDTH / 2, MAP_HEIGHT / 2]): MapView {
  const scale = Math.max(MIN_ZOOM, Math.min(MAX_ZOOM, view.scale * factor))
  const ratio = scale / view.scale
  return constrainView({ scale, x: anchor[0] - (anchor[0] - view.x) * ratio, y: anchor[1] - (anchor[1] - view.y) * ratio })
}

export function provinceView(province: string): MapView {
  const boundary = boundaries.find((item) => item.name === normalizeProvince(province))
  if (!boundary) return { scale: 1, x: 0, y: 0 }
  const [left, top, right, bottom] = boundary.bounds
  const scale = Math.max(1, Math.min(MAX_ZOOM, Math.min(MAP_WIDTH / (right - left + 80), MAP_HEIGHT / (bottom - top + 80))))
  return constrainView({ scale, x: MAP_WIDTH / 2 - (left + right) / 2 * scale, y: MAP_HEIGHT / 2 - (top + bottom) / 2 * scale })
}

function csvCell(value: string | number): string {
  // Escape spreadsheet formulas as well as CSV delimiters.
  const raw = String(value)
  const safe = /^[\s]*[=+@-]/u.test(raw) ? `'${raw}` : raw
  return `"${safe.replace(/"/g, '""')}"`
}

export function locationCsv(rows: GeoRow[], options: {
  level: GeoLevel; metric: GeoMetric; total: number; filteredTotal: number; rangeLabel: string
}): string {
  const percentage = (value: number, denominator: number) => denominator > 0 ? (value / denominator * 100).toFixed(2) : ''
  const table: (string | number)[][] = [[
    '统计区间', '汇总层级', '行政区代码', '地区', '省级地区', '匿名标识', '推荐请求',
    '城市数', '低样本', '占比指标', '全区间指标分母（含未归属）', '当前筛选指标分母', '全区间占比(%)', '当前筛选占比(%)',
  ]]
  for (const row of rows) table.push([
    options.rangeLabel, options.level === 'city' ? '城市' : '省级', row.code, row.label, row.province,
    row.anonymousIds, row.requests, row.cityCount, row.lowSample ? '是' : '否',
    options.metric === 'anonymousIds' ? '匿名标识' : '推荐请求', options.total, options.filteredTotal,
    percentage(row[options.metric], options.total), percentage(row[options.metric], options.filteredTotal),
  ])
  return '\uFEFF' + table.map((row) => row.map(csvCell).join(',')).join('\r\n') + '\r\n'
}
