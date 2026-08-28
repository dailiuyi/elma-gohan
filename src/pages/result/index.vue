<template>
  <view v-if="recommendation" class="result-page">
    <view class="nav">
      <text class="brand">ELMA</text>
      <button v-if="viewMode === 'page'" class="text-btn" @click="sheetOpen = true">改条件</button>
      <button v-else class="text-btn" @click="viewMode = 'page'">← 回去</button>
    </view>

    <view v-if="viewMode === 'page'" class="leaf" :class="{ ink: inkPlay }">
      <text class="kicker">{{ kicker }}</text>
      <text class="date">{{ dateLine }}</text>
      <text class="name">{{ recommendation.restaurant.name }}</text>
      <text class="chorus">别选了。</text>
      <text class="line">{{ headline }}</text>
      <text class="meta">{{ metaLine }}</text>
      <view v-if="recommendation.searchNotice" class="search-notice">
        <text class="search-notice-message">{{ recommendation.searchNotice.message }}</text>
      </view>
      <text v-if="recommendation.alternativesRemaining <= 0" class="sealed">
        今晚写完了。下一顿再开一页。
      </text>
      <view class="wave" aria-hidden="true">
        <view class="wave-arc wave-arc--a" />
        <view class="wave-arc wave-arc--b" />
      </view>
    </view>

    <view v-else-if="viewMode === 'poster'" class="leaf">
      <text class="kicker">{{ kicker }}</text>
      <text class="date">{{ dateLine }}</text>
      <text class="name">{{ recommendation.restaurant.name }}</text>
      <text class="chorus">别选了。</text>
      <text class="line">{{ headline }}</text>
      <text class="meta">{{ metaLine }}</text>
      <view class="wave" aria-hidden="true">
        <view class="wave-arc wave-arc--a" />
        <view class="wave-arc wave-arc--b" />
      </view>
      <text class="poster-hint">没有按钮的这一页，截了就可以发群。</text>
    </view>

    <view v-else class="leaf">
      <text class="kicker">出门</text>
      <text class="name">{{ recommendation.restaurant.name }}</text>
      <text class="chorus">{{ navigating ? '正在打开地图…' : '地图打开了。' }}</text>
      <text class="line">吃完回来，可以告诉我这顿怎么样。只留给下一次。</text>
      <view class="feedback-section">
        <text class="feedback-title">
          {{ selectedFeedback ? '记下了。' : '这顿怎么样' }}
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
            {{ option.label }}
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
            {{ submittingFeedback ? '记下…' : '记下' }}
          </button>
        </view>
      </view>
    </view>

    <view v-if="viewMode === 'page'" class="dock">
      <button
        class="go-button accept-button"
        :class="{ 'accept-button--disabled': operationBusy }"
        :disabled="operationBusy"
        @click="openNavigation"
      >
        就它了
      </button>
      <button
        v-if="recommendation.alternativesRemaining > 0"
        class="side-button reroll-button"
        :class="{ 'reroll-button--disabled': operationBusy }"
        :disabled="operationBusy"
        @click="reroll"
      >
        {{ rerolling ? '在写下一页…' : `下一页 · ${recommendation.alternativesRemaining}` }}
      </button>
      <button
        v-else
        class="side-button"
        disabled
      >
        写完了
      </button>
      <button
        class="side-button deep-evidence-button"
        :disabled="operationBusy"
        @click="openDeepEvidence"
      >
        页边
      </button>
      <button class="quiet" @click="viewMode = 'poster'">发给他们</button>
    </view>
    <text v-if="operationError" class="operation-error">{{ operationError }}</text>

    <view v-if="sheetOpen" class="sheet" @click.self="sheetOpen = false">
      <view class="sheet-card">
        <text class="sheet-title">今晚想怎么写</text>
        <text class="sheet-hint">改了条件，这一页会重写。不是翻到下一页，是另起一顿。</text>

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

        <button class="go-button" :disabled="submitting" @click="rewriteTonight">
          {{ submitting ? '正在刷新候选…' : '按这个刷新候选' }}
        </button>
        <button class="quiet" @click="sheetOpen = false">还是现在这样</button>
      </view>
    </view>

    <view v-if="submitting" class="refresh-overlay">
      <view class="refresh-mark" aria-hidden="true">
        <view class="refresh-ring refresh-ring--outer" />
        <view class="refresh-ring refresh-ring--inner" />
        <view class="refresh-dot" />
      </view>
      <text class="refresh-title">正在重新选</text>
      <text class="refresh-copy">旧候选已放下，按新条件再筛一遍。</text>
    </view>
  </view>
