<template>
  <view v-if="recommendation" class="result-page">
    <view class="result-nav">
      <button class="back-button" aria-label="返回首页" @click="goHome">←</button>
      <text class="result-brand">ELMA / TODAY</text>
      <view class="nav-pixels" aria-hidden="true" />
    </view>

    <view class="pick-heading">
      <text class="pick-index">TODAY'S PICK · 01</text>
      <text class="pick-kicker">别选了，今天吃这个。</text>
    </view>

    <view v-if="recommendation.searchNotice" class="search-notice" role="status">
      <text class="search-notice-code">SEARCH STATUS</text>
      <text class="search-notice-message">{{ recommendation.searchNotice.message }}</text>
    </view>

    <view class="restaurant-section">
      <text class="category-label">{{ recommendation.restaurant.category.label }}</text>
      <text class="restaurant-name">{{ recommendation.restaurant.name }}</text>
      <text class="restaurant-address">{{ recommendation.restaurant.address }}</text>

      <view class="restaurant-meta">
        <view class="meta-item">
          <text class="meta-value">{{ formatDistance(recommendation.restaurant.distanceMeters) }}</text>
          <text class="meta-label">距离</text>
        </view>
        <view class="meta-rule" />
        <view class="meta-item">
          <text class="meta-value">{{ formatPrice(recommendation.restaurant.averagePrice) }}</text>
          <text class="meta-label">人均</text>
        </view>
        <view class="meta-rule" />
        <view class="meta-item">
          <text class="meta-value">{{ recommendation.restaurant.walkingMinutes }} 分钟</text>
          <text class="meta-label">步行</text>
        </view>
      </view>
    </view>

    <view class="risk-strip" :class="`risk-strip--${recommendation.risk.riskLevel.toLowerCase()}`">
      <view>
        <text class="risk-caption">RISK CHECK</text>
        <text class="risk-label">{{ riskLabel }}</text>
      </view>
      <view class="risk-signal" aria-hidden="true">
        <view
          v-for="level in 4"
          :key="level"
          class="risk-dot"
          :class="{ 'risk-dot--active': level <= riskSignalLevel }"
        />
      </view>
    </view>

    <view v-if="visibleRiskReasons.length" class="risk-reasons">
      <text v-for="reason in visibleRiskReasons" :key="reason" class="risk-reason">
        {{ reason }}
      </text>
    </view>

    <view v-if="recommendation.evidenceSummary" class="evidence-section">
      <view class="evidence-heading">
        <text class="evidence-title">数据来源</text>
        <text class="evidence-match">{{ evidenceMatchLabel }}</text>
      </view>
      <view class="evidence-platforms">
        <view class="evidence-platform">
          <text class="evidence-source">高德</text>
          <text class="evidence-rating">{{ formatRating(recommendation.evidenceSummary.amap.rating) }}</text>
        </view>
        <view class="evidence-platform">
          <text class="evidence-source">百度</text>
          <text class="evidence-rating">{{ formatRating(recommendation.evidenceSummary.baidu.rating) }}</text>
        </view>
      </view>
      <text v-if="baiduDetailRatings" class="evidence-details">{{ baiduDetailRatings }}</text>
      <text class="evidence-reason">{{ recommendation.evidenceSummary.reason }}</text>
    </view>

    <view class="reason-section">
      <view class="reason-heading">
        <text class="reason-title">为什么是它</text>
        <text class="reason-count">{{ String(recommendation.reasons.length).padStart(2, '0') }}</text>
      </view>
      <view v-if="recommendation.personalization" class="personalization-meta">
        <text>{{ personalizationLabel }}</text>
        <text>匹配度 {{ Math.round(recommendation.personalization.tasteMatchScore) }}</text>
      </view>
      <view v-for="reason in recommendation.reasons" :key="reason" class="reason-row">
        <view class="reason-pixel" aria-hidden="true" />
        <text>{{ reason }}</text>
      </view>
    </view>

    <view class="result-actions">
      <button
        class="accept-button"
        :class="{ 'accept-button--disabled': operationBusy }"
        :disabled="operationBusy"
        @click="openNavigation"
      >
        <text>{{ navigating ? '正在打开地图…' : '就它了' }}</text>
        <text class="accept-arrow">↗</text>
      </button>
      <button
        class="deep-evidence-button"
        :class="{ 'deep-evidence-button--disabled': operationBusy }"
        :disabled="operationBusy"
        @click="openDeepEvidence"
      >
        深挖一下这家
      </button>
      <button
        v-if="recommendation.alternativesRemaining > 0"
        class="reroll-button"
        :class="{ 'reroll-button--disabled': operationBusy }"
        :disabled="operationBusy"
        @click="reroll"
      >
        {{ rerolling ? '正在换一家…' : `换一家 · 还可以换 ${recommendation.alternativesRemaining} 次` }}
      </button>
      <text v-else class="alternatives-exhausted">备选已用完，今天就从这家出发吧。</text>
      <text v-if="operationError" class="operation-error">{{ operationError }}</text>
      <text v-if="operationTraceId" class="operation-trace">TRACE · {{ operationTraceId }}</text>
    </view>

    <view class="feedback-section">
      <text class="feedback-title">
        {{ selectedFeedback ? '这家反馈已记录' : '这个答案怎么样？' }}
      </text>
      <view class="feedback-row">
        <button
          v-for="option in feedbackOptions"
          :key="option.value"
          class="feedback-button"
          :class="{
            'feedback-button--active': selectedFeedback === option.value || pendingFeedback === option.value,
            'feedback-button--disabled': operationBusy || selectedFeedback !== null,
          }"
          :disabled="operationBusy || selectedFeedback !== null"
          @click="chooseFeedback(option.value)"
        >
          <text class="feedback-icon">{{ option.icon }}</text>
          <text>{{ submittingFeedback && pendingFeedback === option.value ? '提交中' : option.label }}</text>
        </button>
      </view>
      <view v-if="feedbackPanelOpen" class="flavor-panel">
        <text class="flavor-title">{{ flavorPrompt }}</text>
        <view class="flavor-options">
          <button
            v-for="flavor in flavorOptions"
            :key="flavor.value"
            class="flavor-option"
            :class="{ 'flavor-option--active': selectedFlavorTags.includes(flavor.value) }"
            :disabled="submittingFeedback"
            @click="toggleFlavor(flavor.value)"
          >
            {{ flavor.label }}
          </button>
        </view>
        <button class="flavor-submit" :disabled="submittingFeedback" @click="submitPendingFeedback">
          {{ submittingFeedback ? '提交中…' : selectedFlavorTags.length ? '提交反馈' : '跳过标签并提交' }}
        </button>
      </view>
    </view>
  </view>
