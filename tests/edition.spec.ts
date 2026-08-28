import { describe, expect, it } from 'vitest'

import { pageIndex, pageLine, priceLine, walkingLine } from '@/utils/edition'

describe('tonight page copy', () => {
  it('counts pages from remaining alternatives', () => {
    expect(pageIndex(5)).toBe(1)
    expect(pageIndex(0)).toBe(6)
  })

  it('uses the first reason as the page line', () => {
    expect(pageLine(['距离近'], 'LOW')).toBe('距离近。')
    expect(pageLine(['路不远。'], 'LOW')).toBe('路不远。')
    expect(pageLine([], 'LOW')).toBe('可以去。')
  })

  it('formats walking and price for the meta line', () => {
    expect(walkingLine(8)).toBe('8 分钟的路')
    expect(priceLine(null)).toBe('人均还不清楚')
    expect(priceLine(38)).toBe('人均 38')
  })
})
