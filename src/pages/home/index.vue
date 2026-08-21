<template>
  <view class="home-page">
    <view class="pixel-spark pixel-spark--one" aria-hidden="true" />
    <view class="pixel-spark pixel-spark--two" aria-hidden="true" />

    <view class="brand-row">
      <text class="brand">ELMA</text>
      <text class="edition">MEAL DECISION / 0.12</text>
    </view>

    <view class="hero">
      <text class="product-name">家今天的饭</text>
      <text class="hero-copy">别选了，\n今天吃这个。</text>
      <text class="hero-note">只给你一个认真筛过的答案。</text>
    </view>

    <view class="location-row">
      <view class="location-copy">
        <view
          class="location-mark"
          :class="{ 'location-mark--loading': locationStatus === 'loading' }"
          aria-hidden="true"
        />
        <view>
          <text class="location-label">当前定位</text>
          <text class="location-value">{{ locationMessage }}</text>
          <text v-if="locationAccuracy" class="location-accuracy">
            精度约 {{ locationAccuracy }} 米
          </text>
        </view>
      </view>
      <button
        class="text-action"
        :class="{ 'text-action--disabled': locationStatus === 'loading' }"
        :disabled="locationStatus === 'loading'"
        @click="handleLocationAction"
      >
        {{ locationActionLabel }}
      </button>
    </view>

    <view class="choice-section">
      <view class="section-heading">
        <text class="section-index">01</text>
        <text class="section-title">走多远</text>
      </view>
      <view class="choice-row">
        <button
          v-for="option in radiusOptions"
          :key="option.value"
          class="choice-button"
          :class="{
            'choice-button--active': radius === option.value,
            'choice-button--disabled': submitting,
          }"
          :disabled="submitting"
          @click="radius = option.value"
        >
          {{ option.label }}
        </button>
      </view>
    </view>

    <view class="choice-section">
      <view class="section-heading">
        <text class="section-index">02</text>
        <text class="section-title">花多少</text>
      </view>
      <view class="choice-row">
        <button
          v-for="option in budgetOptions"
          :key="String(option.value)"
          class="choice-button"
          :class="{
            'choice-button--active': budget === option.value,
            'choice-button--disabled': submitting,
          }"
          :disabled="submitting"
          @click="budget = option.value"
        >
          {{ option.label }}
        </button>
      </view>
    </view>

    <view class="preference-grid">
      <view class="preference-field preference-field--category">
        <text class="field-label">想吃什么</text>
        <picker
          class="category-picker"
          :disabled="submitting"
          :range="categoryOptions"
          range-key="label"
          :value="categoryIndex"
          @change="handleCategoryChange"
        >
          <view class="category-value">
            <text>{{ selectedCategory.label }}</text>
            <text class="category-action">更改</text>
          </view>
        </picker>
      </view>
      <label class="preference-field preference-field--input">
        <text class="field-label">不想吃</text>
        <input
          v-model="dislikesInput"
          class="dislikes-input"
          :disabled="submitting"
          maxlength="309"
          placeholder="香菜、内脏……"
          placeholder-class="dislikes-placeholder"
        />
      </label>
    </view>

    <view class="decision-area">
      <button
        class="decision-button"
        :class="{ 'decision-button--disabled': submitting }"
        :disabled="submitting"
        @click="submitRecommendation"
      >
        <text>{{ submitting ? '正在决定…' : '帮我选' }}</text>
        <text class="decision-arrow">{{ submitting ? '···' : '→' }}</text>
      </button>
      <text v-if="requestError" class="request-error">{{ requestError }}</text>
      <text v-if="requestTraceId" class="request-trace">TRACE · {{ requestTraceId }}</text>
      <text class="decision-caption">ONE GOOD CHOICE, NOT A LIST.</text>
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
import type {
  CategoryFilterCode,
  CreateRecommendationRequest,
  Radius,
} from '@/types/recommendation'
import { DislikesValidationError, parseDislikes } from '@/utils/dislikes'

const radiusOptions: Array<{ label: string; value: Radius }> = [
  { label: '500m', value: 500 },
  { label: '1km', value: 1000 },
  { label: '2km', value: 2000 },
  { label: '3km', value: 3000 },
]

const budgetOptions: Array<{ label: string; value: number | null }> = [
  { label: '¥20', value: 20 },
  { label: '¥40', value: 40 },
  { label: '¥70', value: 70 },
  { label: '不限', value: null },
]

const categoryOptions: Array<{ label: string; value: CategoryFilterCode }> = [
  { label: '正餐', value: 'MEAL' },
  { label: '中餐（Chinese）', value: 'CHINESE' },
  { label: '火锅', value: 'HOT_POT' },
  { label: '烧烤', value: 'BARBECUE' },
  { label: '粉面', value: 'NOODLES' },
  { label: '小吃快餐', value: 'FAST_FOOD' },
  { label: '西餐', value: 'WESTERN' },
  { label: '日韩料理', value: 'JAPANESE_KOREAN' },
  { label: '饮品甜品', value: 'DESSERT_DRINK' },
  { label: '随便', value: 'ANY' },
]