</template>

<script setup lang="ts">
import { onLoad, onUnload } from '@dcloudio/uni-app'
import { computed, ref } from 'vue'

import { rerollRecommendation, submitRecommendationBehavior, submitRecommendationFeedback } from '@/api/recommendation'
import { ApiError, getUserFacingError } from '@/api/errors'
import { NavigationService, NavigationServiceError } from '@/services/navigation'
import { recommendationStore } from '@/stores/recommendation'
import { createUuidV4 } from '@/services/anonymous-user'
import type { BehaviorType, FeedbackResult, FlavorTag, RiskLevel } from '@/types/recommendation'

const riskLabels: Record<RiskLevel, string> = {
  LOW: '低风险',
  MEDIUM_LOW: '中低风险',
  MEDIUM: '中风险',
  HIGH: '高风险',
}

const riskSignalLevels: Record<RiskLevel, number> = {
  LOW: 1,
  MEDIUM_LOW: 2,
  MEDIUM: 3,
  HIGH: 4,
}

const feedbackOptions: Array<{ icon: string; label: string; value: FeedbackResult }> = [
  { icon: '↑', label: '不错', value: 'LIKE' },
  { icon: '—', label: '一般', value: 'NORMAL' },
  { icon: '↓', label: '踩坑', value: 'DISLIKE' },
]
const flavorOptions: Array<{ label: string; value: FlavorTag }> = [
  { label: '辣', value: 'SPICY' },
  { label: '甜', value: 'SWEET' },
  { label: '油', value: 'OILY' },
  { label: '咸', value: 'SALTY' },
  { label: '清淡', value: 'LIGHT' },
]

const recommendation = computed(() => recommendationStore.state.current)
const rerolling = ref(false)
const navigating = ref(false)
const submittingFeedback = ref(false)
const pendingFeedback = ref<FeedbackResult | null>(null)
const feedbackPanelOpen = ref(false)
const selectedFlavorTags = ref<FlavorTag[]>([])
const acceptedCurrent = ref(false)
const feedbackSubmitted = ref(false)
const skipReported = new Set<string>()
const eventIds = new Map<string, string>()
const operationError = ref('')
const operationTraceId = ref('')

