import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import * as recommendationApi from '@/api/recommendation'
import HomePage from '@/pages/home/index.vue'
import { LocationService, LocationServiceError } from '@/services/location'
import { recommendationStore } from '@/stores/recommendation'
import type { RecommendationResponse } from '@/types/recommendation'
import { EDITION_STORAGE_KEY, editionKey } from '@/utils/edition'

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

describe('home page tonight boot', () => {
  const storage = new Map<string, unknown>()

  beforeEach(() => {
    recommendationStore.clear()
    storage.clear()
    vi.stubGlobal('uni', {
      navigateTo: vi.fn(),
      redirectTo: vi.fn(),
      reLaunch: vi.fn(),
      showToast: vi.fn(),
      getStorageSync: vi.fn((key: string) => storage.get(key)),
      setStorageSync: vi.fn((key: string, value: unknown) => storage.set(key, value)),
      removeStorageSync: vi.fn((key: string) => storage.delete(key)),
    })
  })

  afterEach(() => {
    recommendationStore.clear()
    vi.restoreAllMocks()
    vi.unstubAllGlobals()
  })

  it('writes tonight automatically with the V0.4 range defaults', async () => {
    vi.spyOn(LocationService, 'getCurrentLocation').mockResolvedValue({
      latitude: 28.2282,
      longitude: 112.9388,
      accuracy: 12.4,
    })
    const createSpy = vi.spyOn(recommendationApi, 'createRecommendation').mockResolvedValue(response)
    const wrapper = mount(HomePage)

    await flushPromises()
    expect(wrapper.text()).toContain('先看附近')
    expect(createSpy).toHaveBeenCalledWith({
      latitude: 28.2282,
      longitude: 112.9388,
      minDistance: null,
      radius: 500,
      minBudget: 20,
      maxBudget: 40,
      category: 'MEAL',
      dislikes: [],
    })
    expect(uni.redirectTo).toHaveBeenCalledWith({ url: '/pages/result/index' })
  })

  it('restores the same edition without creating another session', async () => {
    storage.set(EDITION_STORAGE_KEY, {
      key: editionKey(),
      slot: 'LUNCH',
      current: response,
      lastRequest: {
        latitude: 28.2282,
        longitude: 112.9388,
        radius: 500,
        maxBudget: 40,
        category: 'MEAL',
        dislikes: [],
      },
      feedbackByRestaurantId: {},
    })
    const createSpy = vi.spyOn(recommendationApi, 'createRecommendation')
    const wrapper = mount(HomePage)
    await flushPromises()

    expect(wrapper.text()).toContain('还是这一页')
    expect(createSpy).not.toHaveBeenCalled()
    expect(uni.redirectTo).toHaveBeenCalledWith({ url: '/pages/result/index' })
  })

  it('asks for location instead of writing a page', async () => {
    vi.spyOn(LocationService, 'getCurrentLocation').mockRejectedValue(
      new LocationServiceError('PERMISSION_DENIED', 'denied'),
    )
    const createSpy = vi.spyOn(recommendationApi, 'createRecommendation')
    const wrapper = mount(HomePage)
    await flushPromises()

    expect(wrapper.text()).toContain('这一页需要')
    expect(wrapper.find('.go-button').text()).toBe('去开启定位')
    expect(createSpy).not.toHaveBeenCalled()
  })

  it('opens privacy from the boot page', async () => {
    vi.spyOn(LocationService, 'getCurrentLocation').mockRejectedValue(
      new LocationServiceError('PERMISSION_DENIED', 'denied'),
    )
    const wrapper = mount(HomePage)
    await flushPromises()
    await wrapper.find('.privacy-link').trigger('click')
    expect(uni.navigateTo).toHaveBeenCalledWith({ url: '/pages/privacy/index' })
  })

  it('lets the user widen filters after an automatic recommendation fails', async () => {
    vi.spyOn(LocationService, 'getCurrentLocation').mockResolvedValue({
      latitude: 28.2282,
      longitude: 112.9388,
      accuracy: 12.4,
    })
    const createSpy = vi.spyOn(recommendationApi, 'createRecommendation')
      .mockRejectedValueOnce(new Error('no candidate'))
      .mockResolvedValueOnce(response)
    const wrapper = mount(HomePage)
    await flushPromises()

    expect(wrapper.text()).toContain('请求失败')
    await wrapper.find('.adjust-button').trigger('click')
    expect(wrapper.find('.sheet').exists()).toBe(true)

    await wrapper.findAll('.chips')[0].findAll('button')[1].trigger('click')
    await wrapper.findAll('.chips')[1].findAll('button')[2].trigger('click')
    await wrapper.find('.field-input').setValue('粉 面')
    await wrapper.find('.sheet-submit').trigger('click')
    await flushPromises()

    expect(createSpy).toHaveBeenLastCalledWith({
      latitude: 28.2282,
      longitude: 112.9388,
      minDistance: 500,
      radius: 1000,
      minBudget: 40,
      maxBudget: 70,
      category: 'MEAL',
      dislikes: ['粉', '面'],
    })
    expect(uni.redirectTo).toHaveBeenCalledWith({ url: '/pages/result/index' })
  })
})
