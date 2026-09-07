import type { ErrorResponse, RequestFailureKind } from '@/types/api'

const BACKEND_ERROR_CODES = new Set([
  'VALIDATION_FAILED',
  'NO_RECOMMENDATION_AVAILABLE',
  'POI_SEARCH_INCOMPLETE',
  'RECOMMENDATION_NOT_FOUND',
  'POI_PROVIDER_UNAVAILABLE',
  'FEEDBACK_ALREADY_RECORDED',
])

export class ApiError extends Error {
  readonly kind: RequestFailureKind
  readonly statusCode?: number
  readonly response?: ErrorResponse

  constructor(
    message: string,
    options: {
      kind: RequestFailureKind
      statusCode?: number
      response?: ErrorResponse
      cause?: unknown
    },
  ) {
    super(message, { cause: options.cause })
    this.name = 'ApiError'
    this.kind = options.kind
    this.statusCode = options.statusCode
    this.response = options.response
  }
}

export function parseResponseData(value: unknown): unknown {
  if (typeof value !== 'string') return value
  const trimmed = value.trim()
  if (!trimmed.startsWith('{') && !trimmed.startsWith('[')) return value
  try {
    return JSON.parse(trimmed) as unknown
  } catch {
    return value
  }
}

export function isErrorResponse(value: unknown): value is ErrorResponse {
  const parsed = parseResponseData(value)
  if (!parsed || typeof parsed !== 'object') return false

  const candidate = parsed as Partial<ErrorResponse>
  return (
    typeof candidate.code === 'string' &&
    BACKEND_ERROR_CODES.has(candidate.code) &&
    typeof candidate.message === 'string'
  )
}

export function isIncompleteSearchError(error: unknown): boolean {
  return error instanceof ApiError && error.response?.code === 'POI_SEARCH_INCOMPLETE'
}

export function getUserFacingError(error: unknown): string {
  if (error instanceof ApiError) {
    if (error.response) return error.response.message
    if (error.kind === 'NETWORK') return '网络连接失败，请检查网络后重试'
  }

  return '请求失败，请稍后再试'
}

