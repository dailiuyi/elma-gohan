import { apiRequest } from '@/api/client'
import type {
  CreateRecommendationRequest,
  DeepEvidenceResponse,
  BehaviorResponse,
  BehaviorType,
  FeedbackResponse,
  FeedbackResult,
  FlavorTag,
  RecommendationResponse,
} from '@/types/recommendation'

export function createRecommendation(
  request: CreateRecommendationRequest,
): Promise<RecommendationResponse> {
  return apiRequest<RecommendationResponse>({
    path: '/recommendations',
    method: 'POST',
    data: request,
  })
}

export function rerollRecommendation(recommendationId: string): Promise<RecommendationResponse> {
  return apiRequest<RecommendationResponse>({
    path: `/recommendations/${encodeURIComponent(recommendationId)}/reroll`,
    method: 'POST',
  })
}

export function submitRecommendationFeedback(
  recommendationId: string,
  result: FeedbackResult,
  flavorTags: FlavorTag[] = [],
): Promise<FeedbackResponse> {
  return apiRequest<FeedbackResponse>({
    path: `/recommendations/${encodeURIComponent(recommendationId)}/feedback`,
    method: 'POST',
    data: flavorTags.length ? { result, flavorTags } : { result },
  })
}

export function submitRecommendationBehavior(
  recommendationId: string,
  eventId: string,
  restaurantId: string,
  type: BehaviorType,
): Promise<BehaviorResponse> {
  return apiRequest<BehaviorResponse>({
    path: `/recommendations/${encodeURIComponent(recommendationId)}/behaviors`,
    method: 'POST',
    data: { eventId, restaurantId, type },
  })
}

export function deepenRecommendationEvidence(
  recommendationId: string,
): Promise<DeepEvidenceResponse> {
  return apiRequest<DeepEvidenceResponse>({
    path: `/recommendations/${encodeURIComponent(recommendationId)}/deep-evidence`,
    method: 'POST',
  })
}
