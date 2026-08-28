import type { CategoryFilterCode, Radius } from '@/types/recommendation'

export interface RadiusOption {
  label: string
  minDistance: number | null
  radius: Radius
  description: string
}

export interface BudgetOption {
  label: string
  minBudget: number | null
  maxBudget: number | null
  description: string
}

export const radiusOptions: RadiusOption[] = [
  { label: '500m', minDistance: null, radius: 500, description: '走不远，五百米以内' },
  { label: '1km', minDistance: 500, radius: 1000, description: '五百米到一公里' },
  { label: '2km', minDistance: 1000, radius: 2000, description: '一公里到两公里' },
  { label: '3km', minDistance: 2000, radius: 3000, description: '两公里到三公里' },
]

export const budgetOptions: BudgetOption[] = [
  { label: '¥20', minBudget: null, maxBudget: 20, description: '人均二十以内' },
  { label: '¥40', minBudget: 20, maxBudget: 40, description: '人均二十到四十' },
  { label: '¥70', minBudget: 40, maxBudget: 70, description: '人均四十到七十' },
  { label: '¥70+', minBudget: 70, maxBudget: null, description: '人均七十以上' },
]

export const categoryOptions: Array<{ label: string; value: CategoryFilterCode }> = [
  { label: '正餐', value: 'MEAL' },
  { label: '中餐', value: 'CHINESE' },
  { label: '火锅', value: 'HOT_POT' },
  { label: '烧烤', value: 'BARBECUE' },
  { label: '粉面', value: 'NOODLES' },
  { label: '小吃快餐', value: 'FAST_FOOD' },
  { label: '西餐', value: 'WESTERN' },
  { label: '日韩料理', value: 'JAPANESE_KOREAN' },
  { label: '饮品甜品', value: 'DESSERT_DRINK' },
  { label: '随便', value: 'ANY' },
]

export const defaultRadius = radiusOptions[0]
export const defaultBudget = budgetOptions[1]
export const defaultCategory = categoryOptions[0]
