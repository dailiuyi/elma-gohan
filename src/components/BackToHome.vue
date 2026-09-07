<template>
  <button class="back-home" :aria-label="label" @click="goHome">{{ label }}</button>
</template>

<script setup lang="ts">
import { goHome } from '@/utils/home-stay'

withDefaults(defineProps<{
  label?: string
}>(), {
  label: '← 首页',
})
</script>

<style scoped>
.back-home {
  margin: 0;
  padding: 8rpx 0;
  background: transparent;
  color: #5d6d5a;
  font-size: 26rpx;
  line-height: 1;
  text-align: left;
}
</style>