</template>

<script setup lang="ts">
import { onLoad, onShareAppMessage, onUnload } from '@dcloudio/uni-app'
import { computed, ref } from 'vue'

import { createRecommendation, rerollRecommendation, submitRecommendationBehavior, submitRecommendationFeedback } from '@/api/recommendation'
import { ApiError, getUserFacingError } from '@/api/errors'
import { NavigationService, NavigationServiceError } from '@/services/navigation'
import { recommendationStore } from '@/stores/recommendation'
import { createUuidV4 } from '@/services/anonymous-user'
import type { BehaviorType, CategoryFilterCode, FeedbackResult, FlavorTag, Radius } from '@/types/recommendation'
import { formatEditionDate, pageIndex, pageLine, priceLine, walkingLine } from '@/utils/edition'
import { budgetOptions, categoryOptions, radiusOptions, type BudgetOption, type RadiusOption } from '@/utils/filters'
import { parseDislikes } from '@/utils/dislikes'

const feedbackOptions: Array<{ label: string; value: FeedbackResult }> = [
  { label: '不错', value: 'LIKE' },
  { label: '一般', value: 'NORMAL' },
  { label: '不喜欢', value: 'DISLIKE' },
]
const flavorOptions: Array<{ label: string; value: FlavorTag }> = [
  { label: '辣', value: 'SPICY' },
  { label: '甜', value: 'SWEET' },
  { label: '油', value: 'OILY' },
  { label: '咸', value: 'SALTY' },
  { label: '清淡', value: 'LIGHT' },
]

const recommendation = computed(() => recommendationStore.state.current)
const viewMode = ref<'page' | 'done' | 'poster'>('page')
const sheetOpen = ref(false)
const inkPlay = ref(true)
const rerolling = ref(false)
const navigating = ref(false)
const submitting = ref(false)
const submittingFeedback = ref(false)
const pendingFeedback = ref<FeedbackResult | null>(null)
const feedbackPanelOpen = ref(false)
const selectedFlavorTags = ref<FlavorTag[]>([])
const acceptedCurrent = ref(false)
const feedbackSubmitted = ref(false)
const skipReported = new Set<string>()
const operationError = ref('')
const operationTraceId = ref('')

const minDistance = ref<number | null>(null)
const radius = ref<Radius>(500)
const minBudget = ref<number | null>(20)
const maxBudget = ref<number | null>(40)
const category = ref<CategoryFilterCode>('MEAL')
const dislikesInput = ref('')

const categoryIndex = computed(() =>
  Math.max(0, categoryOptions.findIndex((option) => option.value === category.value)),
)
const selectedCategory = computed(() => categoryOptions[categoryIndex.value])
const kicker = computed(() => {
  const remaining = recommendation.value?.alternativesRemaining ?? 0
  return remaining <= 0 ? '06 / 写完了' : `0${pageIndex(remaining)} / 六页`
})
const dateLine = computed(() => formatEditionDate())
const headline = computed(() =>
  recommendation.value
    ? pageLine(recommendation.value.reasons, recommendation.value.risk.riskLevel)
    : '',
)
const metaLine = computed(() => {
  if (!recommendation.value) return ''
  const restaurant = recommendation.value.restaurant
  return `${walkingLine(restaurant.walkingMinutes)} · ${priceLine(restaurant.averagePrice)} · ${restaurant.address}`
})
const selectedFeedback = computed(() => recommendationStore.getCurrentFeedback())
const flavorPrompt = computed(() => {
  if (pendingFeedback.value === 'LIKE') return '合口味的地方，最多三个'
  if (pendingFeedback.value === 'DISLIKE') return '不合口味的地方，最多三个'
  return '它是什么味道，最多三个'
})
const operationBusy = computed(
  () => rerolling.value || navigating.value || submittingFeedback.value || submitting.value,
)

