const HOME_STAY_KEY = 'elma.home.stay'
const HOME_URL = '/pages/home/index'
const HOME_ROUTE = 'pages/home/index'

export function markStayOnHome() {
  try {
    uni.setStorageSync(HOME_STAY_KEY, 1)
  } catch {
    // ignore quota / missing storage
  }
}

export function consumeStayOnHome(): boolean {
  try {
    const marked = Boolean(uni.getStorageSync(HOME_STAY_KEY))
    if (marked) uni.removeStorageSync(HOME_STAY_KEY)
    return marked
  } catch {
    return false
  }
}

export function goHome() {
  markStayOnHome()
  const pages = typeof getCurrentPages === 'function' ? getCurrentPages() : []
  const homeIndex = pages.findIndex((page) => page.route === HOME_ROUTE)
  if (homeIndex >= 0) {
    const delta = pages.length - 1 - homeIndex
    if (delta > 0) {
      uni.navigateBack({
        delta,
        fail: () => uni.reLaunch({ url: HOME_URL }),
      })
      return
    }
  }
  uni.reLaunch({ url: HOME_URL })
}