const radius = ref<Radius>(1000)
const budget = ref<number | null>(null)
const category = ref<CategoryFilterCode>('MEAL')
const dislikesInput = ref('')
const locationStatus = ref<'idle' | 'loading' | 'success' | 'denied' | 'error'>('idle')
const locationAccuracy = ref<number | null>(null)
const currentLocation = ref<LocationCoordinates | null>(null)
const submitting = ref(false)
const requestError = ref('')
const requestTraceId = ref('')

const categoryIndex = computed(() =>
  Math.max(
    0,
    categoryOptions.findIndex((option) => option.value === category.value),
  ),
)
const selectedCategory = computed(() => categoryOptions[categoryIndex.value])

const locationMessage = computed(() => {
  switch (locationStatus.value) {
    case 'loading':
      return '正在获取当前位置…'
    case 'success':
      return '已获取当前位置'
    case 'denied':
      return '定位权限未开启'
    case 'error':
      return '暂时无法获取位置'
    default:
      return '等待获取位置'
  }
})

const locationActionLabel = computed(() => {
  if (locationStatus.value === 'loading') return '定位中'
  if (locationStatus.value === 'denied') return '去开启'
  return '重新定位'
})

async function locate() {
  locationStatus.value = 'loading'
  locationAccuracy.value = null
  currentLocation.value = null
  requestError.value = ''
  requestTraceId.value = ''

  try {
    const location = await LocationService.getCurrentLocation()
    currentLocation.value = location
    locationAccuracy.value = location.accuracy ? Math.round(location.accuracy) : null
    locationStatus.value = 'success'
  } catch (error) {
    locationStatus.value =
      error instanceof LocationServiceError && error.code === 'PERMISSION_DENIED' ? 'denied' : 'error'
  }
}

async function handleLocationAction() {
  if (locationStatus.value !== 'denied') {
    await locate()
    return
  }

  try {
    await PlatformService.openLocationSettings()
    await locate()
  } catch {
    uni.showToast({
      title: '请在系统设置中开启定位权限',
      icon: 'none',
    })
  }
}

function handleCategoryChange(event: { detail: { value: string | number } }) {
  const option = categoryOptions[Number(event.detail.value)]
  if (option) {
    category.value = option.value
  }
}

async function submitRecommendation() {
  if (submitting.value) return

  requestError.value = ''
  requestTraceId.value = ''

  if (!currentLocation.value) {
    requestError.value =
      locationStatus.value === 'denied' ? '请先开启定位权限，再帮你做决定' : '请先获取当前位置'
    return
  }

  let dislikes: string[]
  try {
    dislikes = parseDislikes(dislikesInput.value)
  } catch (error) {
    requestError.value =
      error instanceof DislikesValidationError ? error.message : '不想吃的内容格式不正确'
    return
  }

  const request: CreateRecommendationRequest = {
    latitude: currentLocation.value.latitude,
    longitude: currentLocation.value.longitude,
    radius: radius.value,
    maxBudget: budget.value,
    category: category.value,
    dislikes,
  }

  submitting.value = true
  try {
    const response = await createRecommendation(request)
    recommendationStore.setCurrent(response, request)
    uni.navigateTo({ url: '/pages/result/index' })
  } catch (error) {
    requestError.value = getUserFacingError(error)
    if (error instanceof ApiError) {
      requestTraceId.value = error.response?.traceId ?? ''
    }
  } finally {
    submitting.value = false
  }
}

onMounted(locate)
</script>

<style scoped>
.home-page {
  position: relative;
  display: flex;
  box-sizing: border-box;
  width: 100%;
  min-height: 100vh;
  flex-direction: column;
  overflow: hidden;
  padding: calc(var(--status-bar-height) + 40rpx) 48rpx 48rpx;
  background: #f8f8fb;
  color: #18203a;
}

.pixel-spark {
  position: absolute;
  width: 10rpx;
  height: 10rpx;
  background: #5b61d6;
  box-shadow: 16rpx 0 0 #d9d2f6, 0 16rpx 0 #bfe8db;
}

.pixel-spark--one {
  top: 166rpx;
  right: 68rpx;
}

.pixel-spark--two {
  top: 470rpx;
  right: 102rpx;
  transform: rotate(45deg) scale(0.75);
}

.brand-row,
.section-heading,
.location-row,
.location-copy,
.choice-row,
.category-value,
.decision-button {
  display: flex;
  align-items: center;
}

.brand-row {
  justify-content: space-between;
}

.brand {
  font-size: 28rpx;
  font-weight: 700;
  letter-spacing: 6rpx;
}

