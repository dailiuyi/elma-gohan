export type Radius = 500 | 1000 | 2000 | 3000
export type CategoryFilterCode =
  | 'MEAL'
  | 'CHINESE'
  | 'HOT_POT'
  | 'BARBECUE'
  | 'NOODLES'
  | 'FAST_FOOD'
  | 'WESTERN'
  | 'JAPANESE_KOREAN'
  | 'DESSERT_DRINK'
  | 'ANY'
export type FeedbackResult = 'LIKE' | 'NORMAL' | 'DISLIKE'
export type RiskLevel = 'LOW' | 'MEDIUM_LOW' | 'MEDIUM' | 'HIGH'
export type BusinessStatus = 'OPEN' | 'CLOSED' | 'UNKNOWN'
export type EvidenceStatus = 'AVAILABLE' | 'NO_DATA' | 'UNAVAILABLE'
export type EntityMatchStatus = 'MATCHED' | 'AMBIGUOUS' | 'NO_MATCH' | 'UNAVAILABLE'
export type ConsistencyLevel = 'CONSISTENT' | 'SLIGHT_DIFFERENCE' | 'CONFLICT' | 'UNKNOWN'
export type DeepEvidenceSource = 'AMAP' | 'BAIDU' | 'BILIBILI' | 'XIAOHONGSHU' | 'DIANPING'
export type DeepConsistencyLevel = 'HIGH' | 'MEDIUM' | 'LOW' | 'UNKNOWN'
export type DeepCacheStatus = 'HIT' | 'PARTIAL_HIT' | 'MISS'

export interface CreateRecommendationRequest {
  latitude: number
  longitude: number
  radius: Radius
  maxBudget: number | null
  category: CategoryFilterCode
  dislikes: string[]
}

export interface SubmitFeedbackRequest {
  result: FeedbackResult
}

export interface FeedbackResponse {
  feedbackId: string
  recommendationId: string
  restaurantId: string
  result: FeedbackResult
  recordedAt: string
}

export interface RecommendationResponse {
  recommendationId: string
  restaurant: RestaurantSummary
  risk: RiskAssessment
  evidenceSummary?: EvidenceSummary | null
  reasons: string[]
  alternativesRemaining: number
}

export interface EvidenceSummary {
  matchStatus: EntityMatchStatus
  matchConfidence: number | null
  consistency: ConsistencyLevel
  ratingDifference: number | null
  reason: string
  amap: EvidenceSourceSummary
  baidu: EvidenceSourceSummary
}

export interface EvidenceSourceSummary {
  status: EvidenceStatus
  rating: number | null
  tasteRating: number | null
  serviceRating: number | null
  environmentRating: number | null
  averagePrice: number | null
  commentCount: number | null
}

export interface RestaurantSummary {
  id: string
  name: string
  latitude: number
  longitude: number
  address: string
  category: RestaurantCategory
  distanceMeters: number
  walkingMinutes: number
  averagePrice: number | null
  rating: number | null
  businessStatus: BusinessStatus
}

export interface RestaurantCategory {
  code: string
  label: string
}

export interface RiskAssessment {
  riskScore: number
  riskLevel: RiskLevel
  confidence: number
  reasons: string[]
  algorithmVersion: string
}

export interface DeepEvidenceResponse {
  recommendationId: string
  restaurantId: string
  restaurantName: string
  baseRisk: RiskAssessment
  deepRisk: RiskAssessment
  structuredEvidence: EvidenceSummary | null
  sourceCoverage: DeepSourceCoverage[]
  signals: DeepSignalSummary
  consistency: DeepConsistency
  links: DeepEvidenceLink[]
  cacheStatus: DeepCacheStatus
  generatedAt: string
  expiresAt: string
}

export interface DeepSourceCoverage {
  source: DeepEvidenceSource
  status: EvidenceStatus
  resultCount: number | null
}

export interface DeepSignalSummary {
  positive: string[]
  negative: string[]
  cautions: string[]
}

export interface DeepConsistency {
  level: DeepConsistencyLevel
  reason: string
}

export interface DeepEvidenceLink {
  source: 'BILIBILI' | 'XIAOHONGSHU' | 'DIANPING'
  title: string
  url: string
  publishedAt: string | null
}
