<script setup lang="ts">
import { computed } from 'vue'
import { formatNumber, isMetric } from '../analytics'
import type { BarItem } from '../types'

const props = withDefaults(defineProps<{ items: BarItem[]; empty?: string; unit?: string; maxRows?: number }>(), { empty: '当前区间没有可展示的数据', unit: '', maxRows: 12 })
const shown = computed(() => props.items.slice(0, props.maxRows))
const maximum = computed(() => Math.max(1, ...shown.value.map(item => isMetric(item.value) ? item.value : 0)))
</script>

<template>
  <ol v-if="shown.length" class="data-bars">
    <li v-for="(item, index) in shown" :key="`${item.label}-${index}`">
      <div class="bar-label"><span>{{ item.label }}</span><strong>{{ formatNumber(item.value) }}<small v-if="unit"> {{ unit }}</small></strong></div>
      <div class="bar-track" aria-hidden="true"><span :style="{ width: `${isMetric(item.value) ? Math.max(0, item.value / maximum * 100) : 0}%`, background: item.tone }"></span></div>
    </li>
  </ol>
  <div v-else class="empty-inline"><span aria-hidden="true">∅</span><p>{{ empty }}</p></div>
</template>
