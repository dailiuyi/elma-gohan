import { beforeEach, describe, expect, it, vi } from 'vitest'

import {
  ANONYMOUS_USER_ID_STORAGE_KEY,
  createAnonymousUserId,
  clearAnonymousUserId,
  getAnonymousUserId,
  isAnonymousUserId,
} from '@/services/anonymous-user'

describe('anonymous user identity', () => {
  const storage = new Map<string, unknown>()

  beforeEach(() => {
    storage.clear()
    vi.stubGlobal('uni', {
      getStorageSync: vi.fn((key: string) => storage.get(key)),
      setStorageSync: vi.fn((key: string, value: unknown) => storage.set(key, value)),
      removeStorageSync: vi.fn((key: string) => storage.delete(key)),
    })
  })

  it('generates a UUID v4', () => {
    expect(isAnonymousUserId(createAnonymousUserId())).toBe(true)
  })

  it('persists a generated ID and reuses it', () => {
    const first = getAnonymousUserId()
    const second = getAnonymousUserId()

    expect(second).toBe(first)
    expect(storage.get(ANONYMOUS_USER_ID_STORAGE_KEY)).toBe(first)
    expect(uni.setStorageSync).toHaveBeenCalledTimes(1)
  })

  it('replaces an invalid stored value', () => {
    storage.set(ANONYMOUS_USER_ID_STORAGE_KEY, 'not-a-uuid')

    expect(isAnonymousUserId(getAnonymousUserId())).toBe(true)
    expect(uni.setStorageSync).toHaveBeenCalledTimes(1)
  })

  it('clears the persisted anonymous identity', () => {
    storage.set(ANONYMOUS_USER_ID_STORAGE_KEY, createAnonymousUserId())

    clearAnonymousUserId()

    expect(storage.has(ANONYMOUS_USER_ID_STORAGE_KEY)).toBe(false)
    expect(uni.removeStorageSync).toHaveBeenCalledWith(ANONYMOUS_USER_ID_STORAGE_KEY)
  })
})

