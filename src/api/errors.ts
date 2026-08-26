import type { ErrorResponse, RequestFailureKind } from '@/types/api'

const BACKEND_ERROR_CODES = new Set([
  'VALIDATION_FAILED',
  'NO_RECOMMENDATION_AVAILABLE',
  'POI_SEARCH_INCOMPLETE',
  'RECOMMENDATION_NOT_FOUND',
  'POI_PROVIDER_UNAVAILABLE',
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

export function isErrorResponse(value: unknown): value is ErrorResponse {
  if (!value || typeof value !== 'object') return false

  const candidate = value as Partial<ErrorResponse>
  return (
    typeof candidate.code === 'string' &&
    BACKEND_ERROR_CODES.has(candidate.code) &&
    typeof candidate.message === 'string' &&
    typeof candidate.traceId === 'string'
  )
}

export function getUserFacingError(error: unknown): string {
  if (error instanceof ApiError) {
    if (error.response) return error.response.message
    if (error.kind === 'NETWORK') return '网络连接失败，请检查网络后重试'
  }

  return '请求失败，请稍后再试'
}

