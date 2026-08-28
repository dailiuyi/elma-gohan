import { reactive, readonly } from 'vue'

import type {
  BehaviorType,
  CreateRecommendationRequest,
  FeedbackResponse,
  FeedbackResult,
  RecommendationResponse,
} from '@/types/recommendation'
import { EDITION_STORAGE_KEY, editionKey, type EditionSnapshot } from '@/utils/edition'

interface RecommendationState {
  current: RecommendationResponse | null
  lastRequest: CreateRecommendationRequest | null
  feedbackByRestaurantId: Record<string, FeedbackResponse>
  behaviorEventIds: Record<string, string>
  editionKey: string
}

const state = reactive<RecommendationState>({
  current: null,
  lastRequest: null,
  feedbackByRestaurantId: {},
  behaviorEventIds: {},
  editionKey: '',
})

function canUseStorage() {
  return typeof uni !== 'undefined' && typeof uni.setStorageSync === 'function'
}

function persist() {
  if (!canUseStorage()) return
  if (!state.current || !state.lastRequest || !state.editionKey) {
    try {
      uni.removeStorageSync(EDITION_STORAGE_KEY)
    } catch {
      // ignore
    }
    return
  }

  const snapshot: EditionSnapshot = {
    key: state.editionKey,
    slot: state.editionKey.split(':')[1] as EditionSnapshot['slot'],
    current: state.current,
    lastRequest: state.lastRequest,
    feedbackByRestaurantId: { ...state.feedbackByRestaurantId },
    behaviorEventIds: { ...state.behaviorEventIds },
  }
  try {
    uni.setStorageSync(EDITION_STORAGE_KEY, snapshot)
  } catch {
    // ignore quota
  }
}

export const recommendationStore = {
  state: readonly(state),

  hydrate(): boolean {
    if (!canUseStorage()) return false
    try {
      const stored = uni.getStorageSync(EDITION_STORAGE_KEY) as EditionSnapshot | undefined
      if (!stored?.current || !stored.lastRequest || stored.key !== editionKey()) {
        return false
      }
      state.current = stored.current
      state.lastRequest = stored.lastRequest
      state.feedbackByRestaurantId = stored.feedbackByRestaurantId ?? {}
      state.behaviorEventIds = stored.behaviorEventIds ?? {}
      state.editionKey = stored.key
      return true
    } catch {
      return false
    }
  },

  setCurrent(response: RecommendationResponse, request: CreateRecommendationRequest) {
    state.current = response
    state.lastRequest = request
    state.feedbackByRestaurantId = {}
    state.behaviorEventIds = {}
    state.editionKey = editionKey()
    persist()
  },

  replaceCurrent(response: RecommendationResponse) {
    state.current = response
    persist()
  },

  getCurrentFeedback(): FeedbackResult | null {
    const restaurantId = state.current?.restaurant.id
    return restaurantId ? state.feedbackByRestaurantId[restaurantId]?.result ?? null : null
  },

  recordFeedback(response: FeedbackResponse) {
    state.feedbackByRestaurantId[response.restaurantId] = response
    persist()
  },

  getBehaviorEventId(
    recommendationId: string,
    restaurantId: string,
    type: BehaviorType,
    create: () => string,
  ): string {
    const key = `${recommendationId}:${restaurantId}:${type}`
    const existing = state.behaviorEventIds[key]
    if (existing) return existing

    const eventId = create()
    state.behaviorEventIds[key] = eventId
    persist()
    return eventId
  },

  hasBehaviorEvent(
    recommendationId: string,
    restaurantId: string,
    type: BehaviorType,
  ): boolean {
    return Boolean(state.behaviorEventIds[`${recommendationId}:${restaurantId}:${type}`])
  },

  clear() {
    state.current = null
    state.lastRequest = null
    state.feedbackByRestaurantId = {}
    state.behaviorEventIds = {}
    state.editionKey = ''
    persist()
  },
}