onShareAppMessage(() => ({
  title: recommendation.value
    ? `${recommendation.value.restaurant.name} · 别选了。`
    : 'ELMA 今天吃什么',
  path: '/pages/home/index',
}))

onLoad(() => {
  if (!recommendation.value) {
    uni.reLaunch({ url: '/pages/home/index' })
    return
  }
  hydrateFilters()
  const current = recommendation.value
  if (current) {
    acceptedCurrent.value = recommendationStore.hasBehaviorEvent(
      current.recommendationId,
      current.restaurant.id,
      'ACCEPT',
    )
    feedbackSubmitted.value = recommendationStore.getCurrentFeedback() !== null
  }
})

onUnload(() => {
  reportSkipBestEffort()
})

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

function resetOperationError() {
  operationError.value = ''
  operationTraceId.value = ''
}

function handleOperationError(error: unknown) {
  if (error instanceof ApiError && error.response?.code === 'RECOMMENDATION_NOT_FOUND') {
    recommendationStore.clear()
    uni.showToast({ title: error.response.message, icon: 'none' })
    uni.reLaunch({ url: '/pages/home/index' })
    return
  }
  operationError.value =
    error instanceof NavigationServiceError ? error.message : getUserFacingError(error)
  operationTraceId.value = error instanceof ApiError ? error.response?.traceId ?? '' : ''
}

async function rewriteTonight() {
  const current = recommendation.value
  const last = recommendationStore.state.lastRequest
  if (!current || !last || submitting.value) return
  let dislikes: string[]
  try {
    dislikes = parseDislikes(dislikesInput.value)
  } catch (error) {
    operationError.value = error instanceof Error ? error.message : '不想吃的内容格式不正确'
    return
  }
  resetOperationError()
  submitting.value = true
  try {
    reportSkipBestEffort()
    const conditionsChanged = minDistance.value !== (last.minDistance ?? null)
      || radius.value !== last.radius
      || minBudget.value !== (last.minBudget ?? null)
      || maxBudget.value !== last.maxBudget
      || category.value !== last.category
      || JSON.stringify(dislikes) !== JSON.stringify(last.dislikes)
    const request = {
      ...last,
      minDistance: minDistance.value,
      radius: radius.value,
      minBudget: minBudget.value,
      maxBudget: maxBudget.value,
      category: category.value,
      dislikes,
      excludeRestaurantId: conditionsChanged ? current.restaurant.id : null,
    }
    let response = await createRecommendation(request)
    if (conditionsChanged && response.restaurant.id === current.restaurant.id
      && response.alternativesRemaining > 0) {
      response = await rerollRecommendation(response.recommendationId)
    }
    recommendationStore.setCurrent(response, request)
    sheetOpen.value = false
    viewMode.value = 'page'
    resetInteractionState()
    inkPlay.value = false
    setTimeout(() => {
      inkPlay.value = true
    }, 20)
    if (conditionsChanged && response.restaurant.id === current.restaurant.id) {
      uni.showToast({ title: '新条件下暂时只有这家合适', icon: 'none' })
    }
  } catch (error) {
    handleOperationError(error)
  } finally {
    submitting.value = false
  }
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
    inkPlay.value = false
    setTimeout(() => {
      inkPlay.value = true
    }, 20)
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
  viewMode.value = 'done'
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
    uni.showToast({ title: '最多三个', icon: 'none' })
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
  return recommendationStore.getBehaviorEventId(
    recommendationId,
    restaurantId,
    type,
    createUuidV4,
  )
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
    // best-effort
  }
}

