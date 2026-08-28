import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { recommendationStore } from '@/stores/recommendation'
import type {
  CreateRecommendationRequest,
  FeedbackResponse,
  RecommendationResponse,
} from '@/types/recommendation'

const request: CreateRecommendationRequest = {
  latitude: 28.2282,
  longitude: 112.9388,
  radius: 1000,
  maxBudget: null,
  category: 'ANY',
  dislikes: [],
}

const response = {
  recommendationId: '3c34f61d-cb41-4850-aafe-1505f3312f06',
  restaurant: { id: 'restaurant-a', name: '老街牛肉粉' },
} as RecommendationResponse

describe('recommendation store', () => {
  const storage = new Map<string, unknown>()

  beforeEach(() => {
    storage.clear()
    vi.stubGlobal('uni', {
      getStorageSync: vi.fn((key: string) => storage.get(key)),
      setStorageSync: vi.fn((key: string, value: unknown) => storage.set(key, value)),
      removeStorageSync: vi.fn((key: string) => storage.delete(key)),
    })
  })

  afterEach(() => {
    recommendationStore.clear()
    vi.unstubAllGlobals()
  })

  it('keeps the real response and its originating request', () => {
    recommendationStore.setCurrent(response, request)

    expect(recommendationStore.state.current).toEqual(response)
    expect(recommendationStore.state.lastRequest).toEqual(request)
  })

  it('clears stale recommendation state', () => {
    recommendationStore.setCurrent(response, request)
    recommendationStore.clear()

    expect(recommendationStore.state.current).toBeNull()
    expect(recommendationStore.state.lastRequest).toBeNull()
  })

  it('replaces the current restaurant only with the server reroll response', () => {
    const rerolled = {
      ...response,
      restaurant: { ...response.restaurant, id: 'restaurant-b', name: '南门盖饭' },
      alternativesRemaining: 1,
    }

    recommendationStore.setCurrent(response, request)
    recommendationStore.replaceCurrent(rerolled)

    expect(recommendationStore.state.current).toEqual(rerolled)
    expect(recommendationStore.state.lastRequest).toEqual(request)
  })

  it('records one submitted state per restaurant', () => {
    const feedback: FeedbackResponse = {
      feedbackId: 'feedback-id',
      recommendationId: response.recommendationId,
      restaurantId: 'restaurant-a',
      result: 'LIKE',
      recordedAt: '2026-08-19T09:30:00+08:00',
    }

    recommendationStore.setCurrent(response, request)
    expect(recommendationStore.getCurrentFeedback()).toBeNull()
    recommendationStore.recordFeedback(feedback)
    expect(recommendationStore.getCurrentFeedback()).toBe('LIKE')
  })

  it('starts feedback tracking fresh for a new recommendation session', () => {
    recommendationStore.setCurrent(response, request)
    recommendationStore.recordFeedback({
      feedbackId: 'feedback-id',
      recommendationId: response.recommendationId,
      restaurantId: 'restaurant-a',
      result: 'LIKE',
      recordedAt: '2026-08-19T09:30:00+08:00',
    })

    recommendationStore.setCurrent(
      { ...response, recommendationId: 'new-recommendation-id' },
      request,
    )

    expect(recommendationStore.getCurrentFeedback()).toBeNull()
  })

  it('reuses and persists the same behavior event ID for network retries', () => {
    recommendationStore.setCurrent(response, request)
    const create = vi.fn(() => 'event-id')

    expect(recommendationStore.getBehaviorEventId(
      response.recommendationId,
      response.restaurant.id,
      'ACCEPT',
      create,
    )).toBe('event-id')
    expect(recommendationStore.getBehaviorEventId(
      response.recommendationId,
      response.restaurant.id,
      'ACCEPT',
      create,
    )).toBe('event-id')

    expect(create).toHaveBeenCalledOnce()
    expect(recommendationStore.hasBehaviorEvent(
      response.recommendationId,
      response.restaurant.id,
      'ACCEPT',
    )).toBe(true)
    expect([...storage.values()].some((value) =>
      JSON.stringify(value).includes('event-id'))).toBe(true)
  })
})
