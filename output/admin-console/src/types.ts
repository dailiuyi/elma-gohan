export type Metric = number | null | undefined

export interface LocationPoint {
  code: string
  label: string
  province: string
  longitude: number
  latitude: number
  anonymousIds: number
  requests: number
  lowSample: boolean
}

export interface Locations {
  totalAnonymousIds: number
  totalRequests: number
  points: LocationPoint[]
  unmappedRequests?: number
  note?: string
}

export interface DailyRow {
  metricDate: string
  recommendations: Metric
  activeIds: Metric
  newIds: Metric
  accepts: Metric
  navigations: Metric
  rerolls: Metric
  feedbacks: Metric
  dislikes: Metric
}

export interface PeriodMetrics {
  requests: Metric
  activeIds: Metric
  newIds: Metric
  acceptedSessions: Metric
  acceptanceRate: Metric
  feedbackRate: Metric
}

export interface Snapshot {
  id: string
  schemaVersion?: number
  meta: {
    snapshotAt: string
    periodStart: string
    periodEnd: string
    windowDays: number
    timezone: string
    sourceMode: 'database' | 'fixture'
    readOnlyVerified: boolean
    queryCount: Metric
    queryDurationMs: Metric
    warnings: string[]
  }
  overview: {
    totalRecommendations: Metric
    totalAnonymousIds: Metric
    totalRestaurants: Metric
    totalFeedbacks: Metric
    periodRecommendations: Metric
    periodActiveIds: Metric
    periodNewIds: Metric
    periodReturningIds: Metric
    averageCandidateCount: Metric
  }
  funnel: {
    recommendationSessions: Metric
    acceptedSessions: Metric
    navigatedSessions: Metric
    feedbackSessions: Metric
    feedbackCount: Metric
    dislikedSessions: Metric
    acceptanceRate: Metric
    navigationRate: Metric
    feedbackRate: Metric
  }
  daily: DailyRow[]
  behaviors: { behaviorType: string; eventCount: number; sessionCount: number }[]
  feedback: { result: string; feedbackCount: number }[]
  risks: { riskLevel: string; recommendationCount: number }[]
  categories: { category: string; recommendationCount: number }[]
  algorithms: { algorithmVersion: string; selectionMode: string; recommendationCount: number }[]
  evidenceReliability: { queuedTasks?: Metric; retryingTasks?: Metric; freshQueries?: Metric; coolingDown?: boolean | null }
  tableRows: Record<string, number>
  shadow?: {
    available?: boolean
    note?: string
    summary?: Record<string, Metric>
    variants?: { experimentKey: string; variant: string; servedRecommendationAlgorithmVersion: string; shadowRecommendationAlgorithmVersion: string; snapshotCount: number }[]
    selectionReasons?: { label: string; selectionCount: number }[]
  }
  capabilities?: Record<string, boolean>
  locations: Locations
  analytics?: {
    comparison?: { previousFrom: string; previousTo: string; current: PeriodMetrics; previous: PeriodMetrics }
    frequency?: { label: string; users: number }[]
    heatmap?: { weekday: number; hour: number; requests: number }[]
    retention?: { cohortDate: string; cohortSize: number; day1: Metric; day7: Metric; day14: Metric; day30: Metric }[]
    quality?: {
      mappingStatuses: { status: string; count: number }[]
      deepStatuses: { source: string; status: string; count: number }[]
      totalMappings: Metric
      mappingsWithRatings: Metric
      freshMappings: Metric
    }
  }
}

export interface ConsoleState {
  csrfToken: string
  refresh: { running: boolean; startedAt?: string | null; finishedAt?: string | null; error?: string | null }
  history: { id: string; snapshotAt: string; from: string; to: string }[]
  currentId: string | null
}

export interface BarItem { label: string; value: Metric; tone?: string }
