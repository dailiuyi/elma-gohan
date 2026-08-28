<template>
  <view class="privacy-page">
    <view class="page-header">
      <button class="back-button" @click="goBack">←</button>
      <text class="page-title">隐私与数据</text>
      <view class="header-space" />
    </view>

    <view class="intro">
      <text class="eyebrow">数据</text>
      <text class="headline">少收集，讲明白。</text>
      <text class="updated-at">更新日期：2026 年 8 月 28 日</text>
    </view>

    <view class="section">
      <text class="section-title">我们会使用什么</text>
      <text class="paragraph">
        你主动授权的当前位置，用于查找附近餐厅；系统生成的匿名编号，用于保存推荐会话、换一家、反馈、行为、近期饮食历史和个性化偏好。我们不要求手机号、真实姓名、微信账号或支付信息。
      </text>
    </view>

    <view class="section">
      <text class="section-title">数据会发给谁</text>
      <text class="paragraph">
        附近餐厅查询会使用高德地图和百度地图服务。只有点击“深挖一下”时，才会通过 Brave Search 查询 B站、小红书和大众点评的公开索引线索；不会登录这些平台，也不会抓取网页正文、评论区或用户资料。
      </text>
    </view>

    <view class="section">
      <text class="section-title">保存与保护</text>
      <text class="paragraph">
        个性化数据保存在项目服务器的 PostgreSQL 中，并以匿名编号关联。位置和筛选条件会随推荐会话保存，方便复现当次决定。服务器密钥不会下发到小程序。
      </text>
    </view>

    <view class="section">
      <text class="section-title">你的选择</text>
      <text class="paragraph">
        你可以拒绝定位，此时我们无法提供附近推荐。你也可以在这里删除当前匿名编号关联的全部推荐会话、反馈、行为、饮食历史、口味标注和个性化画像。共享餐厅资料不会因单个用户删除而移除。
      </text>
    </view>

    <view class="delete-panel">
      <text class="delete-title">删除我的数据</text>
      <text class="delete-note">
        删除后无法恢复。本设备下次使用时会生成新的匿名编号，从零开始推荐。
      </text>
      <button
        class="delete-button"
        :disabled="deleting || deleted"
        @click="handleDelete"
      >
        {{ deleteButtonText }}
      </button>
      <text v-if="errorMessage" class="error-message">{{ errorMessage }}</text>
    </view>

    <text class="contact-note">
      其他隐私问题可通过 GitHub 项目 1753262762/elma-gohan 的 Issues 联系开发者。
    </text>
  </view>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'

import { deleteMyUserData } from '@/api/recommendation'
import { getUserFacingError } from '@/api/errors'
import { clearAnonymousUserId } from '@/services/anonymous-user'
import { recommendationStore } from '@/stores/recommendation'

const deleting = ref(false)
const deleted = ref(false)
const errorMessage = ref('')

const deleteButtonText = computed(() => {
  if (deleted.value) return '数据已清除'
  return deleting.value ? '正在删除…' : '删除我的全部数据'
})

function confirmDeletion(): Promise<boolean> {
  return new Promise((resolve) => {
    uni.showModal({
      title: '确认删除全部数据？',
      content: '推荐记录、反馈和个性化画像都会永久删除，且无法恢复。',
      confirmText: '确认删除',
      confirmColor: '#a6455a',
      success: (result) => resolve(result.confirm),
      fail: () => resolve(false),
    })
  })
}

async function handleDelete() {
  if (deleting.value || deleted.value || !(await confirmDeletion())) return

  deleting.value = true
  errorMessage.value = ''
  try {
    await deleteMyUserData()
    clearAnonymousUserId()
    recommendationStore.clear()
    deleted.value = true
    uni.showToast({ title: '数据已清除', icon: 'success' })
  } catch (error) {
    errorMessage.value = getUserFacingError(error)
  } finally {
    deleting.value = false
  }
}

function goBack() {
  uni.navigateBack()
}
</script>

<style scoped>
.privacy-page {
  box-sizing: border-box;
  min-height: 100vh;
  padding: calc(var(--status-bar-height) + var(--elma-custom-nav-offset)) 44rpx 64rpx;
  background: #f7f7f5;
  color: #171717;
}

.page-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.back-button,
.header-space {
  width: 64rpx;
}

.back-button {
  margin: 0;
  padding: 8rpx 0;
  background: transparent;
  color: #5d6d5a;
  font-size: 36rpx;
  line-height: 1;
}

.page-title {
  font-size: 28rpx;
  font-weight: 700;
}

.intro {
  display: flex;
  flex-direction: column;
  margin-top: 54rpx;
  padding-bottom: 34rpx;
  border-bottom: 2rpx solid #d9ddea;
}

.eyebrow,
.updated-at {
  color: #747d97;
  font-size: 18rpx;
  letter-spacing: 2rpx;
}

.headline {
  margin-top: 14rpx;
  font-size: 42rpx;
  font-weight: 700;
  line-height: 1.3;
}

.updated-at {
  margin-top: 18rpx;
  letter-spacing: 1rpx;
}

.section {
  margin-top: 36rpx;
}

.section-title,
.paragraph,
.delete-title,
.delete-note,
.contact-note,
.error-message {
  display: block;
}

.section-title,
.delete-title {
  font-size: 25rpx;
  font-weight: 700;
}

.paragraph,
.delete-note,
.contact-note {
  margin-top: 14rpx;
  color: #5d6680;
  font-size: 22rpx;
  line-height: 1.75;
}

.delete-panel {
  margin-top: 44rpx;
  padding: 28rpx;
  border: 2rpx solid #e5c9cf;
  border-radius: 14rpx;
  background: #fffafb;
}

.delete-button {
  margin: 26rpx 0 0;
  padding: 22rpx;
  border: 2rpx solid #a6455a;
  border-radius: 10rpx;
  background: transparent;
  color: #a6455a;
  font-size: 23rpx;
  line-height: 1;
}

.delete-button[disabled] {
  opacity: 0.55;
}

.error-message {
  margin-top: 16rpx;
  color: #a6455a;
  font-size: 20rpx;
}

.contact-note {
  margin-top: 34rpx;
  font-size: 19rpx;
  text-align: center;
}
</style>
