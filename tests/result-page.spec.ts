import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import * as recommendationApi from '@/api/recommendation'
import ResultPage from '@/pages/result/index.vue'
import { NavigationService } from '@/services/navigation'
import { recommendationStore } from '@/stores/recommendation'
import type { CreateRecommendationRequest, RecommendationResponse } from '@/types/recommendation'

vi.mock('@dcloudio/uni-app', () => ({
  onLoad: (callback: () => void) => callback(),
  onUnload: vi.fn(),
  onShareAppMessage: vi.fn(),
}))

const request: CreateRecommendationRequest = {
  latitude: 28.2282,
  longitude: 112.9388,
  radius: 1000,
  maxBudget: null,
  category: 'ANY',
  dislikes: [],
}

function recommendation(overrides: Partial<RecommendationResponse> = {}): RecommendationResponse {
  return {
    recommendationId: '3c34f61d-cb41-4850-aafe-1505f3312f06',
    restaurant: {
      id: 'restaurant-a',
      name: '老街牛肉粉',
      latitude: 28.2291,
      longitude: 112.9412,
      address: '麓山南路 123 号',
      category: { code: 'NOODLES', label: '粉面' },
      distanceMeters: 1620,
      walkingMinutes: 18,
      averagePrice: null,
      rating: null,
      businessStatus: 'OPEN',
    },
    risk: {
      riskScore: 48,
      riskLevel: 'MEDIUM_LOW',
      confidence: 0.4,
      reasons: ['信息有限'],
      algorithmVersion: 'risk-v0.3',
    },
    reasons: ['距离可接受'],
    alternativesRemaining: 0,
    ...overrides,
  }
}

