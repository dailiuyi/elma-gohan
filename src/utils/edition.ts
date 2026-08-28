import type {
  CreateRecommendationRequest,
  FeedbackResponse,
  RecommendationResponse,
} from '@/types/recommendation'

export const EDITION_STORAGE_KEY = 'elma.tonight-edition.v1'

export type MealSlot = 'LUNCH' | 'DINNER' | 'LATE'

export interface EditionSnapshot {
  key: string
  slot: MealSlot
  current: RecommendationResponse
  lastRequest: CreateRecommendationRequest
  feedbackByRestaurantId: Record<string, FeedbackResponse>
  behaviorEventIds?: Record<string, string>
}

export function currentMealSlot(now = new Date()): MealSlot {
  const hour = now.getHours()
  if (hour < 15) return 'LUNCH'
  if (hour < 21) return 'DINNER'
  return 'LATE'
}

export function slotLabel(slot: MealSlot): string {
  if (slot === 'LUNCH') return '午饭'
  if (slot === 'DINNER') return '晚饭'
  return '夜宵'
}

export function slotKicker(slot: MealSlot): string {
  return slot === 'LUNCH' ? '今天 / TODAY' : '今晚 / TONIGHT'
}

export function editionKey(now = new Date()): string {
  return `${now.getFullYear()}-${now.getMonth() + 1}-${now.getDate()}:${currentMealSlot(now)}`
}

export function formatEditionDate(now = new Date()): string {
  return `${now.getMonth() + 1} 月 ${now.getDate()} 日 · ${slotLabel(currentMealSlot(now))}`
}

export function pageIndex(alternativesRemaining: number): number {
  return Math.min(6, Math.max(1, 6 - alternativesRemaining))
}

export function walkingLine(minutes: number): string {
  return `${minutes} 分钟的路`
}

export function priceLine(averagePrice: number | null): string {
  return averagePrice === null ? '人均还不清楚' : `人均 ${averagePrice}`
}

export function pageLine(reasons: readonly string[], riskLevel: string): string {
  const first = reasons[0]?.trim()
  if (first) return /[。.!！]$/.test(first) ? first : `${first}。`
  return riskLevel === 'LOW' ? '可以去。' : '有一点拿不准。'
}
