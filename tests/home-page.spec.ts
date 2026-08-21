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

  it('uses the V0.12 defaults and submits normalized dislikes', async () => {
    vi.spyOn(LocationService, 'getCurrentLocation').mockResolvedValue({
      latitude: 28.2282,
      longitude: 112.9388,
      accuracy: 12.4,
    })
    const createSpy = vi.spyOn(recommendationApi, 'createRecommendation').mockResolvedValue(response)
    const wrapper = mount(HomePage)

    await flushPromises()
    expect(wrapper.text()).toContain('已获取当前位置')
    expect(wrapper.find('.location-accuracy').text()).toContain('12 米')
    expect(wrapper.findAll('.choice-button--active').map((item) => item.text())).toEqual([
      '1km',
      '不限',
    ])
    expect(wrapper.find('.category-value').text()).toContain('正餐')

    await wrapper.find('input').setValue('香菜 内脏, 香菜,肥肉')
    await wrapper.find('.decision-button').trigger('click')
    await flushPromises()

    expect(createSpy).toHaveBeenCalledWith({
      latitude: 28.2282,
      longitude: 112.9388,
      radius: 1000,
      maxBudget: null,
      category: 'MEAL',
      dislikes: ['香菜', '内脏', '肥肉'],
    })
    expect(uni.navigateTo).toHaveBeenCalledWith({ url: '/pages/result/index' })
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