describe('result page acceptance states', () => {
  beforeEach(() => {
    vi.stubGlobal('uni', {
      reLaunch: vi.fn(),
      navigateTo: vi.fn(),
      showToast: vi.fn(),
    })
  })

  afterEach(() => {
    recommendationStore.clear()
    vi.restoreAllMocks()
    vi.unstubAllGlobals()
  })

  it('returns home when opened without recommendation state', () => {
    const wrapper = mount(ResultPage)

    expect(wrapper.html()).toBe('<!--v-if-->')
    expect(uni.reLaunch).toHaveBeenCalledWith({ url: '/pages/home/index' })
  })

  it('renders tonight as a page with exhausted alternatives sealed', () => {
    recommendationStore.setCurrent(recommendation(), request)
    const wrapper = mount(ResultPage)

    expect(wrapper.text()).toContain('老街牛肉粉')
    expect(wrapper.text()).toContain('18 分钟的路')
    expect(wrapper.text()).toContain('人均还不清楚')
    expect(wrapper.text()).toContain('距离可接受')
    expect(wrapper.find('.reroll-button').exists()).toBe(false)
    expect(wrapper.text()).toContain('今晚写完了')
  })

  it('renders incomplete recall as a non-error notice', () => {
    recommendationStore.setCurrent(recommendation({
      searchNotice: {
        code: 'SEARCH_INCOMPLETE',
        message: '这一区间的餐厅还没搜完，先从已经找到的合适选项里为你选了一家。',
      },
    }), request)
    const wrapper = mount(ResultPage)

    expect(wrapper.find('.search-notice').exists()).toBe(true)
    expect(wrapper.find('.search-notice-message').text()).toContain('先从已经找到的合适选项里')
    expect(wrapper.find('.operation-error').exists()).toBe(false)
  })

  it('keeps platform ratings off the page itself', () => {
    recommendationStore.setCurrent(recommendation({
      evidenceSummary: {
        matchStatus: 'MATCHED',
        matchConfidence: 0.91,
        consistency: 'CONFLICT',
        ratingDifference: 0.7,
        reason: '高德评分比百度高 0.7 分',
        amap: {
          status: 'AVAILABLE', rating: 4.9, tasteRating: null, serviceRating: null,
          environmentRating: null, averagePrice: 58, commentCount: null,
        },
        baidu: {
          status: 'AVAILABLE', rating: 4.2, tasteRating: 4.0, serviceRating: 4.1,
          environmentRating: null, averagePrice: 62, commentCount: 2600,
        },
      },
    }), request)

    const wrapper = mount(ResultPage)

    expect(wrapper.find('.evidence-section').exists()).toBe(false)
    expect(wrapper.text()).not.toContain('口味 4.0')
    expect(wrapper.find('.deep-evidence-button').exists()).toBe(true)
  })

  it('opens the independent deep-evidence page for the frozen restaurant', async () => {
    recommendationStore.setCurrent(recommendation(), request)
    const wrapper = mount(ResultPage)

    await wrapper.find('.deep-evidence-button').trigger('click')

    expect(uni.navigateTo).toHaveBeenCalledWith({
      url: '/pages/deep-evidence/index?recommendationId=3c34f61d-cb41-4850-aafe-1505f3312f06&restaurantId=restaurant-a',
    })
  })

  it('shows reroll loading and replaces the view only with the server response', async () => {
    recommendationStore.setCurrent(recommendation({ alternativesRemaining: 5 }), request)
    let finishReroll!: (value: RecommendationResponse) => void
    const rerollSpy = vi
      .spyOn(recommendationApi, 'rerollRecommendation')
      .mockReturnValue(new Promise((resolve) => (finishReroll = resolve)))
    const wrapper = mount(ResultPage)

    await wrapper.find('.reroll-button').trigger('click')
    expect(wrapper.find('.reroll-button').text()).toContain('在写下一页')
    expect(wrapper.find('.accept-button').attributes('disabled')).toBeDefined()

    finishReroll(
      recommendation({
        restaurant: { ...recommendation().restaurant, id: 'restaurant-b', name: '南门盖饭' },
        alternativesRemaining: 0,
      }),
    )
    await flushPromises()

    expect(rerollSpy).toHaveBeenCalledOnce()
    expect(wrapper.text()).toContain('南门盖饭')
    expect(wrapper.find('.reroll-button').exists()).toBe(false)
  })

  it('records the abandoned restaurant as SKIP before rewriting conditions', async () => {
    recommendationStore.setCurrent(recommendation({ alternativesRemaining: 5 }), request)
    const behaviorSpy = vi.spyOn(recommendationApi, 'submitRecommendationBehavior')
      .mockResolvedValue({
        eventId: 'event-id',
        recommendationId: recommendation().recommendationId,
        restaurantId: 'restaurant-a',
        type: 'SKIP',
        recordedAt: '2026-08-28T12:00:00+08:00',
        deduplicated: false,
      })
    let finishCreate!: (value: RecommendationResponse) => void
    const createSpy = vi.spyOn(recommendationApi, 'createRecommendation')
      .mockReturnValue(new Promise((resolve) => (finishCreate = resolve)))
    const wrapper = mount(ResultPage)

    await wrapper.find('.text-btn').trigger('click')
    await wrapper.findAll('.sheet .chips')[0].findAll('button')[2].trigger('click')
    await wrapper.find('.sheet .go-button').trigger('click')
    expect(wrapper.find('.refresh-overlay').exists()).toBe(true)
    expect(wrapper.text()).toContain('正在重新选')
    expect(createSpy).toHaveBeenCalledWith(expect.objectContaining({
      minDistance: 1000,
      radius: 2000,
      excludeRestaurantId: 'restaurant-a',
    }))

    finishCreate(recommendation({
      recommendationId: 'new-recommendation-id',
      restaurant: { ...recommendation().restaurant, id: 'restaurant-b', name: '南门盖饭' },
    }))
    await flushPromises()

    expect(behaviorSpy).toHaveBeenCalledWith(
      recommendation().recommendationId,
      expect.any(String),
      'restaurant-a',
      'SKIP',
    )
    expect(recommendationStore.state.current?.recommendationId).toBe('new-recommendation-id')
    expect(wrapper.find('.refresh-overlay').exists()).toBe(false)
  })

  it('automatically advances when the refreshed session still starts with the old restaurant', async () => {
    recommendationStore.setCurrent(recommendation({ alternativesRemaining: 5 }), request)
    vi.spyOn(recommendationApi, 'submitRecommendationBehavior').mockResolvedValue({
      eventId: 'event-id',
      recommendationId: recommendation().recommendationId,
      restaurantId: 'restaurant-a',
      type: 'SKIP',
      recordedAt: '2026-08-28T12:00:00+08:00',
      deduplicated: false,
    })
    vi.spyOn(recommendationApi, 'createRecommendation').mockResolvedValue(
      recommendation({ recommendationId: 'refreshed-session', alternativesRemaining: 5 }),
    )
    const rerollSpy = vi.spyOn(recommendationApi, 'rerollRecommendation').mockResolvedValue(
      recommendation({
        recommendationId: 'refreshed-session',
        restaurant: { ...recommendation().restaurant, id: 'restaurant-b', name: '南门盖饭' },
        alternativesRemaining: 4,
      }),
    )
    const wrapper = mount(ResultPage)

    await wrapper.find('.text-btn').trigger('click')
    await wrapper.findAll('.sheet .chips')[0].findAll('button')[2].trigger('click')
    await wrapper.find('.sheet .go-button').trigger('click')
    await flushPromises()

    expect(rerollSpy).toHaveBeenCalledWith('refreshed-session')
    expect(wrapper.text()).toContain('南门盖饭')
  })

  it('collects optional flavor tags and records feedback once', async () => {
    recommendationStore.setCurrent(recommendation(), request)
    vi.spyOn(NavigationService, 'openRestaurant').mockResolvedValue()
    const feedbackSpy = vi.spyOn(recommendationApi, 'submitRecommendationFeedback').mockResolvedValue({
      feedbackId: 'feedback-id',
      recommendationId: recommendation().recommendationId,
      restaurantId: 'restaurant-a',
      result: 'LIKE',
      recordedAt: '2026-08-19T09:30:00+08:00',
    })
    const wrapper = mount(ResultPage)

    await wrapper.find('.accept-button').trigger('click')
    await wrapper.findAll('.feedback-button')[0].trigger('click')
    expect(wrapper.find('.flavor-panel').exists()).toBe(true)
    await wrapper.findAll('.flavor-option')[0].trigger('click')
    await wrapper.find('.flavor-submit').trigger('click')
    await flushPromises()

    expect(feedbackSpy).toHaveBeenCalledOnce()
    expect(feedbackSpy).toHaveBeenCalledWith(
      recommendation().recommendationId,
      'LIKE',
      ['SPICY'],
    )
    expect(wrapper.text()).toContain('记下了')
    expect(wrapper.findAll('.feedback-button').every((button) => button.attributes('disabled') !== undefined)).toBe(true)
  })

  it('shows navigation errors and restores the action after loading', async () => {
    recommendationStore.setCurrent(recommendation(), request)
    vi.spyOn(NavigationService, 'openRestaurant').mockRejectedValue(new Error('map failed'))
    const wrapper = mount(ResultPage)

    await wrapper.find('.accept-button').trigger('click')
    await flushPromises()

    expect(wrapper.find('.operation-error').text()).toBe('请求失败，请稍后再试')
    expect(wrapper.text()).toContain('出门')
  })
})