function reportSkipBestEffort() {
  const current = recommendation.value
  const key = current ? `${current.recommendationId}:${current.restaurant.id}` : ''
  if (!current || acceptedCurrent.value || feedbackSubmitted.value || selectedFeedback.value
    || skipReported.has(key)) return
  skipReported.add(key)
  void reportBehavior('SKIP', current.recommendationId, current.restaurant.id)
}
</script>

<style scoped>
.result-page {
  position: relative;
  display: flex;
  box-sizing: border-box;
  min-height: 100vh;
  flex-direction: column;
  padding: calc(var(--status-bar-height) + var(--elma-custom-nav-offset)) 0 24rpx;
  background: #f7f7f5;
  color: #171717;
}

.nav {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 36rpx 8rpx;
  min-height: 48rpx;
}
.brand {
  font-size: 22rpx;
  letter-spacing: 6rpx;
  color: #686865;
}
.text-btn {
  margin: 0;
  padding: 8rpx 0;
  background: transparent;
  color: #5d6d5a;
  font-size: 26rpx;
}

.leaf {
  display: flex;
  flex: 1;
  flex-direction: column;
  padding: 16rpx 48rpx 12rpx;
}
.kicker {
  color: #5d6d5a;
  font-size: 22rpx;
  letter-spacing: 6rpx;
}
.date {
  margin-top: 10rpx;
  color: #686865;
  font-size: 24rpx;
}
.name {
  margin-top: 48rpx;
  font-size: 72rpx;
  font-weight: 500;
  line-height: 1.12;
}
.chorus {
  margin-top: 20rpx;
  color: #5d6d5a;
  font-size: 36rpx;
}
.line {
  margin-top: 40rpx;
  font-size: 28rpx;
  line-height: 1.75;
  color: #3f3f3b;
}
.meta,
.search-notice-message,
.sealed,
.poster-hint {
  display: block;
  margin-top: 20rpx;
  color: #686865;
  font-size: 24rpx;
  line-height: 1.7;
}
.sealed { color: #5d6d5a; }

.wave {
  position: relative;
  height: 100rpx;
  margin-top: auto;
  overflow: hidden;
}
.wave-arc {
  position: absolute;
  left: -18%;
  width: 140%;
  height: 180rpx;
  border: 2rpx solid #5d6d5a;
  border-radius: 50%;
  opacity: 0.4;
  animation: drift 16s ease-in-out infinite alternate;
}
.wave-arc--b {
  top: 20rpx;
  opacity: 0.22;
  animation-duration: 21s;
}
@keyframes drift {
  from { transform: translateX(0); }
  to { transform: translateX(-24rpx); }
}

.dock {
  display: flex;
  flex-wrap: wrap;
  gap: 16rpx;
  padding: 12rpx 48rpx 12rpx;
}
.go-button,
.accept-button {
  width: 100%;
  margin: 0;
  padding: 28rpx 32rpx;
  border-radius: 20rpx;
  background: #5d6d5a;
  color: #f7f7f5;
  font-size: 30rpx;
  text-align: left;
}
.side-button,
.reroll-button,
.deep-evidence-button {
  width: calc(50% - 8rpx);
  margin: 0;
  padding: 22rpx 12rpx;
  border: 2rpx solid rgba(23, 23, 23, 0.12);
  border-radius: 20rpx;
  background: transparent;
  color: #171717;
  font-size: 26rpx;
}
.quiet {
  width: 100%;
  margin: 0;
  padding: 8rpx;
  background: transparent;
  color: #686865;
  font-size: 24rpx;
}
.accept-button--disabled,
.reroll-button--disabled {
  opacity: 0.55;
}
.operation-error {
  width: 100%;
  color: #8a4b3a;
  font-size: 24rpx;
  text-align: center;
}

.feedback-section { margin-top: 36rpx; }
.feedback-title {
  display: block;
  color: #686865;
  font-size: 24rpx;
  letter-spacing: 2rpx;
}
.feedback-row {
  display: flex;
  gap: 16rpx;
  margin-top: 20rpx;
}
.feedback-button {
  flex: 1;
  margin: 0;
  padding: 18rpx 0;
  border: 2rpx solid rgba(23, 23, 23, 0.12);
  border-radius: 16rpx;
  background: transparent;
  font-size: 24rpx;
}
.feedback-button--active {
  background: #171717;
  border-color: #171717;
  color: #f7f7f5;
}
.flavor-panel { margin-top: 24rpx; }
.flavor-title { display: block; font-size: 24rpx; color: #3f3f3b; }
.flavor-options {
  display: flex;
  flex-wrap: wrap;
  gap: 12rpx;
  margin-top: 16rpx;
}
.flavor-option {
  margin: 0;
  padding: 12rpx 22rpx;
  border: 2rpx solid rgba(23, 23, 23, 0.12);
  border-radius: 999rpx;
  background: transparent;
  font-size: 24rpx;
}
.flavor-option--active {
  border-color: #5d6d5a;
  background: rgba(93, 109, 90, 0.12);
  color: #5d6d5a;
}
.flavor-submit {
  width: 100%;
  margin-top: 24rpx;
  padding: 22rpx;
  border-radius: 20rpx;
  background: #5d6d5a;
  color: #f7f7f5;
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
  max-height: 88vh;
  padding: 48rpx 44rpx 64rpx;
  background: #f7f7f5;
  overflow: auto;
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
  flex: 1;
  margin: 0;
  padding: 20rpx 0;
  border: 2rpx solid rgba(23, 23, 23, 0.12);
  border-radius: 16rpx;
  background: transparent;
  font-size: 24rpx;
}
.chip--active {
  background: #5d6d5a;
  border-color: #5d6d5a;
  color: #f7f7f5;
}
.prefs {
  display: flex;
  flex-direction: column;
  gap: 36rpx;
  margin-top: 44rpx;
}
.field { flex: 1; min-width: 0; }
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

.ink .name {
  animation: rise 0.9s cubic-bezier(0.22, 1, 0.36, 1) both;
}

.refresh-overlay {
  position: fixed;
  inset: 0;
  z-index: 40;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-direction: column;
  padding: 48rpx;
  background: rgba(247, 247, 245, 0.96);
}
.refresh-mark {
  position: relative;
  width: 150rpx;
  height: 150rpx;
}
.refresh-ring {
  position: absolute;
  border: 2rpx solid #5d6d5a;
  border-radius: 50%;
}
.refresh-ring--outer {
  inset: 0;
  border-right-color: transparent;
  animation: refresh-spin 1.4s linear infinite;
}
.refresh-ring--inner {
  inset: 28rpx;
  border-top-color: transparent;
  opacity: 0.48;
  animation: refresh-spin 1s linear infinite reverse;
}
.refresh-dot {
  position: absolute;
  top: 67rpx;
  left: 67rpx;
  width: 16rpx;
  height: 16rpx;
  border-radius: 50%;
  background: #5d6d5a;
  animation: refresh-pulse 0.9s ease-in-out infinite alternate;
}
.refresh-title {
  margin-top: 38rpx;
  font-size: 34rpx;
  font-weight: 500;
  letter-spacing: 4rpx;
}
.refresh-copy {
  margin-top: 18rpx;
  color: #686865;
  font-size: 24rpx;
  text-align: center;
}
@keyframes refresh-spin {
  to { transform: rotate(360deg); }
}
@keyframes refresh-pulse {
  from { opacity: 0.35; transform: scale(0.75); }
  to { opacity: 1; transform: scale(1.15); }
}
@keyframes rise {
  from { opacity: 0; transform: translateY(16rpx); }
  to { opacity: 1; transform: translateY(0); }
}
</style>