const riskLabel = computed(() =>
  recommendation.value ? riskLabels[recommendation.value.risk.riskLevel] : '',
)
const riskSignalLevel = computed(() =>
  recommendation.value ? riskSignalLevels[recommendation.value.risk.riskLevel] : 0,
)
const visibleRiskReasons = computed(() => recommendation.value?.risk.reasons.slice(0, 2) ?? [])
const evidenceMatchLabel = computed(() => {
  switch (recommendation.value?.evidenceSummary?.matchStatus) {
    case 'MATCHED': return '已匹配同一门店'
    case 'AMBIGUOUS': return '存在相似门店'
    case 'UNAVAILABLE': return '百度暂不可用'
    default: return '百度暂未匹配'
  }
})
const baiduDetailRatings = computed(() => {
  const source = recommendation.value?.evidenceSummary?.baidu
  if (!source) return ''
  return [
    source.tasteRating === null ? '' : `口味 ${source.tasteRating.toFixed(1)}`,
    source.serviceRating === null ? '' : `服务 ${source.serviceRating.toFixed(1)}`,
    source.environmentRating === null ? '' : `环境 ${source.environmentRating.toFixed(1)}`,
  ].filter(Boolean).join(' · ')
})
const selectedFeedback = computed(() => recommendationStore.getCurrentFeedback())
const flavorPrompt = computed(() => pendingFeedback.value === 'LIKE'
  ? '哪里合你口味？最多选 3 个'
  : pendingFeedback.value === 'DISLIKE'
    ? '哪里不合口味？最多选 3 个'
    : '这家有什么口味特点？最多选 3 个')
const personalizationLabel = computed(() => {
  switch (recommendation.value?.personalization?.selectionMode) {
    case 'EXPLORATION': return '低风险探索'
    case 'PERSONALIZED': return '为你匹配'
    default: return '默认推荐'
  }
})
const operationBusy = computed(
  () => rerolling.value || navigating.value || submittingFeedback.value,
)

onLoad(() => {
  if (!recommendation.value) goHome()
})

onUnload(() => {
  reportSkipBestEffort()
})

function formatDistance(distanceMeters: number) {
  return distanceMeters < 1000 ? `${distanceMeters}m` : `${(distanceMeters / 1000).toFixed(1)}km`
}

function formatPrice(averagePrice: number | null) {
  return averagePrice === null ? '暂无' : `¥${averagePrice}`
}

function formatRating(rating: number | null) {
  return rating === null ? '暂无评分' : rating.toFixed(1)
}

function goHome() {
  reportSkipBestEffort()
  recommendationStore.clear()
  uni.reLaunch({ url: '/pages/home/index' })
}

function resetOperationError() {
  operationError.value = ''
  operationTraceId.value = ''
}

function handleOperationError(error: unknown) {
  if (
    error instanceof ApiError &&
    error.response?.code === 'RECOMMENDATION_NOT_FOUND'
  ) {
    recommendationStore.clear()
    uni.showToast({ title: error.response.message, icon: 'none' })
    uni.reLaunch({ url: '/pages/home/index' })
    return
  }

  operationError.value =
    error instanceof NavigationServiceError ? error.message : getUserFacingError(error)
  operationTraceId.value = error instanceof ApiError ? error.response?.traceId ?? '' : ''
}

async function reroll() {
  const current = recommendation.value
  if (!current || current.alternativesRemaining <= 0 || operationBusy.value) return

  resetOperationError()
  rerolling.value = true
  try {
    const response = await rerollRecommendation(current.recommendationId)
    recommendationStore.replaceCurrent(response)
    resetInteractionState()
  } catch (error) {
    handleOperationError(error)
  } finally {
    rerolling.value = false
  }
}

async function openNavigation() {
  const current = recommendation.value
  if (!current || operationBusy.value) return

  resetOperationError()
  navigating.value = true
  acceptedCurrent.value = true
  void reportBehavior('ACCEPT', current.recommendationId, current.restaurant.id)
  try {
    await NavigationService.openRestaurant(current.restaurant)
    void reportBehavior('NAVIGATE', current.recommendationId, current.restaurant.id)
  } catch (error) {
    handleOperationError(error)
  } finally {
    navigating.value = false
  }
}

