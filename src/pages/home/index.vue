<template>
  <view class="boot-page">
    <view class="nav">
      <text class="brand">ELMA</text>
    </view>

    <view class="hero">
      <text class="kicker">{{ kicker }}</text>
      <text class="title">{{ title }}</text>
      <text class="lede">{{ lede }}</text>
    </view>

    <view class="wave" aria-hidden="true">
      <view class="wave-arc wave-arc--a" />
      <view class="wave-arc wave-arc--b" />
    </view>

    <view v-if="locationStatus === 'denied'" class="ask">
      <button class="go-button" @click="handleLocationAction">去开启定位</button>
    </view>
    <view v-else-if="requestError" class="ask">
      <text class="error">{{ requestError }}</text>
      <button class="go-button" :disabled="submitting" @click="retryTonight">再写一次</button>
      <button class="adjust-button" :disabled="submitting" @click="sheetOpen = true">调整条件</button>
    </view>

    <button class="privacy-link" @click="openPrivacy">关于这些数据</button>

    <view v-if="sheetOpen" class="sheet" @click.self="sheetOpen = false">
      <view class="sheet-card">
        <text class="sheet-title">换个范围</text>
        <text class="sheet-hint">只在刚才没有合适答案时多问这一次。</text>

        <text class="label">多远</text>
        <view class="chips">
          <button
            v-for="option in radiusOptions"
            :key="option.label"
            class="chip"
            :class="{ 'chip--active': radius === option.radius && minDistance === option.minDistance }"
            :disabled="submitting"
            @click="selectRadius(option)"
          >
            {{ option.label }}
          </button>
        </view>

        <text class="label">多少</text>
        <view class="chips">
          <button
            v-for="option in budgetOptions"
            :key="option.label"
            class="chip"
            :class="{ 'chip--active': maxBudget === option.maxBudget && minBudget === option.minBudget }"
            :disabled="submitting"
            @click="selectBudget(option)"
          >
            {{ option.label }}
          </button>
        </view>

        <view class="prefs">
          <view class="field">
            <text class="label">想吃</text>
            <picker :range="categoryOptions" range-key="label" :value="categoryIndex" @change="handleCategoryChange">
              <text class="field-value">{{ selectedCategory.label }}</text>
            </picker>
          </view>
          <view class="field">
            <text class="label">不想吃</text>
            <input v-model="dislikesInput" class="field-input" maxlength="309" placeholder="香菜、内脏" />
          </view>
        </view>

        <text v-if="requestError" class="sheet-error">{{ requestError }}</text>
        <button class="go-button sheet-submit" :disabled="submitting" @click="refreshTonight">
          {{ submitting ? '正在刷新候选…' : '按这个刷新候选' }}
        </button>
        <button class="sheet-cancel" :disabled="submitting" @click="sheetOpen = false">先不改</button>
      </view>
    </view>
  </view>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'

import { createRecommendation } from '@/api/recommendation'
import { ApiError, getUserFacingError } from '@/api/errors'
import { LocationService, LocationServiceError } from '@/services/location'
import { PlatformService } from '@/services/platform'
import { recommendationStore } from '@/stores/recommendation'
import type { LocationCoordinates } from '@/types/location'
import type { CategoryFilterCode, CreateRecommendationRequest, Radius } from '@/types/recommendation'
import { parseDislikes } from '@/utils/dislikes'
import { currentMealSlot, slotKicker } from '@/utils/edition'
import {
  budgetOptions,
  categoryOptions,
  defaultBudget,
  defaultCategory,
  defaultRadius,
  radiusOptions,
  type BudgetOption,
  type RadiusOption,
} from '@/utils/filters'


const locationStatus = ref<'idle' | 'loading' | 'success' | 'denied' | 'error'>('idle')
const currentLocation = ref<LocationCoordinates | null>(null)
const submitting = ref(false)
const requestError = ref('')
const restored = ref(false)
const sheetOpen = ref(false)
const minDistance = ref<number | null>(defaultRadius.minDistance)
const radius = ref<Radius>(defaultRadius.radius)
const minBudget = ref<number | null>(defaultBudget.minBudget)
const maxBudget = ref<number | null>(defaultBudget.maxBudget)
const category = ref<CategoryFilterCode>(defaultCategory.value)
const dislikesInput = ref('')

