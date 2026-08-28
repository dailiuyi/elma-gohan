<template>
  <view class="deep-page">
    <view class="deep-nav">
      <button class="back-button" aria-label="返回推荐" @click="goBack">←</button>
      <text class="deep-brand">ELMA</text>
      <view class="nav-placeholder" />
    </view>

    <view v-if="loading" class="loading-card">
      <text class="loading-title">再看一点……</text>
      <view v-for="source in pendingSources" :key="source" class="loading-row">
        <text class="loading-dot">·</text>
        <text>{{ source }} · 查询中</text>
      </view>
    </view>

    <view v-else-if="errorMessage" class="error-card">
      <text class="error-title">这次没有查完</text>
      <text class="error-message">{{ errorMessage }}</text>
      <button class="retry-button" @click="loadEvidence">重新深挖</button>
    </view>

    <template v-else-if="result">
      <view class="deep-heading">
        <text class="deep-kicker">页边的注解</text>
        <text class="restaurant-name">{{ result.restaurantName }}</text>
        <text class="weak-evidence-note">公开索引里的标题和摘要。不是完整评价。</text>
      </view>

      <view class="summary-card">
        <view class="summary-row">
          <text>综合风险</text>
          <text class="summary-value">{{ riskLabel }}</text>
        </view>
        <view class="summary-row">
          <text>数据可信度</text>
          <text class="summary-value">{{ confidencePercent }}%</text>
        </view>
        <view class="summary-row">
          <text>多来源一致度</text>
          <text class="summary-value">{{ consistencyLabel }}</text>
        </view>
        <text class="consistency-reason">{{ result.consistency.reason }}</text>
      </view>

      <view class="source-card">
        <text class="section-title">来源覆盖</text>
        <view v-for="source in result.sourceCoverage" :key="source.source" class="source-row">
          <text>{{ sourceLabel(source.source) }}</text>
          <text :class="`source-status source-status--${source.status.toLowerCase()}`">
            {{ sourceStatus(source.status, source.resultCount) }}
          </text>
        </view>
      </view>

      <view class="signals-card">
        <text class="section-title">最近公开结果主要提到</text>
        <view v-if="!hasSignals" class="empty-signals">暂未形成明确线索</view>
        <text v-for="item in result.signals.positive" :key="`p-${item}`" class="signal signal--positive">
          + {{ item }}
        </text>
        <text v-for="item in result.signals.negative" :key="`n-${item}`" class="signal signal--negative">
          - {{ item }}
        </text>
        <text v-for="item in result.signals.cautions" :key="`c-${item}`" class="signal signal--caution">
          △ {{ item }}
        </text>
      </view>

      <view class="reasons-card">
        <text class="section-title">值得注意</text>
        <text v-for="reason in displayedReasons" :key="reason" class="reason">{{ reason }}</text>
      </view>

      <view v-if="result.links.length" class="links-card">
        <text class="section-title">公开结果</text>
        <button v-for="link in result.links" :key="link.url" class="link-row" @click="openLink(link.url)">
          <view class="link-copy">
            <text class="link-source">{{ sourceLabel(link.source) }}</text>
            <text class="link-title">{{ link.title }}</text>
            <text v-if="link.publishedAt" class="link-date">{{ formatDate(link.publishedAt) }}</text>
          </view>
          <text class="copy-action">复制链接</text>
        </button>
      </view>

      <button v-if="allWebSourcesUnavailable" class="retry-button" @click="loadEvidence">
        重新深挖
      </button>

      <button class="accept-button" @click="goBack">回去</button>
    </template>
  </view>
</template>

<script setup lang="ts">
import { onLoad } from '@dcloudio/uni-app'
import { computed, ref } from 'vue'

import { deepenRecommendationEvidence } from '@/api/recommendation'
import { getUserFacingError } from '@/api/errors'
import type {
  DeepConsistencyLevel,
  DeepEvidenceResponse,
  DeepEvidenceSource,
  EvidenceStatus,
  RiskLevel,
} from '@/types/recommendation'

