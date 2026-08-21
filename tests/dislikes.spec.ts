import { describe, expect, it } from 'vitest'

import { DislikesValidationError, parseDislikes } from '@/utils/dislikes'

describe('dislikes parser', () => {
  it('splits Chinese commas, English commas, line breaks, and spaces', () => {
    expect(parseDislikes('香菜，内脏\n太辣 榴莲,肥肉')).toEqual([
      '香菜',
      '内脏',
      '太辣',
      '榴莲',
      '肥肉',
    ])
  })

  it('trims blanks, deduplicates, and treats consecutive spaces as one separator', () => {
    expect(parseDislikes('  香菜，香菜,SPICY, spicy, 牛肉   面  ')).toEqual([
      '香菜', 'SPICY', '牛肉', '面',
    ])
  })

  it('rejects more than ten unique items', () => {
    expect(() => parseDislikes('1,2,3,4,5,6,7,8,9,10,11')).toThrow(DislikesValidationError)
  })

  it('rejects a phrase longer than thirty characters', () => {
    expect(() => parseDislikes('一'.repeat(31))).toThrow('不能超过 30 个字')
  })
})