const kicker = computed(() => slotKicker(currentMealSlot()))
const title = computed(() => {
  if (locationStatus.value === 'denied') return '这一页需要\n你在哪里。'
  if (restored.value) return '还是这一页。'
  if (submitting.value || locationStatus.value === 'loading') return '先看附近，\n再只留一家。'
  if (requestError.value) return '这一页没写成。'
  return '先看附近，\n再只留一家。'
})
const lede = computed(() => {
  if (locationStatus.value === 'denied') return '只为了找附近的店。不要名字，也不要微信号。'
  if (requestError.value) return ''
  return '少问一点。一次只给一家。'
})
const categoryIndex = computed(() =>
  Math.max(0, categoryOptions.findIndex((option) => option.value === category.value)),
)
const selectedCategory = computed(() => categoryOptions[categoryIndex.value])

function goResult() {
  uni.redirectTo({ url: '/pages/result/index' })
}

function buildRequest(location: LocationCoordinates): CreateRecommendationRequest {
  return {
    latitude: location.latitude,
    longitude: location.longitude,
    minDistance: minDistance.value,
    radius: radius.value,
    minBudget: minBudget.value,
    maxBudget: maxBudget.value,
    category: category.value,
    dislikes: parseDislikes(dislikesInput.value),
  }
}

function hydrateFilters() {
  const last = recommendationStore.state.lastRequest
  if (!last) return
  minDistance.value = last.minDistance ?? null
  radius.value = last.radius
  minBudget.value = last.minBudget ?? null
  maxBudget.value = last.maxBudget
  category.value = last.category
  dislikesInput.value = [...last.dislikes].join('，')
}

function selectRadius(option: RadiusOption) {
  minDistance.value = option.minDistance
  radius.value = option.radius
}

function selectBudget(option: BudgetOption) {
  minBudget.value = option.minBudget
  maxBudget.value = option.maxBudget
}

function handleCategoryChange(event: { detail: { value: string | number } }) {
  const option = categoryOptions[Number(event.detail.value)]
  if (option) category.value = option.value
}

async function locate() {
  locationStatus.value = 'loading'
  currentLocation.value = null
  requestError.value = ''
  try {
    const location = await LocationService.getCurrentLocation()
    currentLocation.value = location
    locationStatus.value = 'success'
    return location
  } catch (error) {
    locationStatus.value =
      error instanceof LocationServiceError && error.code === 'PERMISSION_DENIED' ? 'denied' : 'error'
    if (locationStatus.value === 'error') {
      requestError.value = '暂时无法获取位置'
    }
    return null
  }
}

async function openTonight(forceRefresh = false) {
  if (submitting.value) return
  requestError.value = ''

  if (!forceRefresh && recommendationStore.hydrate() && recommendationStore.state.current) {
    restored.value = true
    goResult()
    return
  }

  const location = currentLocation.value ?? (await locate())
  if (!location) {
    if (locationStatus.value !== 'denied') {
      requestError.value = '请先获取当前位置'
    }
    return
  }

  let request: CreateRecommendationRequest
  try {
    request = buildRequest(location)
  } catch (error) {
    requestError.value = error instanceof Error ? error.message : '不想吃的内容格式不正确'
    return
  }
  submitting.value = true
  try {
    const response = await createRecommendation(request)
    recommendationStore.setCurrent(response, request)
    sheetOpen.value = false
    goResult()
  } catch (error) {
    requestError.value = getUserFacingError(error)
    if (error instanceof ApiError) {
      requestError.value = error.response?.message ?? requestError.value
    }
  } finally {
    submitting.value = false
  }
}

function retryTonight() {
  void openTonight(false)
}

function refreshTonight() {
  void openTonight(true)
}

async function handleLocationAction() {
  if (locationStatus.value !== 'denied') {
    await locate()
    await openTonight()
    return
  }
  try {
    await PlatformService.openLocationSettings()
    await locate()
    await openTonight()
  } catch {
    uni.showToast({ title: '请在系统设置中开启定位权限', icon: 'none' })
  }
}

function openPrivacy() {
  uni.navigateTo({ url: '/pages/privacy/index' })
}

onMounted(() => {
  hydrateFilters()
  void openTonight()
})
</script>

