import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import * as recommendationApi from '@/api/recommendation'
import DeepEvidencePage from '@/pages/deep-evidence/index.vue'
import type { DeepEvidenceResponse } from '@/types/recommendation'

let pageQuery = {
  recommendationId: 'recommendation-a',
  restaurantId: 'restaurant-a',
}

vi.mock('@dcloudio/uni-app', () => ({
  onLoad: (callback: (query: Record<string, string>) => void) => callback(pageQuery),
}))

function response(overrides: Partial<DeepEvidenceResponse> = {}): DeepEvidenceResponse {
  return {
    recommendationId: 'recommendation-a',
    restaurantId: 'restaurant-a',
    restaurantName: '老王湘菜馆',
    baseRisk: {
      riskScore: 18,
      riskLevel: 'LOW',
      confidence: 0.76,
      reasons: ['基础数据较稳定'],
      algorithmVersion: 'risk-v0.3',
    },
    deepRisk: {
      riskScore: 21,
      riskLevel: 'MEDIUM_LOW',
      confidence: 0.82,
      reasons: ['部分公开结果提到偏咸'],
      algorithmVersion: 'deep-risk-v0.1',
    },
    structuredEvidence: null,
    sourceCoverage: [
      { source: 'AMAP', status: 'AVAILABLE', resultCount: null },
      { source: 'BAIDU', status: 'AVAILABLE', resultCount: null },
      { source: 'BILIBILI', status: 'AVAILABLE', resultCount: 1 },
      { source: 'XIAOHONGSHU', status: 'NO_DATA', resultCount: 0 },
      { source: 'DIANPING', status: 'UNAVAILABLE', resultCount: 0 },
    ],
    signals: {
      positive: ['多次提到性价比'],
      negative: ['部分结果提到偏咸'],
      cautions: ['高峰期可能需要排队'],
    },
    consistency: { level: 'MEDIUM', reason: '多数公开来源方向接近' },
    links: [{
      source: 'BILIBILI',
      title: '老王湘菜馆到底值不值得去',
      url: 'https://www.bilibili.com/video/BV1test',
      publishedAt: '2026-08-10T00:00:00Z',
    }],
    cacheStatus: 'MISS',
    generatedAt: '2026-08-20T12:00:00Z',
    expiresAt: '2026-08-20T18:00:00Z',
    ...overrides,
  }
}

describe('deep evidence page', () => {
  beforeEach(() => {
    pageQuery = { recommendationId: 'recommendation-a', restaurantId: 'restaurant-a' }
    vi.stubGlobal('uni', {
      navigateBack: vi.fn(),
      setClipboardData: vi.fn((options) => options.success?.()),
      showToast: vi.fn(),
    })
  })

  afterEach(() => {
    vi.restoreAllMocks()
    vi.unstubAllGlobals()
  })

  it('renders returned public clues and copies result links', async () => {
    vi.spyOn(recommendationApi, 'deepenRecommendationEvidence').mockResolvedValue(response())
    const wrapper = mount(DeepEvidencePage)
    await flushPromises()

    expect(wrapper.text()).toContain('老王湘菜馆')
    expect(wrapper.text()).toContain('82%')
    expect(wrapper.text()).toContain('公开索引里的标题和摘要')
    expect(wrapper.text()).toContain('暂未找到同店公开线索')
    expect(wrapper.text()).toContain('多次提到性价比')

    await wrapper.find('.link-row').trigger('click')
    expect(uni.setClipboardData).toHaveBeenCalledWith(expect.objectContaining({
      data: 'https://www.bilibili.com/video/BV1test',
    }))
  })

  it('does not render a late response for a different restaurant', async () => {
    vi.spyOn(recommendationApi, 'deepenRecommendationEvidence').mockResolvedValue(
      response({ restaurantId: 'restaurant-b', restaurantName: '另一家店' }),
    )
    const wrapper = mount(DeepEvidencePage)
    await flushPromises()

    expect(wrapper.text()).toContain('当前推荐已经变化')
    expect(wrapper.text()).not.toContain('另一家店')
  })

  it('keeps the base conclusion and offers retry when all web sources fail', async () => {
    vi.spyOn(recommendationApi, 'deepenRecommendationEvidence').mockResolvedValue(response({
      sourceCoverage: [
        { source: 'AMAP', status: 'AVAILABLE', resultCount: null },
        { source: 'BAIDU', status: 'AVAILABLE', resultCount: null },
        { source: 'BILIBILI', status: 'UNAVAILABLE', resultCount: 0 },
        { source: 'XIAOHONGSHU', status: 'UNAVAILABLE', resultCount: 0 },
        { source: 'DIANPING', status: 'UNAVAILABLE', resultCount: 0 },
      ],
      signals: { positive: [], negative: [], cautions: [] },
      links: [],
    }))
    const wrapper = mount(DeepEvidencePage)
    await flushPromises()

    expect(wrapper.text()).toContain('基础数据较稳定')
    expect(wrapper.find('.retry-button').exists()).toBe(true)
  })
})
