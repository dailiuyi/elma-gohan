import { beforeEach, describe, expect, it, vi } from 'vitest'

import { apiRequest } from '@/api/client'
import { ApiError, getUserFacingError } from '@/api/errors'

const USER_ID = 'f57b640c-e4b2-4702-aa2f-2a9dacaf6910'

describe('api client', () => {
  beforeEach(() => {
    vi.stubEnv('VITE_API_BASE_URL', 'http://localhost:8080/api/v1')
  })

  it('adds the persistent anonymous UUID to every request', async () => {
    const request = vi.fn((options: UniApp.RequestOptions) => {
      options.success?.({ data: { ok: true }, statusCode: 200, header: {}, cookies: [] })
    })

    vi.stubGlobal('uni', {
      getStorageSync: vi.fn(() => USER_ID),
      setStorageSync: vi.fn(),
      request,
    })

    await expect(apiRequest<{ ok: boolean }>({ path: '/health' })).resolves.toEqual({ ok: true })
    expect(request).toHaveBeenCalledWith(
      expect.objectContaining({
        url: 'http://localhost:8080/api/v1/health',
        header: expect.objectContaining({ 'X-Anonymous-User-Id': USER_ID }),
      }),
    )
  })

  it('preserves backend error code, field errors, and trace ID', async () => {
    vi.stubGlobal('uni', {
      getStorageSync: vi.fn(() => USER_ID),
      setStorageSync: vi.fn(),
      request: vi.fn((options: UniApp.RequestOptions) => {
        options.success?.({
          statusCode: 400,
          header: {},
          cookies: [],
          data: {
            code: 'VALIDATION_FAILED',
            message: '请求参数不合法',
            fieldErrors: [{ field: 'radius', message: '取值无效' }],
            traceId: 'trace-1',
          },
        })
      }),
    })

    const error = await apiRequest({ path: '/example' }).catch((reason: unknown) => reason)

    expect(error).toBeInstanceOf(ApiError)
    if (!(error instanceof ApiError)) throw new Error('Expected ApiError')
    expect(error.response).toMatchObject({ code: 'VALIDATION_FAILED', traceId: 'trace-1' })
    expect(getUserFacingError(error)).toBe('请求参数不合法')
  })

  it('recognizes an incomplete POI search as a backend error with its own message', async () => {
    vi.stubGlobal('uni', {
      getStorageSync: vi.fn(() => USER_ID),
      setStorageSync: vi.fn(),
      request: vi.fn((options: UniApp.RequestOptions) => {
        options.success?.({
          statusCode: 422,
          header: {},
          cookies: [],
          data: {
            code: 'POI_SEARCH_INCOMPLETE',
            message: '附近餐厅较多，本次尚未完成全部检索，请缩小距离或选择具体品类后重试',
            traceId: 'trace-recall',
          },
        })
      }),
    })

    const error = await apiRequest({ path: '/recommendations' }).catch((reason: unknown) => reason)

    expect(error).toBeInstanceOf(ApiError)
    if (!(error instanceof ApiError)) throw new Error('Expected ApiError')
    expect(error.response).toMatchObject({
      code: 'POI_SEARCH_INCOMPLETE',
      traceId: 'trace-recall',
    })
    expect(getUserFacingError(error)).toContain('尚未完成全部检索')
  })

  it('normalizes request failures as network errors', async () => {
    vi.stubGlobal('uni', {
      getStorageSync: vi.fn(() => USER_ID),
      setStorageSync: vi.fn(),
      request: vi.fn((options: UniApp.RequestOptions) => {
        options.fail?.({ errMsg: 'request:fail timeout' })
      }),
    })

    const error = await apiRequest({ path: '/example' }).catch((reason: unknown) => reason)

    expect(error).toMatchObject({ kind: 'NETWORK' })
    expect(getUserFacingError(error)).toBe('网络连接失败，请检查网络后重试')
  })
})
