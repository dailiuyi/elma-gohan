<script setup lang="ts">
import { computed } from 'vue'
import { comparison, formatNumber, formatPercent } from '../analytics'
import type { Metric } from '../types'

const props = defineProps<{ label: string; value: Metric; previous?: Metric; rate?: boolean; decimal?: number; hint?: string; accent?: boolean; compare?: boolean }>()
const change = computed(() => comparison(props.value, props.previous, props.rate))
</script>

<template>
  <article class="metric-card" :class="{ 'metric-card--accent': accent }">
    <p class="metric-label">{{ label }}</p>
    <p class="metric-value">{{ rate ? formatPercent(value) : formatNumber(value, decimal) }}</p>
    <p v-if="compare" class="metric-change" :data-direction="change.direction">
      <span aria-hidden="true">{{ change.direction === 'up' ? '↗' : change.direction === 'down' ? '↘' : '—' }}</span>
      {{ change.label }}<span class="metric-comparison-label">较前期</span>
    </p>
    <p v-if="hint" class="metric-hint">{{ hint }}</p>
  </article>
</template>
