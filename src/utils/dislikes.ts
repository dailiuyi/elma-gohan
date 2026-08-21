const MAX_DISLIKES = 10
const MAX_DISLIKE_LENGTH = 30

export class DislikesValidationError extends Error {
  constructor(message: string) {
    super(message)
    this.name = 'DislikesValidationError'
  }
}

export function parseDislikes(input: string): string[] {
  const uniqueValues = new Map<string, string>()

  for (const segment of input.split(/[,，\s]+/u)) {
    const value = segment.trim()
    if (!value) continue

    if (value.length > MAX_DISLIKE_LENGTH) {
      throw new DislikesValidationError(`“${value.slice(0, 8)}…”不能超过 30 个字`)
    }

    const normalized = value.toLocaleLowerCase()
    if (!uniqueValues.has(normalized)) {
      uniqueValues.set(normalized, value)
    }
  }

  const values = Array.from(uniqueValues.values())
  if (values.length > MAX_DISLIKES) {
    throw new DislikesValidationError('不想吃的内容最多填写 10 项')
  }

  return values
}

