export const ANONYMOUS_USER_ID_STORAGE_KEY = 'elma.anonymous-user-id.v1'

const UUID_V4_PATTERN =
  /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i

function randomBytes(length: number): Uint8Array {
  const bytes = new Uint8Array(length)
  const runtimeCrypto = globalThis.crypto

  if (runtimeCrypto?.getRandomValues) {
    return runtimeCrypto.getRandomValues(bytes)
  }

  for (let index = 0; index < length; index += 1) {
    bytes[index] = Math.floor(Math.random() * 256)
  }

  return bytes
}

export function createUuidV4(): string {
  const bytes = randomBytes(16)
  bytes[6] = (bytes[6] & 0x0f) | 0x40
  bytes[8] = (bytes[8] & 0x3f) | 0x80

  const value = Array.from(bytes, (byte) => byte.toString(16).padStart(2, '0')).join('')

  return [value.slice(0, 8), value.slice(8, 12), value.slice(12, 16), value.slice(16, 20), value.slice(20)].join('-')
}

export const createAnonymousUserId = createUuidV4

export function isAnonymousUserId(value: unknown): value is string {
  return typeof value === 'string' && UUID_V4_PATTERN.test(value)
}

export function getAnonymousUserId(): string {
  const storedValue = uni.getStorageSync(ANONYMOUS_USER_ID_STORAGE_KEY) as unknown

  if (isAnonymousUserId(storedValue)) {
    return storedValue
  }

  const anonymousUserId = createAnonymousUserId()
  uni.setStorageSync(ANONYMOUS_USER_ID_STORAGE_KEY, anonymousUserId)
  return anonymousUserId
}