<style scoped>
.boot-page {
  position: relative;
  display: flex;
  box-sizing: border-box;
  min-height: 100vh;
  flex-direction: column;
  padding: calc(var(--status-bar-height) + var(--elma-custom-nav-offset)) 48rpx 48rpx;
  background: #f7f7f5;
  color: #171717;
}

.nav { min-height: 48rpx; }
.brand {
  font-size: 22rpx;
  letter-spacing: 6rpx;
  color: #686865;
}

.hero { margin-top: 80rpx; }
.kicker {
  display: block;
  color: #5d6d5a;
  font-size: 22rpx;
  letter-spacing: 6rpx;
}
.title {
  display: block;
  margin-top: 28rpx;
  font-size: 64rpx;
  font-weight: 500;
  line-height: 1.18;
  white-space: pre-line;
}
.lede {
  display: block;
  margin-top: 24rpx;
  color: #686865;
  font-size: 26rpx;
  line-height: 1.7;
}

.wave {
  position: relative;
  height: 120rpx;
  margin-top: auto;
  overflow: hidden;
}
.wave-arc {
  position: absolute;
  left: -18%;
  width: 140%;
  height: 200rpx;
  border: 2rpx solid #5d6d5a;
  border-radius: 50%;
  opacity: 0.4;
  animation: drift 16s ease-in-out infinite alternate;
}
.wave-arc--b {
  top: 24rpx;
  opacity: 0.22;
  animation-duration: 21s;
  animation-direction: alternate-reverse;
}
@keyframes drift {
  from { transform: translateX(0); }
  to { transform: translateX(-24rpx); }
}

.ask { margin-top: 48rpx; }
.go-button {
  width: 100%;
  margin: 0;
  padding: 28rpx 32rpx;
  border-radius: 20rpx;
  background: #5d6d5a;
  color: #f7f7f5;
  font-size: 30rpx;
  text-align: left;
}
.adjust-button,
.sheet-cancel {
  width: 100%;
  margin: 18rpx 0 0;
  padding: 18rpx 0;
  background: transparent;
  color: #5d6d5a;
  font-size: 25rpx;
}
.error {
  display: block;
  margin-bottom: 24rpx;
  color: #8a4b3a;
  font-size: 26rpx;
}

.privacy-link {
  margin: 28rpx auto 0;
  padding: 8rpx;
  background: transparent;
  color: #686865;
  font-size: 22rpx;
}

.sheet {
  position: fixed;
  inset: 0;
  z-index: 20;
  background: rgba(23, 23, 23, 0.28);
}
.sheet-card {
  position: absolute;
  right: 0;
  bottom: 0;
  left: 0;
  box-sizing: border-box;
  max-height: 88vh;
  overflow: auto;
  padding: 48rpx 44rpx 64rpx;
  background: #f7f7f5;
}
.sheet-title { display: block; font-size: 32rpx; font-weight: 500; }
.sheet-hint {
  display: block;
  margin-top: 18rpx;
  color: #686865;
  font-size: 24rpx;
  line-height: 1.6;
}
.label {
  display: block;
  margin-top: 44rpx;
  color: #686865;
  font-size: 22rpx;
  letter-spacing: 4rpx;
}
.chips {
  display: flex;
  gap: 16rpx;
  margin-top: 22rpx;
}
.chip {
  min-width: 0;
  flex: 1;
  margin: 0;
  padding: 20rpx 0;
  border: 2rpx solid rgba(23, 23, 23, 0.12);
  border-radius: 16rpx;
  background: transparent;
  font-size: 24rpx;
}
.chip--active {
  border-color: #5d6d5a;
  background: #5d6d5a;
  color: #f7f7f5;
}
.prefs {
  display: flex;
  flex-direction: column;
  gap: 36rpx;
  margin-top: 44rpx;
}
.field { min-width: 0; flex: 1; }
.field .label { margin-top: 0; }
.field-value,
.field-input {
  display: block;
  margin-top: 18rpx;
  padding-bottom: 14rpx;
  border-bottom: 2rpx solid rgba(23, 23, 23, 0.12);
  font-size: 28rpx;
}
.field-input { height: 62rpx; }
.sheet-error {
  display: block;
  margin-top: 28rpx;
  color: #8a4b3a;
  font-size: 23rpx;
  line-height: 1.5;
}
.sheet-submit { margin-top: 48rpx; }
</style>
