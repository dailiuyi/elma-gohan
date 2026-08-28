import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import * as recommendationApi from '@/api/recommendation'
import HomePage from '@/pages/home/index.vue'
import { LocationService, LocationServiceError } from '@/services/location'
import { recommendationStore } from '@/stores/recommendation'
import type { RecommendationResponse } from '@/types/recommendation'

const response = {
  recommendationId: '3c34f61d-cb41-4850-aafe-1505f3312f06',
  restaurant: {
    id: 'restaurant-a',
    name: '老街牛肉粉',
    latitude: 28.2291,
    longitude: 112.9412,
    address: '麓山南路 123 号',
    category: { code: 'NOODLES', label: '粉面' },
    distanceMeters: 620,
    walkingMinutes: 8,
    averagePrice: 26,
    rating: 4.5,
    businessStatus: 'OPEN',
  },
  risk: {
    riskScore: 18,
    riskLevel: 'LOW',
    confidence: 0.82,
    reasons: ['评分稳定'],
    algorithmVersion: 'risk-v0.3',
  },
  reasons: ['距离近'],
  alternativesRemaining: 5,
} satisfies RecommendationResponse

describe('home page acceptance states', () => {
  beforeEach(() => {
    recommendationStore.clear()
    vi.stubGlobal('uni', {
      navigateTo: vi.fn(),
      showToast: vi.fn(),
    })
  })

  afterEach(() => {
    recommendationStore.clear()
    vi.restoreAllMocks()
    vi.unstubAllGlobals()
  })

  it('uses the V0.4 range defaults and submits normalized dislikes', async () => {
    vi.spyOn(LocationService, 'getCurrentLocation').mockResolvedValue({
      latitude: 28.2282,
      longitude: 112.9388,
      accuracy: 12.4,
    })
    const createSpy = vi.spyOn(recommendationApi, 'createRecommendation').mockResolvedValue(response)
    const wrapper = mount(HomePage)

    await flushPromises()
    expect(wrapper.find('.product-name').text()).toBe('ELMA 今天吃什么')
    expect(wrapper.findAll('.product-name')).toHaveLength(1)
    expect(wrapper.find('.brand').exists()).toBe(false)
    expect(wrapper.find('.edition').text()).toBe('elma-gohan / 1.0.0')
    expect(wrapper.find('.header-rail').exists()).toBe(true)
    expect(wrapper.findAll('.decision-flow__step').map((item) => item.text())).toEqual([
      '定位附近',
      '过滤风险',
      '只给一家',
    ])
    expect(wrapper.text()).toContain('已获取当前位置')
    expect(wrapper.find('.location-accuracy').text()).toContain('12 米')
    expect(wrapper.findAll('.choice-button--active').map((item) => item.text())).toEqual([
      '500m',
      '¥40',
    ])
    expect(wrapper.findAll('.range-caption').map((item) => item.text())).toEqual([
      '当前范围：500m 以内',
      '当前范围：人均 ¥20 以上至 ¥40',
    ])
    expect(wrapper.find('.category-value').text()).toContain('正餐')

    await wrapper.find('.privacy-link').trigger('click')
    expect(uni.navigateTo).toHaveBeenCalledWith({ url: '/pages/privacy/index' })

    await wrapper.find('input').setValue('香菜 内脏, 香菜,肥肉')
    await wrapper.find('.decision-button').trigger('click')
    await flushPromises()

    expect(createSpy).toHaveBeenCalledWith({
      latitude: 28.2282,
      longitude: 112.9388,
      minDistance: null,
      radius: 500,
      minBudget: 20,
      maxBudget: 40,
      category: 'MEAL',
      dislikes: ['香菜', '内脏', '肥肉'],
    })
    expect(uni.navigateTo).toHaveBeenCalledWith({ url: '/pages/result/index' })
  })

  it('maps every distance and budget button to a non-overlapping request range', async () => {
    vi.spyOn(LocationService, 'getCurrentLocation').mockResolvedValue({
      latitude: 28.2282,
      longitude: 112.9388,
    })
    const createSpy = vi.spyOn(recommendationApi, 'createRecommendation').mockResolvedValue(response)
    const wrapper = mount(HomePage)
    await flushPromises()

    const expectedDistances = [
      { minDistance: null, radius: 500, caption: '当前范围：500m 以内' },
      { minDistance: 500, radius: 1000, caption: '当前范围：500m 以上至 1km' },
      { minDistance: 1000, radius: 2000, caption: '当前范围：1km 以上至 2km' },
      { minDistance: 2000, radius: 3000, caption: '当前范围：2km 以上至 3km' },
    ]
    const distanceButtons = wrapper.findAll('.choice-section')[0].findAll('.choice-button')
    for (const [index, expected] of expectedDistances.entries()) {
      await distanceButtons[index].trigger('click')
      expect(wrapper.findAll('.range-caption')[0].text()).toBe(expected.caption)
      await wrapper.find('.decision-button').trigger('click')
      await flushPromises()
      expect(createSpy.mock.calls.at(-1)?.[0]).toEqual(expect.objectContaining({
        minDistance: expected.minDistance,
        radius: expected.radius,
      }))
    }

    const expectedBudgets = [
      { minBudget: null, maxBudget: 20, caption: '当前范围：人均 ¥20 以内' },
      { minBudget: 20, maxBudget: 40, caption: '当前范围：人均 ¥20 以上至 ¥40' },
      { minBudget: 40, maxBudget: 70, caption: '当前范围：人均 ¥40 以上至 ¥70' },
      { minBudget: 70, maxBudget: null, caption: '当前范围：人均高于 ¥70' },
    ]
    const budgetButtons = wrapper.findAll('.choice-section')[1].findAll('.choice-button')
    for (const [index, expected] of expectedBudgets.entries()) {
      await budgetButtons[index].trigger('click')
      expect(wrapper.findAll('.range-caption')[1].text()).toBe(expected.caption)
      await wrapper.find('.decision-button').trigger('click')
      await flushPromises()
      expect(createSpy.mock.calls.at(-1)?.[0]).toEqual(expect.objectContaining({
        minBudget: expected.minBudget,
        maxBudget: expected.maxBudget,
      }))
    }
  })

  it('allows an optional detailed category correction before deciding', async () => {
    vi.spyOn(LocationService, 'getCurrentLocation').mockResolvedValue({
      latitude: 28.2282,
      longitude: 112.9388,
    })
    const createSpy = vi.spyOn(recommendationApi, 'createRecommendation').mockResolvedValue(response)
    const wrapper = mount(HomePage)
    await flushPromises()

    wrapper.find('picker').element.dispatchEvent(
      new CustomEvent('change', { detail: { value: 1 } }),
    )
    await wrapper.vm.$nextTick()
    expect(wrapper.find('.category-value').text()).toContain('中餐（Chinese）')

    await wrapper.find('.decision-button').trigger('click')
    await flushPromises()
    expect(createSpy).toHaveBeenCalledWith(expect.objectContaining({ category: 'CHINESE' }))
  })

  it('shows location denial and blocks recommendation without coordinates', async () => {
    vi.spyOn(LocationService, 'getCurrentLocation').mockRejectedValue(
      new LocationServiceError('PERMISSION_DENIED', 'denied'),
    )
    const createSpy = vi.spyOn(recommendationApi, 'createRecommendation')
    const wrapper = mount(HomePage)

    await flushPromises()
    expect(wrapper.text()).toContain('定位权限未开启')
    expect(wrapper.find('.text-action').text()).toBe('去开启')

    await wrapper.find('.decision-button').trigger('click')
    expect(wrapper.find('.request-error').text()).toContain('请先开启定位权限')
    expect(createSpy).not.toHaveBeenCalled()
  })

  it('disables repeated submission while the request is loading', async () => {
    vi.spyOn(LocationService, 'getCurrentLocation').mockResolvedValue({
      latitude: 28.2282,
      longitude: 112.9388,
    })
    let finishRequest!: (value: RecommendationResponse) => void
    const createSpy = vi
      .spyOn(recommendationApi, 'createRecommendation')
      .mockReturnValue(new Promise((resolve) => (finishRequest = resolve)))
    const wrapper = mount(HomePage)

    await flushPromises()
    await wrapper.find('.decision-button').trigger('click')

    expect(wrapper.find('.decision-button').attributes('disabled')).toBeDefined()
    expect(wrapper.find('.decision-button').text()).toContain('正在决定')
    await wrapper.find('.decision-button').trigger('click')
    expect(createSpy).toHaveBeenCalledTimes(1)

    finishRequest(response)
    await flushPromises()
  })
})