const pendingSources = ['高德地图', '百度地图', 'B站公开结果', '小红书公开结果', '大众点评公开结果']
const riskLabels: Record<RiskLevel, string> = {
  LOW: '较低',
  MEDIUM_LOW: '中低',
  MEDIUM: '中等',
  HIGH: '较高',
}
const consistencyLabels: Record<DeepConsistencyLevel, string> = {
  HIGH: '较高',
  MEDIUM: '一般',
  LOW: '较低',
  UNKNOWN: '暂不明确',
}

const recommendationId = ref('')
const expectedRestaurantId = ref('')
const loading = ref(true)
const errorMessage = ref('')
const result = ref<DeepEvidenceResponse | null>(null)

const riskLabel = computed(() => result.value ? riskLabels[result.value.deepRisk.riskLevel] : '')
const confidencePercent = computed(() => Math.round((result.value?.deepRisk.confidence ?? 0) * 100))
const consistencyLabel = computed(() =>
  result.value ? consistencyLabels[result.value.consistency.level] : '',
)
const hasSignals = computed(() => {
  const signals = result.value?.signals
  return Boolean(signals && (signals.positive.length || signals.negative.length || signals.cautions.length))
})
const allWebSourcesUnavailable = computed(() => {
  if (!result.value) return false
  const webSources = new Set<DeepEvidenceSource>(['BILIBILI', 'XIAOHONGSHU', 'DIANPING'])
  const sources = result.value.sourceCoverage.filter((item) => webSources.has(item.source))
  return sources.length === 3 && sources.every((item) => item.status === 'UNAVAILABLE')
})
const displayedReasons = computed(() => {
  if (!result.value) return []
  const reasons = allWebSourcesUnavailable.value
    ? [...result.value.baseRisk.reasons, ...result.value.deepRisk.reasons]
    : result.value.deepRisk.reasons
  return [...new Set(reasons)].slice(0, 5)
})

onLoad((query) => {
  recommendationId.value = typeof query?.recommendationId === 'string' ? query.recommendationId : ''
  expectedRestaurantId.value = typeof query?.restaurantId === 'string' ? query.restaurantId : ''
  if (!recommendationId.value || !expectedRestaurantId.value) {
    loading.value = false
    errorMessage.value = '推荐信息已经失效，请返回后重新选择'
    return
  }
  void loadEvidence()
})

async function loadEvidence() {
  loading.value = true
  errorMessage.value = ''
  try {
    const response = await deepenRecommendationEvidence(recommendationId.value)
    if (response.restaurantId !== expectedRestaurantId.value) {
      errorMessage.value = '当前推荐已经变化，请返回查看最新餐厅'
      result.value = null
      return
    }
    result.value = response
  } catch (error) {
    result.value = null
    errorMessage.value = getUserFacingError(error)
  } finally {
    loading.value = false
  }
}

function sourceLabel(source: DeepEvidenceSource) {
  return {
    AMAP: '高德', BAIDU: '百度', BILIBILI: 'B站',
    XIAOHONGSHU: '小红书', DIANPING: '大众点评',
  }[source]
}

function sourceStatus(status: EvidenceStatus, count: number | null) {
  if (status === 'UNAVAILABLE') return '暂不可用'
  if (status === 'NO_DATA') return '暂未找到同店公开线索'
  return count === null ? '已使用' : `找到 ${count} 条`
}

function formatDate(value: string) {
  const date = new Date(value)
  return Number.isNaN(date.getTime())
    ? ''
    : `${date.getFullYear()}.${date.getMonth() + 1}.${date.getDate()}`
}

function openLink(url: string) {
  if (typeof uni.getSystemInfoSync === 'function'
    && uni.getSystemInfoSync().uniPlatform === 'web'
    && typeof window !== 'undefined') {
    window.open(url, '_blank', 'noopener,noreferrer')
    return
  }
  uni.setClipboardData({
    data: url,
    success: () => uni.showToast({ title: '链接已复制，请在浏览器打开', icon: 'none' }),
  })
}