.edition {
  color: #747d97;
  font-size: 18rpx;
  letter-spacing: 2rpx;
}

.hero {
  display: flex;
  flex-direction: column;
  margin-top: 54rpx;
}

.product-name {
  color: #5b61d6;
  font-size: 24rpx;
  font-weight: 600;
  letter-spacing: 3rpx;
}

.hero-copy {
  margin-top: 16rpx;
  font-size: 64rpx;
  font-weight: 700;
  letter-spacing: -2rpx;
  line-height: 1.15;
  white-space: pre-line;
}

.hero-note {
  margin-top: 20rpx;
  color: #66708a;
  font-size: 24rpx;
  letter-spacing: 1rpx;
}

.location-row {
  justify-content: space-between;
  margin-top: 52rpx;
  padding: 24rpx 0;
  border-top: 2rpx solid #dde1ec;
  border-bottom: 2rpx solid #dde1ec;
}

.location-copy {
  gap: 20rpx;
}

.location-mark {
  width: 24rpx;
  height: 24rpx;
  border: 6rpx solid #5b61d6;
  border-radius: 50% 50% 50% 0;
  transform: rotate(-45deg);
}

.location-label,
.location-value,
.location-accuracy {
  display: block;
}

.location-label {
  color: #747d97;
  font-size: 20rpx;
}

.location-value {
  margin-top: 4rpx;
  font-size: 26rpx;
  font-weight: 600;
}

.location-accuracy {
  margin-top: 4rpx;
  color: #747d97;
  font-size: 18rpx;
}

.location-mark--loading {
  animation: location-pulse 1s steps(2, end) infinite;
}

.text-action {
  margin: 0;
  padding: 8rpx 0 8rpx 20rpx;
  background: transparent;
  color: #5b61d6;
  font-size: 22rpx;
  line-height: 1;
}

.text-action--disabled {
  color: #9ca3b7;
  opacity: 1;
}

@keyframes location-pulse {
  50% {
    opacity: 0.35;
  }
}

.choice-section {
  margin-top: 34rpx;
}

.section-heading {
  gap: 14rpx;
}

.section-index {
  color: #5b61d6;
  font-size: 18rpx;
  letter-spacing: 1rpx;
}

.section-title,
.field-label {
  font-size: 24rpx;
  font-weight: 600;
}

.choice-row {
  gap: 14rpx;
  margin-top: 18rpx;
}

.choice-button {
  width: 0;
  min-width: 0;
  flex: 1 1 0;
  margin: 0;
  padding: 19rpx 6rpx;
  border: 2rpx solid #dde1ec;
  border-radius: 12rpx;
  background: transparent;
  color: #49526c;
  font-size: 24rpx;
  line-height: 1;
}

.choice-button--active {
  border-color: #5b61d6;
  background: #e8e9ff;
  color: #343a9f;
  font-weight: 600;
}

.choice-button--disabled {
  opacity: 0.7;
}

.preference-grid {
  display: grid;
  grid-template-columns: 0.82fr 1.18fr;
  gap: 16rpx;
  margin-top: 36rpx;
}

.preference-field {
  min-width: 0;
  padding: 22rpx 24rpx;
  border-radius: 12rpx;
  background: #eef0f8;
}

.field-label {
  display: block;
  color: #66708a;
  font-size: 20rpx;
}

.category-value {
  justify-content: space-between;
  margin-top: 14rpx;
  font-size: 26rpx;
  font-weight: 600;
}

.category-picker {
  display: block;
}

.category-action {
  color: #5b61d6;
  font-size: 19rpx;
  font-weight: 500;
}

.dislikes-input {
  width: 100%;
  height: 42rpx;
  margin-top: 7rpx;
  color: #18203a;
  font-size: 25rpx;
}

.dislikes-placeholder {
  color: #9ca3b7;
}

.decision-area {
  margin-top: auto;
  padding-top: 42rpx;
}

.decision-button {
  width: 100%;
  justify-content: space-between;
  margin: 0;
  padding: 28rpx 34rpx;
  border-radius: 12rpx;
  background: #5b61d6;
  color: #ffffff;
  font-size: 30rpx;
  font-weight: 600;
  line-height: 1;
  text-align: left;
}

.decision-button--disabled {
  background: #7479c9;
  opacity: 1;
}

.decision-arrow {
  font-size: 40rpx;
  font-weight: 400;
}

.decision-caption {
  display: block;
  margin-top: 16rpx;
  color: #8a92a8;
  font-size: 17rpx;
  letter-spacing: 2rpx;
  text-align: center;
}

.request-error,
.request-trace {
  display: block;
  margin-top: 14rpx;
  text-align: center;
}

.request-error {
  color: #a6455a;
  font-size: 21rpx;
}

.request-trace {
  color: #8a92a8;
  font-size: 15rpx;
  letter-spacing: 1rpx;
}
</style>
