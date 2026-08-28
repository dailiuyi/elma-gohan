import { beforeEach, describe, expect, it, vi } from 'vitest'

import { apiRequest } from '@/api/client'
import {
  createRecommendation,
  deleteMyUserData,
  deepenRecommendationEvidence,
  rerollRecommendation,
  submitRecommendationBehavior,
  submitRecommendationFeedback,
} from '@/api/recommendation'
import type {
  CreateRecommendationRequest,
  FeedbackResponse,
  RecommendationResponse,
} from '@/types/recommendation'

vi.mock('@/api/client', () => ({ apiRequest: vi.fn() }))

const request: CreateRecommendationRequest = {
  latitude: 28.2282,
  longitude: 112.9388,
  radius: 1000,
  maxBudget: 40,
  category: 'ANY',
  dislikes: ['香菜'],
}

describe('recommendation API', () => {
  beforeEach(() => vi.mocked(apiRequest).mockReset())

  it('posts the contract request to /recommendations', async () => {
    const response = { recommendationId: 'recommendation-id' } as RecommendationResponse
    vi.mocked(apiRequest).mockResolvedValue(response)

    await expect(createRecommendation(request)).resolves.toBe(response)
    expect(apiRequest).toHaveBeenCalledWith({
      path: '/recommendations',
      method: 'POST',
      data: request,
    })
  })

  it('rerolls only through the recommendation session endpoint', async () => {
    const response = { recommendationId: 'recommendation-id' } as RecommendationResponse
    vi.mocked(apiRequest).mockResolvedValue(response)

    await expect(rerollRecommendation('session/id')).resolves.toBe(response)
    expect(apiRequest).toHaveBeenCalledWith({
      path: '/recommendations/session%2Fid/reroll',
      method: 'POST',
    })
  })

  it('loads deep evidence only through the on-demand session endpoint', async () => {
    const response = { recommendationId: 'recommendation-id' }
    vi.mocked(apiRequest).mockResolvedValue(response)

    await expect(deepenRecommendationEvidence('session/id')).resolves.toBe(response)
    expect(apiRequest).toHaveBeenCalledWith({
      path: '/recommendations/session%2Fid/deep-evidence',
      method: 'POST',
    })
  })

  it('submits only the selected feedback result', async () => {
    const response = { feedbackId: 'feedback-id' } as FeedbackResponse
    vi.mocked(apiRequest).mockResolvedValue(response)

    await expect(submitRecommendationFeedback('session-id', 'DISLIKE')).resolves.toBe(response)
    expect(apiRequest).toHaveBeenCalledWith({
      path: '/recommendations/session-id/feedback',
      method: 'POST',
      data: { result: 'DISLIKE' },
    })
  })

  it('submits optional flavor tags with feedback', async () => {
    const response = { feedbackId: 'feedback-id' } as FeedbackResponse
    vi.mocked(apiRequest).mockResolvedValue(response)
    await submitRecommendationFeedback('session-id', 'DISLIKE', ['SPICY', 'OILY'])
    expect(apiRequest).toHaveBeenCalledWith({
      path: '/recommendations/session-id/feedback',
      method: 'POST',
      data: { result: 'DISLIKE', flavorTags: ['SPICY', 'OILY'] },
    })
  })

  it('submits an idempotent client behavior event', async () => {
    vi.mocked(apiRequest).mockResolvedValue({ eventId: 'event-id' })
    await submitRecommendationBehavior('session-id', 'event-id', 'restaurant-a', 'ACCEPT')
    expect(apiRequest).toHaveBeenCalledWith({
      path: '/recommendations/session-id/behaviors',
      method: 'POST',
      data: { eventId: 'event-id', restaurantId: 'restaurant-a', type: 'ACCEPT' },
    })
  })

  it('deletes all data for the current anonymous user', async () => {
    vi.mocked(apiRequest).mockResolvedValue(undefined)

    await expect(deleteMyUserData()).resolves.toBeUndefined()
    expect(apiRequest).toHaveBeenCalledWith({
      path: '/users/me/data',
      method: 'DELETE',
    })
  })
})