function goBack() {
  uni.navigateBack({ delta: 1 })
}
</script>

<style scoped>
.deep-page {
  box-sizing: border-box;
  min-height: 100vh;
  padding: calc(var(--status-bar-height) + var(--elma-custom-nav-offset)) 40rpx 60rpx;
  background: #f7f7f5;
  color: #171717;
}

.deep-nav,
.summary-row,
.source-row,
.link-row {
  display: flex;
  align-items: center;
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
  font-size: 28rpx;
  line-height: 52rpx;
}

.deep-brand {
  font-size: 18rpx;
  font-weight: 600;
  letter-spacing: 3rpx;
}

.nav-placeholder { width: 56rpx; }

.loading-card,
.error-card,
.summary-card,
.source-card,
.signals-card,
.reasons-card,
.links-card {
  margin-top: 28rpx;
  padding: 28rpx;
  border: 2rpx solid #e0e3ed;
  border-radius: 14rpx;
  background: #ffffff;
}

.loading-card,
.error-card { margin-top: 80rpx; }
.loading-title,
.error-title,
.section-title { display: block; font-size: 25rpx; font-weight: 700; }
.loading-row { margin-top: 24rpx; color: #66708a; font-size: 21rpx; }
.loading-dot { margin-right: 14rpx; color: #5b61d6; }
.error-message { display: block; margin-top: 18rpx; color: #a6455a; font-size: 21rpx; }

.deep-heading { margin-top: 48rpx; }
.deep-kicker { display: block; color: #5d6d5a; font-size: 19rpx; letter-spacing: 4rpx; }
.restaurant-name { display: block; margin-top: 14rpx; font-size: 48rpx; font-weight: 700; }
.weak-evidence-note { display: block; margin-top: 12rpx; color: #7a8298; font-size: 18rpx; }

.summary-row,
.source-row { padding: 15rpx 0; border-bottom: 2rpx solid #eff0f5; font-size: 22rpx; }
.summary-value { color: #343a9f; font-weight: 700; }
.consistency-reason { display: block; margin-top: 18rpx; color: #59627c; font-size: 20rpx; line-height: 1.6; }

.source-card .section-title,
.signals-card .section-title,
.reasons-card .section-title,
.links-card .section-title { margin-bottom: 14rpx; }
.source-status { color: #2f7d6a; font-size: 19rpx; }
.source-status--no_data { color: #8a7343; }
.source-status--unavailable { color: #8a92a8; }

.signal,
.reason { display: block; margin-top: 14rpx; font-size: 21rpx; line-height: 1.55; }
.signal--positive { color: #2f7d6a; }
.signal--negative { color: #a6455a; }
.signal--caution { color: #8a6539; }
.empty-signals { color: #8a92a8; font-size: 20rpx; }

.link-row { width: 100%; margin: 0; padding: 20rpx 0; border-bottom: 2rpx solid #eff0f5; background: transparent; text-align: left; }
.link-row::after { border: 0; }
.link-copy { min-width: 0; flex: 1; }
.link-source,
.link-title,
.link-date { display: block; }
.link-source { color: #5b61d6; font-size: 17rpx; }
.link-title { margin-top: 7rpx; color: #303851; font-size: 20rpx; line-height: 1.45; }
.link-date { margin-top: 5rpx; color: #8a92a8; font-size: 16rpx; }
.copy-action { margin-left: 20rpx; color: #5b61d6; font-size: 17rpx; }

.retry-button,
.accept-button { width: 100%; margin: 28rpx 0 0; padding: 24rpx; border-radius: 20rpx; background: #5d6d5a; color: #f7f7f5; font-size: 24rpx; }
.accept-button { margin-top: 34rpx; }
</style>