function openDeepEvidence() {
  const current = recommendation.value
  if (!current || operationBusy.value) return
  uni.navigateTo({
    url: `/pages/deep-evidence/index?recommendationId=${encodeURIComponent(current.recommendationId)}`
      + `&restaurantId=${encodeURIComponent(current.restaurant.id)}`,
  })
}

function chooseFeedback(result: FeedbackResult) {
  if (operationBusy.value || selectedFeedback.value) return
  pendingFeedback.value = result
  selectedFlavorTags.value = []
  feedbackPanelOpen.value = true
}

function toggleFlavor(tag: FlavorTag) {
  const index = selectedFlavorTags.value.indexOf(tag)
  if (index >= 0) {
    selectedFlavorTags.value.splice(index, 1)
  } else if (selectedFlavorTags.value.length < 3) {
    selectedFlavorTags.value.push(tag)
  } else {
    uni.showToast({ title: '最多选择 3 个', icon: 'none' })
  }
}

async function submitPendingFeedback() {
  const current = recommendation.value
  const result = pendingFeedback.value
  if (!current || !result || operationBusy.value || selectedFeedback.value) return

  resetOperationError()
  submittingFeedback.value = true
  try {
    const response = await submitRecommendationFeedback(
      current.recommendationId,
      result,
      selectedFlavorTags.value,
    )
    recommendationStore.recordFeedback(response)
    feedbackSubmitted.value = true
    feedbackPanelOpen.value = false
  } catch (error) {
    handleOperationError(error)
  } finally {
    submittingFeedback.value = false
  }
}

function resetInteractionState() {
  acceptedCurrent.value = false
  feedbackSubmitted.value = false
  feedbackPanelOpen.value = false
  pendingFeedback.value = null
  selectedFlavorTags.value = []
}

function behaviorEventId(recommendationId: string, restaurantId: string, type: BehaviorType) {
  const key = `${recommendationId}:${restaurantId}:${type}`
  const existing = eventIds.get(key)
  if (existing) return existing
  const created = createUuidV4()
  eventIds.set(key, created)
  return created
}

async function reportBehavior(
  type: BehaviorType,
  recommendationId: string,
  restaurantId: string,
) {
  try {
    await submitRecommendationBehavior(
      recommendationId,
      behaviorEventId(recommendationId, restaurantId, type),
      restaurantId,
      type,
    )
  } catch {
    // 行为是 best-effort 信号，不能阻断主流程。
  }
}

function reportSkipBestEffort() {
  const current = recommendation.value
  if (!current || acceptedCurrent.value || feedbackSubmitted.value
    || skipReported.has(current.restaurant.id)) return
  skipReported.add(current.restaurant.id)
  void reportBehavior('SKIP', current.recommendationId, current.restaurant.id)
}
</script>

<style scoped>
.result-page {
  position: relative;
  box-sizing: border-box;
  width: 100%;
  min-height: 100vh;
  overflow: hidden;
  padding: calc(var(--status-bar-height) + 34rpx) 48rpx 54rpx;
  background: #f8f8fb;
  color: #18203a;
}

.result-nav,
.pick-heading,
.restaurant-meta,
.risk-strip,
.risk-signal,
.reason-heading,
.reason-row,
.accept-button,
.feedback-row,
.feedback-button {
  display: flex;
  align-items: center;
}

.result-nav {
  justify-content: space-between;
}

.back-button {
  width: 56rpx;
  height: 56rpx;
  margin: 0;
  padding: 0;
  border: 2rpx solid #dde1ec;
  border-radius: 12rpx;
  background: transparent;
  color: #18203a;
  font-size: 28rpx;
  line-height: 52rpx;
}

.result-brand {
  font-size: 19rpx;
  font-weight: 600;
  letter-spacing: 3rpx;
}

.nav-pixels {
  width: 10rpx;
  height: 10rpx;
  margin-right: 16rpx;
  background: #5b61d6;
  box-shadow: 14rpx 0 0 #d9d2f6, 0 14rpx 0 #bfe8db;
}

.pick-heading {
  justify-content: space-between;
  margin-top: 54rpx;
}

.pick-index {
  color: #5b61d6;
  font-size: 18rpx;
  letter-spacing: 2rpx;
}

.pick-kicker {
  color: #747d97;
  font-size: 20rpx;
}

