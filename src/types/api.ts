export type BackendErrorCode =
  | 'VALIDATION_FAILED'
  | 'NO_RECOMMENDATION_AVAILABLE'
  | 'POI_SEARCH_INCOMPLETE'
  | 'RECOMMENDATION_NOT_FOUND'
  | 'POI_PROVIDER_UNAVAILABLE'
  | 'FEEDBACK_ALREADY_RECORDED'

export interface FieldError {
  field: string
  message: string
}

export interface ErrorResponse {
  code: BackendErrorCode
  message: string
  fieldErrors?: FieldError[]
  traceId: string
}

export type RequestFailureKind = 'BACKEND' | 'NETWORK' | 'UNKNOWN'

