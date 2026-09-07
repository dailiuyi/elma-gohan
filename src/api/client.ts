import { getAnonymousUserId } from '@/services/anonymous-user'

import { ApiError, isErrorResponse, parseResponseData } from './errors'
import type { ErrorResponse } from '@/types/api'

type HttpMethod = 'GET' | 'POST' | 'PUT' | 'DELETE'

const REQUEST_TIMEOUT_MS = 15000

export interface ApiRequestOptions {
  path: string
  method?: HttpMethod
  data?: UniApp.RequestOptions['data']
  headers?: Record<string, string>
}

function getApiBaseUrl(): string {
  return import.meta.env.VITE_API_BASE_URL.replace(/\/$/, '')
}

function rejectHttpError(statusCode: number, data: unknown): ApiError {
  const parsed = parseResponseData(data)
  if (isErrorResponse(parsed)) {
    const response: ErrorResponse = {
      code: parsed.code,
      message: parsed.message,
      fieldErrors: parsed.fieldErrors,
      traceId: typeof parsed.traceId === 'string' ? parsed.traceId : '',
    }
    return new ApiError(parsed.message, {
      kind: 'BACKEND',
      statusCode,
      response,
    })
  }
  return new ApiError('服务暂时不可用，请稍后再试', {
    kind: 'UNKNOWN',
    statusCode,
  })
}

export function apiRequest<T>(options: ApiRequestOptions): Promise<T> {
  return new Promise((resolve, reject) => {
    let settled = false
    const settle = (action: () => void) => {
      if (settled) return
      settled = true
      action()
    }

    uni.request({
      url: `${getApiBaseUrl()}${options.path.startsWith('/') ? options.path : `/${options.path}`}`,
      method: options.method ?? 'GET',
      data: options.data,
      timeout: REQUEST_TIMEOUT_MS,
      header: {
        'Content-Type': 'application/json',
        ...options.headers,
        'X-Anonymous-User-Id': getAnonymousUserId(),
      },
      success(response) {
        settle(() => {
          if (response.statusCode >= 200 && response.statusCode < 300) {
            resolve(parseResponseData(response.data) as T)
            return
          }
          reject(rejectHttpError(response.statusCode, response.data))
        })
      },
      fail(error) {
        settle(() => {
          const failed = error as UniApp.GeneralCallbackResult & {
            statusCode?: number
            data?: unknown
          }
          if (typeof failed.statusCode === 'number') {
            reject(rejectHttpError(failed.statusCode, failed.data))
            return
          }
          reject(
            new ApiError('网络连接失败，请检查网络后重试', {
              kind: 'NETWORK',
              cause: error,
            }),
          )
        })
      },
    })
  })
}