.search-notice {
  margin-top: 28rpx;
  padding: 22rpx 24rpx;
  border: 2rpx solid #d8d9f2;
  border-radius: 14rpx;
  background: #f0f0ff;
}

.search-notice-code,
.search-notice-message {
  display: block;
}

.search-notice-code {
  color: #5b61d6;
  font-size: 17rpx;
  font-weight: 600;
  letter-spacing: 2rpx;
}

.search-notice-message {
  margin-top: 8rpx;
  color: #505a77;
  font-size: 22rpx;
  line-height: 1.55;
}

.restaurant-section {
  margin-top: 42rpx;
}

.category-label {
  display: inline-block;
  padding: 8rpx 15rpx;
  border-radius: 8rpx;
  background: #e8e9ff;
  color: #343a9f;
  font-size: 20rpx;
  font-weight: 600;
}

.restaurant-name,
.restaurant-address {
  display: block;
}

.restaurant-name {
  margin-top: 18rpx;
  font-size: 62rpx;
  font-weight: 700;
  letter-spacing: -2rpx;
  line-height: 1.16;
}

.restaurant-address {
  margin-top: 14rpx;
  color: #66708a;
  font-size: 23rpx;
}

.restaurant-meta {
  margin-top: 38rpx;
  padding: 27rpx 0;
  border-top: 2rpx solid #dde1ec;
  border-bottom: 2rpx solid #dde1ec;
}

.meta-item {
  min-width: 0;
  flex: 1 1 0;
}

.meta-value,
.meta-label {
  display: block;
  text-align: center;
}

.meta-value {
  font-size: 28rpx;
  font-weight: 700;
}

.meta-label {
  margin-top: 6rpx;
  color: #747d97;
  font-size: 18rpx;
}

.meta-rule {
  width: 2rpx;
  height: 42rpx;
  background: #dde1ec;
}

.risk-strip {
  justify-content: space-between;
  margin-top: 30rpx;
  padding: 24rpx 26rpx;
  border-radius: 12rpx;
  background: #dff2ec;
}

.risk-caption,
.risk-label {
  display: block;
}

.risk-caption {
  color: #477568;
  font-size: 16rpx;
  letter-spacing: 2rpx;
}

.risk-label {
  margin-top: 4rpx;
  font-size: 27rpx;
  font-weight: 700;
}

.risk-signal {
  gap: 8rpx;
}

.risk-reasons {
  padding: 12rpx 8rpx 0;
}

.risk-reason {
  display: block;
  margin-top: 6rpx;
  color: #66708a;
  font-size: 18rpx;
  line-height: 1.4;
}

.risk-dot {
  width: 12rpx;
  height: 12rpx;
  background: #b7cec7;
}

.risk-dot--active {
  background: #2f7d6a;
}

.risk-strip--medium .risk-dot--active,
.risk-strip--high .risk-dot--active {
  background: #a86145;
}

.evidence-section {
  margin-top: 26rpx;
  padding: 24rpx;
  border: 2rpx solid #dfe2ed;
  border-radius: 12rpx;
  background: #f7f8fc;
}

.evidence-heading,
.evidence-platforms,
.evidence-platform {
  display: flex;
}

.evidence-heading {
  justify-content: space-between;
  align-items: center;
}

.evidence-title {
  color: #303851;
  font-size: 23rpx;
  font-weight: 700;
}

.evidence-match {
  color: #68718a;
  font-size: 17rpx;
}

.evidence-platforms {
  gap: 16rpx;
  margin-top: 18rpx;
}

.evidence-platform {
  flex: 1;
  justify-content: space-between;
  padding: 15rpx 17rpx;
  background: #ffffff;
}

.evidence-source {
  color: #747d97;
  font-size: 18rpx;
}

.evidence-rating {
  color: #303851;
  font-size: 22rpx;
  font-weight: 700;
}

.evidence-details,
.evidence-reason {
  display: block;
  margin-top: 14rpx;
  color: #59627c;
  font-size: 18rpx;
  line-height: 1.5;
}

.evidence-reason {
  color: #3f4862;
}

.reason-section {
  margin-top: 42rpx;
}

.reason-heading {
  justify-content: space-between;
  padding-bottom: 16rpx;
}

.reason-title {
  font-size: 25rpx;
  font-weight: 700;
}

