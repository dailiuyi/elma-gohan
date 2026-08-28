import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import * as recommendationApi from '@/api/recommendation'
import PrivacyPage from '@/pages/privacy/index.vue'
import { ANONYMOUS_USER_ID_STORAGE_KEY } from '@/services/anonymous-user'
import { recommendationStore } from '@/stores/recommendation'

describe('privacy page', () => {
  const storage = new Map<string, unknown>()

  beforeEach(() => {
    storage.set(ANONYMOUS_USER_ID_STORAGE_KEY, '11111111-1111-4111-8111-111111111111')
    vi.stubGlobal('uni', {
      showModal: vi.fn((options) => options.success?.({ confirm: true, cancel: false })),
      showToast: vi.fn(),
      navigateBack: vi.fn(),
      removeStorageSync: vi.fn((key: string) => storage.delete(key)),
    })
    recommendationStore.clear()
  })

  afterEach(() => {
    vi.restoreAllMocks()
    vi.unstubAllGlobals()
  })

  it('explains data use and deletes server and local anonymous data', async () => {
    const deleteSpy = vi.spyOn(recommendationApi, 'deleteMyUserData').mockResolvedValue()
    const wrapper = mount(PrivacyPage)

    expect(wrapper.text()).toContain('高德地图和百度地图')
    expect(wrapper.text()).toContain('不会抓取网页正文、评论区或用户资料')

    await wrapper.find('.delete-button').trigger('click')
    await flushPromises()

    expect(deleteSpy).toHaveBeenCalledTimes(1)
    expect(storage.has(ANONYMOUS_USER_ID_STORAGE_KEY)).toBe(false)
    expect(wrapper.find('.delete-button').text()).toBe('数据已清除')
    expect(uni.showToast).toHaveBeenCalledWith({ title: '数据已清除', icon: 'success' })
  })
})
