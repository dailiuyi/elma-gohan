<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { formatNumber, isMetric } from '../analytics'
import type { Metric } from '../types'

const props = defineProps<{ rows: { date: string; value: Metric; secondary?: Metric }[]; label: string; secondaryLabel?: string }>()
const showTable = ref(false)
const activeIndex = ref<number | null>(null)
const keyboardActive = ref(false)
const activeRow = computed(() => activeIndex.value == null ? null : props.rows[activeIndex.value])
const graph = ref<SVGSVGElement | null>(null)
const width = 760
const height = 230
const left = 48
const top = 16
const bottom = 32
const plotWidth = width - left - 16
const plotHeight = height - top - bottom
const max = computed(() => Math.max(1, ...props.rows.flatMap(row => [row.value, row.secondary]).filter(isMetric)))
const x = (index: number) => left + (props.rows.length > 1 ? index / (props.rows.length - 1) : 0.5) * plotWidth
const y = (value: number) => top + plotHeight - value / max.value * plotHeight
function line(key: 'value' | 'secondary'): string {
  let previousValid = false
  return props.rows.map((row, index) => {
    const value = row[key]
    if (!isMetric(value)) { previousValid = false; return '' }
    const command = previousValid ? 'L' : 'M'
    previousValid = true
    return `${command}${x(index)},${y(value)}`
  }).join(' ')
}
const hasData = computed(() => props.rows.some(row => isMetric(row.value) || isMetric(row.secondary)))
const ticks = computed(() => [0, 0.25, 0.5, 0.75, 1].map(ratio => ({ value: max.value * ratio, y: y(max.value * ratio) })))
const labels = computed(() => props.rows.filter((_, index) => index === 0 || index === props.rows.length - 1 || index % Math.max(1, Math.ceil(props.rows.length / 5)) === 0).map(row => ({ date: row.date, index: props.rows.indexOf(row) })))
function pointAt(event: PointerEvent) {
  const bounds = graph.value?.getBoundingClientRect()
  if (!bounds?.width || !props.rows.length) return
  const position = ((event.clientX - bounds.left) / bounds.width * width - left) / plotWidth
  activeIndex.value = Math.min(props.rows.length - 1, Math.max(0, Math.round(position * (props.rows.length - 1))))
  keyboardActive.value = false
}
function handleKey(event: KeyboardEvent) {
  if (!props.rows.length || !['ArrowLeft', 'ArrowRight', 'Home', 'End', 'Escape'].includes(event.key)) return
  event.preventDefault()
  keyboardActive.value = true
  if (event.key === 'Escape') { activeIndex.value = null; return }
  if (event.key === 'Home') activeIndex.value = 0
  else if (event.key === 'End') activeIndex.value = props.rows.length - 1
  else activeIndex.value = Math.min(props.rows.length - 1, Math.max(0, (activeIndex.value ?? (event.key === 'ArrowRight' ? -1 : props.rows.length)) + (event.key === 'ArrowRight' ? 1 : -1)))
}
function leavePointer() { if (!keyboardActive.value) activeIndex.value = null }
watch(() => props.rows, () => { activeIndex.value = null })
</script>

<template>
  <div class="trend-chart">
    <div class="chart-legend"><span><i></i>{{ label }}</span><span v-if="secondaryLabel"><i class="secondary"></i>{{ secondaryLabel }}</span><button class="text-button" type="button" :aria-expanded="showTable" @click="showTable = !showTable">{{ showTable ? '收起数据表' : '查看数据表' }}</button></div>
    <div v-if="hasData" class="chart-interactive" tabindex="0" role="group" :aria-label="`${label}趋势交互图，使用左右方向键选择日期，Home和End跳转首尾，Escape清除选择`" @keydown="handleKey" @blur="activeIndex = null">
    <div v-if="activeRow" class="chart-tooltip" aria-live="polite"><strong>{{ activeRow.date }}</strong><span>{{ label }} {{ formatNumber(activeRow.value) }}</span><span v-if="secondaryLabel">{{ secondaryLabel }} {{ formatNumber(activeRow.secondary) }}</span></div>
    <svg ref="graph" :viewBox="`0 0 ${width} ${height}`" role="img" :aria-label="`${label}${secondaryLabel ? `与${secondaryLabel}` : ''}逐日趋势，可通过数据表读取精确值`" @pointermove="pointAt" @pointerdown="pointAt" @pointerleave="leavePointer">
      <g v-for="tick in ticks" :key="tick.y"><line :x1="left" :x2="width - 16" :y1="tick.y" :y2="tick.y" class="chart-grid" /><text :x="left - 10" :y="tick.y + 4" text-anchor="end" class="chart-label">{{ formatNumber(tick.value, max < 4 ? 1 : 0) }}</text></g>
      <path :d="line('value')" class="chart-line" />
      <path v-if="secondaryLabel" :d="line('secondary')" class="chart-line chart-line--secondary" />
      <line v-if="activeIndex !== null" :x1="x(activeIndex)" :x2="x(activeIndex)" :y1="top" :y2="height - bottom" class="chart-focus-line" />
      <g v-for="(row, index) in rows" :key="row.date"><circle v-if="isMetric(row.value)" :cx="x(index)" :cy="y(row.value)" r="3" class="chart-dot"><title>{{ row.date }} · {{ label }} {{ formatNumber(row.value) }}</title></circle><circle v-if="secondaryLabel && isMetric(row.secondary)" :cx="x(index)" :cy="y(row.secondary)" r="2.5" class="chart-dot chart-dot--secondary"><title>{{ row.date }} · {{ secondaryLabel }} {{ formatNumber(row.secondary) }}</title></circle></g>
      <text v-for="item in labels" :key="item.date" :x="x(item.index)" :y="height - 9" :text-anchor="item.index === 0 ? 'start' : item.index === rows.length - 1 ? 'end' : 'middle'" class="chart-label">{{ item.date.slice(5) }}</text>
    </svg>
    </div>
    <div v-else class="empty-inline"><span aria-hidden="true">∅</span><p>当前区间暂无趋势数据</p></div>
    <p v-if="hasData" class="chart-help">移动指针或轻触查看单日；聚焦图表后可使用 ← → / Home / End。</p>
    <div v-if="showTable" class="table-scroll"><table><caption class="sr-only">每日趋势精确数据</caption><thead><tr><th scope="col">日期</th><th scope="col">{{ label }}</th><th v-if="secondaryLabel" scope="col">{{ secondaryLabel }}</th></tr></thead><tbody><tr v-for="row in rows" :key="row.date"><th scope="row">{{ row.date }}</th><td>{{ formatNumber(row.value) }}</td><td v-if="secondaryLabel">{{ formatNumber(row.secondary) }}</td></tr></tbody></table></div>
  </div>
</template>