.reason-count {
  color: #5b61d6;
  font-size: 18rpx;
  letter-spacing: 2rpx;
}

.reason-row {
  gap: 20rpx;
  padding: 17rpx 0;
  border-top: 2rpx solid #eaecf3;
  color: #3f4862;
  font-size: 23rpx;
}

.reason-pixel {
  width: 10rpx;
  height: 10rpx;
  flex: 0 0 auto;
  background: #5b61d6;
  box-shadow: 6rpx 6rpx 0 #d9d2f6;
}

.result-actions {
  margin-top: 38rpx;
}

.accept-button {
  width: 100%;
  justify-content: space-between;
  margin: 0;
  padding: 27rpx 34rpx;
  border-radius: 12rpx;
  background: #5b61d6;
  color: #ffffff;
  font-size: 30rpx;
  font-weight: 600;
  line-height: 1;
  text-align: left;
}

.accept-arrow {
  font-size: 36rpx;
  font-weight: 400;
}

.personalization-meta {
  display: flex;
  justify-content: space-between;
  margin-bottom: 12rpx;
  color: #5b61d6;
  font-size: 18rpx;
}

.deep-evidence-button {
  width: 100%;
  margin: 16rpx 0 0;
  padding: 23rpx 24rpx;
  border: 2rpx solid #5b61d6;
  border-radius: 12rpx;
  background: #f0f0ff;
  color: #4147ad;
  font-size: 23rpx;
  font-weight: 600;
  line-height: 1;
}

.deep-evidence-button--disabled {
  opacity: 0.62;
}

.reroll-button {
  width: 100%;
  margin: 16rpx 0 0;
  padding: 23rpx 24rpx;
  border: 2rpx solid #cfd3e1;
  border-radius: 12rpx;
  background: transparent;
  color: #3f4862;
  font-size: 23rpx;
  line-height: 1;
}

.reroll-button--disabled {
  opacity: 0.65;
}

.accept-button--disabled {
  background: #7479c9;
  opacity: 1;
}

.alternatives-exhausted,
.operation-error,
.operation-trace {
  display: block;
  margin-top: 16rpx;
  text-align: center;
}

.alternatives-exhausted {
  color: #747d97;
  font-size: 19rpx;
}

.operation-error {
  color: #a6455a;
  font-size: 21rpx;
}

.operation-trace {
  color: #8a92a8;
  font-size: 15rpx;
  letter-spacing: 1rpx;
}

.feedback-section {
  margin-top: 38rpx;
}

.feedback-title {
  display: block;
  color: #747d97;
  font-size: 20rpx;
  text-align: center;
}

.feedback-row {
  gap: 12rpx;
  margin-top: 16rpx;
}

.feedback-button {
  flex: 1;
  justify-content: center;
  gap: 8rpx;
  margin: 0;
  padding: 17rpx 10rpx;
  border-radius: 10rpx;
  background: #eef0f8;
  color: #59627c;
  font-size: 21rpx;
  line-height: 1;
}

.feedback-button--active {
  background: #e8e9ff;
  color: #343a9f;
  font-weight: 600;
}

.feedback-button--disabled {
  opacity: 0.62;
}

.feedback-button--active.feedback-button--disabled {
  opacity: 1;
}

.feedback-icon {
  color: #5b61d6;
  font-size: 24rpx;
}

.flavor-panel {
  margin-top: 18rpx;
  padding: 22rpx;
  border: 2rpx solid #dfe2ed;
  border-radius: 12rpx;
  background: #f7f8fc;
}

.flavor-title {
  display: block;
  color: #3f4862;
  font-size: 20rpx;
}

.flavor-options {
  display: flex;
  flex-wrap: wrap;
  gap: 12rpx;
  margin-top: 16rpx;
}

.flavor-option {
  margin: 0;
  padding: 13rpx 22rpx;
  border: 2rpx solid #d7dae6;
  border-radius: 999rpx;
  background: #ffffff;
  color: #59627c;
  font-size: 20rpx;
  line-height: 1;
}

.flavor-option--active {
  border-color: #5b61d6;
  background: #e8e9ff;
  color: #343a9f;
}

.flavor-submit {
  width: 100%;
  margin: 18rpx 0 0;
  padding: 18rpx;
  border-radius: 10rpx;
  background: #5b61d6;
  color: #ffffff;
  font-size: 21rpx;
  line-height: 1;
}
</style>
  suppressSkip.value = true
