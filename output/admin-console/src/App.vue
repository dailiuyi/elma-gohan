<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, ref } from 'vue'
import MetricCard from './components/MetricCard.vue'
import TrendChart from './components/TrendChart.vue'
import DataBars from './components/DataBars.vue'
import GeoExplorer from './components/GeoExplorer.vue'
import { csvText, downloadFile, formatNumber, formatPercent, isMetric, presetRange, requestJson, RequestError, safeRatio, shanghaiDate, validateRange } from './analytics'
import type { ConsoleState, Metric, Snapshot } from './types'

const sections = [
  { id: 'overview', title: '运营总览', en: 'OVERVIEW', subtitle: '从全局趋势到关键转化，先看清正在发生什么。', icon: 'M3 3h7v7H3z M14 3h7v7h-7z M3 14h7v7H3z M14 14h7v7h-7z' },
  { id: 'usage', title: '使用与留存', en: 'ENGAGEMENT', subtitle: '观察活跃、使用频次与回访，让使用习惯有据可循。', icon: 'M3 18l5-6 5 3 8-11 M3 21h18' },
  { id: 'recommendation', title: '推荐效果', en: 'RECOMMENDATIONS', subtitle: '把推荐、接受、导航与反馈放在一起，理解每一步。', icon: 'M4 4h16l-6 8v7l-4 2V12z' },
  { id: 'geography', title: '用户地图', en: 'GEOGRAPHY', subtitle: '在城市尺度观察使用分布，发现真实的地域差异。', icon: 'M12 22s8-8 8-13a8 8 0 0 0-16 0c0 5 8 13 8 13z M9 9a3 3 0 1 0 6 0a3 3 0 1 0-6 0' },
  { id: 'quality', title: '证据质量', en: 'DATA QUALITY', subtitle: '区分匹配、评分与检索状态，明确推荐依据的边界。', icon: 'M12 2l9 4v6c0 5-9 10-9 10S3 17 3 12V6z M8 12l3 3 5-6' },
  { id: 'data', title: '数据管理', en: 'DATA WORKSPACE', subtitle: '按需刷新、回看快照、导出分析，保留每一次判断的依据。', icon: 'M4 6c0-5 16-5 16 0s-16 5-16 0v12c0 5 16 5 16 0V6 M4 12c0 5 16 5 16 0' },
]
const section = ref('overview')
const activeSection = computed(() => sections.find(item => item.id === section.value)!)
const mobileOpen = ref(false)
const heading = ref<HTMLElement | null>(null)
const menuButton = ref<HTMLButtonElement | null>(null)
const theme = ref<'light' | 'dark'>('light')
const initialRange = presetRange(30)
const from = ref(initialRange.from)
const to = ref(initialRange.to)
const state = ref<ConsoleState | null>(null)
const snapshot = ref<Snapshot | null>(null)
const loading = ref(true)
const posting = ref(false)
const loadingHistory = ref(false)
const connectionError = ref('')
const formError = ref('')
const notice = ref('')
const selectedHistory = ref('')
let pollTimer: ReturnType<typeof setTimeout> | undefined
let disposed = false
let loadSequence = 0
const refreshing = computed(() => posting.value || !!state.value?.refresh.running)
const rangeLabel = computed(() => snapshot.value ? `${snapshot.value.meta.periodStart} — ${snapshot.value.meta.periodEnd}` : '尚未载入快照')
const isHistory = computed(() => !!snapshot.value && !!state.value?.currentId && snapshot.value.id !== state.value.currentId)
const selectionChanged = computed(() => !!snapshot.value && (snapshot.value.meta.periodStart !== from.value || snapshot.value.meta.periodEnd !== to.value))
const compare = computed(() => snapshot.value?.analytics?.comparison)
const behaviorAvailable = computed(() => snapshot.value?.capabilities?.behaviorMetrics !== false)
const trendMetric = ref<'recommendations' | 'activeIds' | 'feedbacks'>('recommendations')
const trendNames = { recommendations: '推荐请求', activeIds: '每日活跃标识', feedbacks: '反馈次数' }
const trendRows = computed(() => (snapshot.value?.daily ?? []).map(row => ({ date: row.metricDate, value: row[trendMetric.value] })))
const activityRows = computed(() => (snapshot.value?.daily ?? []).map(row => ({ date: row.metricDate, value: row.activeIds, secondary: row.newIds })))
const outcomeRows = computed(() => (snapshot.value?.daily ?? []).map(row => ({ date: row.metricDate, value: row.accepts, secondary: row.navigations })))
const feedbackNames: Record<string, string> = { ACCEPT: '接受', ACCEPTED: '接受', LIKE: '喜欢', LIKED: '喜欢', DISLIKE: '不喜欢', DISLIKED: '不喜欢', SATISFIED: '满意', UNSATISFIED: '不满意', GOOD: '满意', BAD: '不满意', SKIP: '跳过' }
const riskNames: Record<string, string> = { LOW: '低风险', MEDIUM: '中风险', HIGH: '高风险', UNKNOWN: '未知风险' }
const behaviorNames: Record<string, string> = { ACCEPT: '接受推荐', NAVIGATE: '发起导航', REROLL: '换一换', DISLIKE: '不感兴趣', FEEDBACK: '提交反馈', VIEW: '查看推荐', SHARE: '分享' }
const statusNames: Record<string, string> = { MATCHED: '已匹配', MATCHED_WITH_RATING: '已匹配且有评分', MATCHED_WITHOUT_RATING: '已匹配但无评分', NO_MATCH: '未匹配', UNAVAILABLE: '暂不可用', PENDING: '待处理', READY: '已就绪', COMPLETE: '已完成', COMPLETED: '已完成', SUCCESS: '成功', FAILED: '失败', QUEUED: '排队中', RUNNING: '处理中', RETRYING: '重试中', EXPIRED: '已过期', NOT_FOUND: '未找到' }
const tableSearch = ref('')
const tables = computed(() => Object.entries(snapshot.value?.tableRows ?? {}).filter(([name]) => name.toLowerCase().includes(tableSearch.value.toLowerCase())).sort((a, b) => b[1] - a[1]))
const quality = computed(() => snapshot.value?.analytics?.quality)
const mappingMatched = computed(() => quality.value?.mappingStatuses?.find(item => item.status === 'MATCHED')?.count)
const mappingUnrated = computed(() => quality.value?.mappingStatuses?.find(item => item.status === 'MATCHED_WITHOUT_RATING')?.count ?? (isMetric(mappingMatched.value) && isMetric(quality.value?.mappingsWithRatings) ? Math.max(0, mappingMatched.value - quality.value!.mappingsWithRatings!) : null))
const periodFeedbackCount = computed(() => snapshot.value?.feedback.reduce((total, row) => total + row.feedbackCount, 0))
const heatmap = computed(() => {
  const values = new Map<string, number>()
  const rows = snapshot.value?.analytics?.heatmap
  if (!rows) return values
  for (let day = 1; day <= 7; day++) for (let hour = 0; hour < 24; hour++) values.set(`${day}-${hour}`, 0)
  rows.forEach(row => values.set(`${row.weekday}-${row.hour}`, row.requests))
  return values
})
const heatMax = computed(() => Math.max(1, ...heatmap.value.values()))
const weekdayNames = ['一', '二', '三', '四', '五', '六', '日']
const retentionColumns = [{ key: 'day1', label: '次日' }, { key: 'day7', label: '第 7 天' }, { key: 'day14', label: '第 14 天' }, { key: 'day30', label: '第 30 天' }] as const
const frequency = computed(() => (snapshot.value?.analytics?.frequency ?? []).map(item => ({ label: item.label, value: item.users })))
const funnelItems = computed(() => {
  const f = snapshot.value?.funnel
  return [{ label: '推荐会话', value: f?.recommendationSessions }, { label: '接受推荐', value: f?.acceptedSessions }, { label: '发起导航', value: f?.navigatedSessions }, { label: '提交反馈', value: f?.feedbackSessions }].map(item => ({ ...item, rate: safeRatio(item.value, f?.recommendationSessions) }))
})
const refreshError = computed(() => state.value?.refresh.error || '')
const insights = computed(() => {
  const data = snapshot.value
  if (!data) return []
  const rows: { label: string; text: string; target: string }[] = []
  if (isMetric(data.overview.periodActiveIds) && data.overview.periodActiveIds > 0) rows.push({ label: '使用深度', text: `本期每个活跃匿名标识平均产生 ${formatNumber(safeRatio(data.overview.periodRecommendations, data.overview.periodActiveIds), 1)} 次推荐请求。`, target: 'usage' })
  if (isMetric(data.funnel.acceptanceRate)) rows.push({ label: '推荐转化', text: `本期接受率为 ${formatPercent(data.funnel.acceptanceRate)}；接受动作代表行为意向，不等同于实际到店。`, target: 'recommendation' })
  if (data.locations?.points?.length) rows.push({ label: '地域分布', text: `当前快照覆盖 ${data.locations.points.length} 个城市；城市内人数按主要活动城市归属。`, target: 'geography' })
  if (isMetric(mappingUnrated.value)) rows.push({ label: '证据缺口', text: `${formatNumber(mappingUnrated.value)} 条已匹配映射尚无评分，可在证据质量中与未匹配分开观察。`, target: 'quality' })
  return rows
})

function dateTime(value?: string | null): string {
  if (!value) return '—'
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? value : new Intl.DateTimeFormat('zh-CN', { timeZone: 'Asia/Shanghai', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', second: '2-digit', hour12: false }).format(date)
}
function setTheme(value: 'light' | 'dark') {
  theme.value = value
  document.documentElement.dataset.theme = value
  try { localStorage.setItem('elma-console-theme', value) } catch { /* Storage is optional. */ }
}
async function navigate(id: string) {
  section.value = id
  mobileOpen.value = false
  await nextTick()
  heading.value?.focus()
}
function closeMenu(event: KeyboardEvent) {
  if (event.key === 'Escape' && mobileOpen.value) { mobileOpen.value = false; menuButton.value?.focus() }
}
function setPreset(days: number) {
  const range = presetRange(days)
  from.value = range.from
  to.value = range.to
  formError.value = ''
}
async function loadSnapshot(id?: string): Promise<void> {
  const sequence = ++loadSequence
  const result = await requestJson<Snapshot>(`snapshot${id ? `?id=${encodeURIComponent(id)}` : ''}`)
  if (disposed || sequence !== loadSequence) return
  snapshot.value = result
  selectedHistory.value = result.id
}
function schedulePoll() {
  clearTimeout(pollTimer)
  if (!disposed) pollTimer = setTimeout(() => void pollRefresh(), 2000)
}
async function pollRefresh() {
  try {
    const next = await requestJson<ConsoleState>('state')
    if (disposed) return
    state.value = next
    connectionError.value = ''
    if (next.refresh.running) { schedulePoll(); return }
    if (next.refresh.error) { notice.value = ''; return }
    if (next.currentId) {
      await loadSnapshot()
      notice.value = '最新快照已载入，所有分析已同步更新。'
    }
  } catch (error) {
    if (!disposed) connectionError.value = error instanceof Error ? error.message : '状态同步失败，请重新连接。'
  }
}
async function connect() {
  clearTimeout(pollTimer)
  loading.value = true
  connectionError.value = ''
  try {
    const next = await requestJson<ConsoleState>('state')
    if (disposed) return
    state.value = next
    if (next.currentId) {
      await loadSnapshot()
      if (snapshot.value) { from.value = snapshot.value.meta.periodStart; to.value = snapshot.value.meta.periodEnd }
    }
    if (next.refresh.running) schedulePoll()
  } catch (error) {
    if (!disposed) connectionError.value = error instanceof Error ? error.message : '数据载入失败，请重试。'
  } finally { loading.value = false }
}
async function refreshData() {
  if (refreshing.value) return
  formError.value = validateRange(from.value, to.value) ?? ''
  if (formError.value) return
  if (!state.value?.csrfToken) { connectionError.value = '连接尚未准备好，请先重新连接数据服务。'; return }
  posting.value = true
  connectionError.value = ''
  notice.value = ''
  try {
    await requestJson<unknown>('refresh', { method: 'POST', headers: { 'Content-Type': 'application/json', 'X-ELMA-CSRF': state.value.csrfToken }, body: JSON.stringify({ from: from.value, to: to.value }) })
    state.value.refresh = { running: true, startedAt: new Date().toISOString(), error: null }
    notice.value = `正在生成 ${from.value} — ${to.value} 的快照，通常需要几十秒。`
    schedulePoll()
  } catch (error) {
    if (error instanceof RequestError && error.status === 409) { state.value.refresh.running = true; schedulePoll() }
    else connectionError.value = error instanceof Error ? error.message : '刷新未能开始，请重试。'
  } finally { posting.value = false }
}
async function selectHistory(id: string) {
  if (!id || refreshing.value) return
  loadingHistory.value = true
  connectionError.value = ''
  try { await loadSnapshot(id); notice.value = id === state.value?.currentId ? '已返回最新快照。' : '正在查看历史快照，所有图表与导出均使用这份快照。' }
  catch (error) { connectionError.value = error instanceof Error ? error.message : '历史快照载入失败。'; selectedHistory.value = snapshot.value?.id ?? '' }
  finally { loadingHistory.value = false }
}
function exportJson() {
  if (!snapshot.value) return
  downloadFile(`elma-snapshot-${snapshot.value.meta.periodStart}-${snapshot.value.meta.periodEnd}.json`, JSON.stringify(snapshot.value, null, 2), 'application/json;charset=utf-8')
}
function exportCsv() {
  const data = snapshot.value
  if (!data) return
  let rows: Record<string, unknown>[]
  if (section.value === 'quality') rows = (data.analytics?.quality?.mappingStatuses ?? []).map(row => ({ 类型: '映射状态', 状态: row.status, 数量: row.count })).concat((data.analytics?.quality?.deepStatuses ?? []).map(row => ({ 类型: `深度证据/${row.source}`, 状态: row.status, 数量: row.count })))
  else if (section.value === 'geography') rows = (data.locations?.points ?? []).map(row => ({ 城市代码: row.code, 城市: row.label, 省份: row.province, 匿名标识: row.anonymousIds, 推荐请求: row.requests, 低样本: row.lowSample ? '是' : '否' }))
  else if (section.value === 'data') rows = Object.entries(data.tableRows ?? {}).map(([name, count]) => ({ 数据表: name, 行数: count, 统计范围: '快照时点全库' }))
  else rows = data.daily.map(row => ({ 日期: row.metricDate, 推荐请求: row.recommendations, 当日活跃标识: row.activeIds, 新增标识: row.newIds, 接受: row.accepts, 导航: row.navigations, 换一换: row.rerolls, 反馈: row.feedbacks, 不感兴趣: row.dislikes }))
  const context = { 区间开始: data.meta.periodStart, 区间结束: data.meta.periodEnd, 快照时间: data.meta.snapshotAt }
  downloadFile(`elma-${section.value}-${data.meta.periodStart}-${data.meta.periodEnd}.csv`, csvText(rows.length ? rows.map(row => ({ ...context, ...row })) : [{ ...context, 说明: '当前快照无可导出记录' }]), 'text/csv;charset=utf-8')
}
function exportRetention() {
  const data = snapshot.value
  if (!data) return
  downloadFile(`elma-retention-${data.meta.periodStart}-${data.meta.periodEnd}.csv`, csvText((data.analytics?.retention ?? []).map(row => ({ 首访日期: row.cohortDate, 新增标识: row.cohortSize, 次日留存: row.day1, 第7天留存: row.day7, 第14天留存: row.day14, 第30天留存: row.day30, 说明: '比例0到1；空值表示尚未满足观察期', 快照时间: data.meta.snapshotAt }))), 'text/csv;charset=utf-8')
}
function heatStyle(value: number | undefined) {
  return { background: value == null ? 'var(--surface-soft)' : `color-mix(in srgb, var(--accent) ${Math.round(value / heatMax.value * 80 + 8)}%, var(--surface-soft))` }
}
function retentionStyle(value: Metric) {
  return isMetric(value) ? { background: `color-mix(in srgb, var(--teal) ${Math.round(Math.min(value, 1) * 35 + 4)}%, var(--surface))` } : undefined
}

onMounted(() => {
  try { setTheme(localStorage.getItem('elma-console-theme') === 'dark' ? 'dark' : 'light') } catch { setTheme('light') }
  document.addEventListener('keydown', closeMenu)
  void connect()
})
onUnmounted(() => { disposed = true; clearTimeout(pollTimer); document.removeEventListener('keydown', closeMenu) })
</script>

<template>
  <div class="console-shell">
    <a class="skip-link" href="#main-content">跳到主要内容</a>
    <button v-if="mobileOpen" class="sidebar-backdrop" aria-label="关闭导航" @click="mobileOpen = false"></button>
    <aside id="console-sidebar" class="sidebar" :class="{ 'is-open': mobileOpen }">
      <a class="brand" href="/console/" aria-label="ELMA 数据控制台首页"><span class="brand-symbol" aria-hidden="true">e<span>·</span></span><span><strong>ELMA</strong><small>数据控制台</small></span><span class="brand-version">02</span></a>
      <div class="sidebar-caption">工作空间</div>
      <nav class="main-nav" aria-label="分析主题">
        <button v-for="item in sections" :key="item.id" :class="{ active: section === item.id }" :aria-current="section === item.id ? 'page' : undefined" @click="navigate(item.id)"><svg viewBox="0 0 24 24" aria-hidden="true"><path :d="item.icon" /></svg><span>{{ item.title }}</span><span v-if="section === item.id" class="nav-dot" aria-hidden="true"></span></button>
      </nav>
      <div class="sidebar-bottom">
        <div class="workspace-note"><span class="status-dot"></span><strong>只读分析空间</strong><p>聚合数据 · 上海时区<br>匿名标识不等同于真实人数</p></div>
        <a class="legacy-link" href="/console/legacy/">旧版导览 <span aria-hidden="true">↗</span><small>产品演示 / 表结构 / 连接指引</small></a>
        <div class="sidebar-footer"><span>ELMA INSIGHTS</span><button class="theme-toggle" :aria-label="theme === 'light' ? '切换为深色模式' : '切换为浅色模式'" @click="setTheme(theme === 'light' ? 'dark' : 'light')"><span aria-hidden="true">{{ theme === 'light' ? '◐' : '☼' }}</span>{{ theme === 'light' ? '深色' : '浅色' }}</button></div>
      </div>
    </aside>

    <div class="main-shell">
      <header class="topbar"><div class="breadcrumbs"><button ref="menuButton" class="menu-toggle" aria-label="打开导航" aria-controls="console-sidebar" :aria-expanded="mobileOpen" @click="mobileOpen = !mobileOpen">☰</button><span>工作空间</span><span aria-hidden="true">/</span><strong>{{ activeSection.title }}</strong></div><div class="topbar-status"><span class="status-dot" :class="{ 'status-dot--busy': refreshing, 'status-dot--error': !!connectionError }"></span>{{ refreshing ? '数据刷新中' : connectionError ? '连接待恢复' : snapshot ? '快照已就绪' : loading ? '正在连接' : '等待首次快照' }}</div></header>

      <main id="main-content" class="main-content" :aria-busy="loading || loadingHistory">
        <div class="page-heading"><div><p class="eyebrow">{{ activeSection.en }} <span> / ELMA</span></p><h1 ref="heading" tabindex="-1">{{ activeSection.title }}</h1><p class="page-subtitle">{{ activeSection.subtitle }}</p></div><div class="heading-actions"><button class="button button--subtle" :disabled="!snapshot" @click="exportCsv"><span aria-hidden="true">↓</span> 导出 CSV</button><button class="button button--primary" :disabled="refreshing || loading || !state" @click="refreshData"><span :class="{ spinning: refreshing }" aria-hidden="true">↻</span>{{ refreshing ? '拉取中…' : '手动拉取数据' }}</button></div></div>

        <form class="date-toolbar" @submit.prevent="refreshData"><div class="date-fields"><label>开始日期<input v-model="from" type="date" name="from" :max="to || shanghaiDate()" :disabled="refreshing" required /></label><span class="date-separator" aria-hidden="true">—</span><label>结束日期<input v-model="to" type="date" name="to" :min="from" :max="shanghaiDate()" :disabled="refreshing" required /></label></div><div class="date-presets"><button v-for="days in [7, 30, 90]" :key="days" type="button" :disabled="refreshing" @click="setPreset(days)">近 {{ days }} 天</button></div><button class="button button--outline" type="submit" :disabled="refreshing || loading || !state">应用区间</button><span class="date-timezone">UTC+8 · 最多 90 天</span></form>
        <p v-if="formError" class="form-error" role="alert">{{ formError }}</p>
        <div v-if="connectionError" class="alert alert--error" role="alert"><div><strong>连接需要重试</strong><p>{{ connectionError }}</p></div><button class="button button--outline" :disabled="loading" @click="connect">重新连接</button></div>
        <div v-if="refreshError" class="alert alert--error" role="alert"><div><strong>本次刷新失败，之前的快照已保留</strong><p>{{ refreshError }}</p></div><button class="button button--outline" :disabled="refreshing || loading" @click="refreshData">再次拉取</button></div>
        <div v-if="refreshing" class="alert alert--info" role="status"><span class="loading-ring" aria-hidden="true"></span><div><strong>正在从数据库生成只读聚合快照</strong><p>{{ snapshot ? `页面继续展示 ${rangeLabel} 的已有快照；刷新成功后自动更新。` : '首次快照生成后将自动显示分析结果。' }}最长等待约 3 分钟。</p></div></div>
        <p v-else-if="notice" class="notice" role="status">{{ notice }}</p>

        <div v-if="snapshot" class="snapshot-strip"><div><span class="snapshot-tag" :class="{ 'snapshot-tag--history': isHistory }">{{ isHistory ? '历史快照' : '当前快照' }}</span><strong>{{ rangeLabel }}</strong><span v-if="selectionChanged" class="pending-range">上方选择尚未应用到图表</span></div><span>更新于 {{ dateTime(snapshot.meta.snapshotAt) }} · 上海<span v-if="snapshot.meta.sourceMode === 'fixture'" class="fixture-tag">演示数据</span></span></div>
        <div v-if="snapshot?.meta.warnings?.length" class="warnings"><details><summary>{{ snapshot.meta.warnings.length }} 项数据口径提示</summary><ul><li v-for="warning in snapshot.meta.warnings" :key="warning">{{ warning }}</li></ul></details></div>

        <div v-if="loading && !snapshot" class="loading-state" role="status"><span class="loading-ring"></span><h2>正在连接数据空间</h2><p>载入当前快照与刷新状态…</p></div>
        <section v-else-if="!snapshot" class="empty-state"><span class="empty-symbol" aria-hidden="true">◫</span><p class="eyebrow">READY WHEN YOU ARE</p><h2>先创建一份数据快照</h2><p>选择上方日期区间，点击「手动拉取数据」。<br>这里会生成使用、推荐、地域与证据的完整分析。</p><button class="button button--primary" :disabled="refreshing || !state" @click="refreshData">{{ refreshing ? '正在生成首份快照…' : '生成首份快照' }}</button></section>

        <template v-else>
          <template v-if="section === 'overview'">
            <div class="section-kicker"><span>本期关键指标</span><small v-if="compare">环比 {{ compare.previousFrom }} — {{ compare.previousTo }} · 同等天数</small><small v-else>前期对比暂不可用</small></div>
            <div class="metric-grid"><MetricCard label="推荐请求" :value="snapshot.overview.periodRecommendations" :previous="compare?.previous.requests" compare accent hint="本期发起的推荐请求总量" /><MetricCard label="活跃匿名标识" :value="snapshot.overview.periodActiveIds" :previous="compare?.previous.activeIds" compare hint="整个区间跨日去重" /><MetricCard label="新增匿名标识" :value="snapshot.overview.periodNewIds" :previous="compare?.previous.newIds" compare hint="首次推荐发生在本期" /><MetricCard label="接受率" :value="snapshot.funnel.acceptanceRate" :previous="compare?.previous.acceptanceRate" compare rate hint="有接受行为的会话 / 推荐会话" /></div>
            <div class="content-grid content-grid--wide"><section class="panel"><div class="panel-heading"><div><h2>使用趋势</h2><p>逐日观察变化，精确数据可展开查看</p></div><label class="sr-only" for="trend-metric">趋势指标</label><select id="trend-metric" v-model="trendMetric"><option value="recommendations">推荐请求</option><option value="activeIds">活跃标识</option><option value="feedbacks">反馈次数</option></select></div><TrendChart :rows="trendRows" :label="trendNames[trendMetric]" /></section><section class="panel insight-panel"><div class="panel-heading"><div><p class="eyebrow">AT A GLANCE</p><h2>本期观察</h2></div><span class="insight-spark" aria-hidden="true">✳</span></div><button v-for="item in insights.slice(0, 3)" :key="item.label" class="insight-item" @click="navigate(item.target)"><span>{{ item.label }} <i aria-hidden="true">↗</i></span><p>{{ item.text }}</p></button><p v-if="!insights.length" class="muted">当前数据不足以形成观察，拉取更多数据后查看。</p><p class="fine-print">基于聚合数据的描述，不作因果推断。</p></section></div>
            <div class="content-grid"><section class="panel"><div class="panel-heading"><div><h2>关键行为</h2><p>各行为会话独立统计，可能重叠</p></div><button class="text-button" @click="navigate('recommendation')">查看推荐效果 ↗</button></div><div class="funnel"><div v-for="(item, index) in funnelItems" :key="item.label" class="funnel-row"><span class="funnel-step">0{{ index + 1 }}</span><span>{{ item.label }}</span><strong>{{ formatNumber(item.value) }}</strong><span class="funnel-rate">{{ formatPercent(item.rate) }}</span><div class="funnel-track" aria-hidden="true"><span :style="{ width: `${Math.min(100, (item.rate ?? 0) * 100)}%` }"></span></div></div></div><p class="fine-print">占比均以推荐会话为分母。此处不假设严格的先后漏斗关系。</p></section><section class="panel"><div class="panel-heading"><div><h2>推荐品类</h2><p>按推荐记录统计 · 展示前 8 项</p></div></div><DataBars :items="snapshot.categories.map(row => ({ label: row.category || '未分类', value: row.recommendationCount }))" :max-rows="8" /></section></div>
            <div class="context-banner"><span class="context-banner-icon" aria-hidden="true">◉</span><div><strong>累计数据资产</strong><p>截至快照时点的全库统计，不受本期区间筛选影响。</p></div><div><strong>{{ formatNumber(snapshot.overview.totalAnonymousIds) }}</strong><span>累计匿名标识</span></div><div><strong>{{ formatNumber(snapshot.overview.totalRestaurants) }}</strong><span>收录餐厅</span></div><div><strong>{{ formatNumber(snapshot.overview.totalRecommendations) }}</strong><span>累计推荐</span></div></div>
          </template>

          <template v-else-if="section === 'usage'">
            <div class="metric-grid"><MetricCard label="本期活跃标识" :value="snapshot.overview.periodActiveIds" :previous="compare?.previous.activeIds" compare accent hint="区间去重，不能相加每日活跃数" /><MetricCard label="本期新增标识" :value="snapshot.overview.periodNewIds" :previous="compare?.previous.newIds" compare hint="首次发起推荐的匿名标识" /><MetricCard label="老用户回访标识" :value="snapshot.overview.periodReturningIds" hint="本期活跃且首次推荐早于本期" /><MetricCard label="平均推荐频次" :value="safeRatio(snapshot.overview.periodRecommendations, snapshot.overview.periodActiveIds)" :decimal="1" hint="本期请求 / 本期去重活跃标识" /></div>
            <div class="content-grid content-grid--wide"><section class="panel"><div class="panel-heading"><div><h2>活跃与新增</h2><p>日活跃是当日去重；同一标识可出现在多天</p></div></div><TrendChart :rows="activityRows" label="日活跃标识" secondary-label="日新增标识" /></section><section class="panel"><div class="panel-heading"><div><h2>使用频次分布</h2><p>本期每个标识的推荐请求次数</p></div></div><DataBars :items="frequency" unit="个" /><p class="fine-print">分桶按整个区间计算，每个活跃标识只计入一个分桶。</p></section></div>
            <section class="panel"><div class="panel-heading"><div><h2>一周使用节律</h2><p>上海时间 · 同一星期与小时在区间内累计的推荐请求</p></div><div class="heat-legend"><span>少</span><i></i><span>多</span></div></div><div v-if="heatmap.size" class="heatmap-scroll"><div class="activity-heatmap" role="table" aria-label="按星期与小时的推荐请求热力表"><div class="heatmap-hour-row" role="row"><span role="columnheader">时段</span><span v-for="hour in 24" :key="hour" role="columnheader">{{ hour - 1 }}</span></div><div v-for="(day, dayIndex) in weekdayNames" :key="day" class="heatmap-row" role="row"><span role="rowheader">周{{ day }}</span><span v-for="hour in 24" :key="hour" class="heat-cell" role="cell" tabindex="0" :style="heatStyle(heatmap.get(`${dayIndex + 1}-${hour - 1}`))" :aria-label="`周${day} ${hour - 1}:00，${heatmap.has(`${dayIndex + 1}-${hour - 1}`) ? `${heatmap.get(`${dayIndex + 1}-${hour - 1}`)} 次推荐` : '无数据'}`"><span class="heat-tooltip">周{{ day }} {{ hour - 1 }}:00 · {{ formatNumber(heatmap.get(`${dayIndex + 1}-${hour - 1}`)) }} 次</span></span></div></div></div><div v-else class="empty-inline"><p>当前快照暂无按小时统计</p></div></section>
            <section class="panel"><div class="panel-heading"><div><h2>首访群组留存</h2><p>首次推荐后，精确第 N 天再次发起推荐的比例</p></div><button class="text-button" :disabled="!snapshot.analytics?.retention?.length" @click="exportRetention">导出留存 CSV ↓</button></div><div v-if="snapshot.analytics?.retention?.length" class="table-scroll retention-scroll"><table class="retention-table"><caption class="sr-only">首访日期群组与次日、第7天、第14天、第30天留存</caption><thead><tr><th scope="col">首次推荐日期</th><th scope="col">群组标识数</th><th v-for="column in retentionColumns" :key="column.key" scope="col">{{ column.label }}</th></tr></thead><tbody><tr v-for="row in snapshot.analytics.retention" :key="row.cohortDate"><th scope="row">{{ row.cohortDate }}</th><td>{{ formatNumber(row.cohortSize) }}<span v-if="row.cohortSize < 10" class="tiny-tag">低样本</span></td><td v-for="column in retentionColumns" :key="column.key" :style="retentionStyle(row[column.key])"><span v-if="isMetric(row[column.key])">{{ formatPercent(row[column.key]) }}</span><span v-else class="observation-pending" title="该群组尚未满足此留存周期的完整观察期">待观察</span></td></tr></tbody></table></div><div v-else class="empty-inline"><p>本期暂无首访群组数据</p></div><p class="fine-print">「待观察」不是 0%。不足 10 个标识的群组标注为低样本，比例波动较大。匿名标识可能因清除缓存或更换设备重建。首次推荐以数据库当前保留记录中的最早请求为准。</p></section>
          </template>

          <template v-else-if="section === 'recommendation'">
            <div v-if="!behaviorAvailable" class="alert alert--info"><p>当前数据库尚未提供完整行为统计，相关指标显示为「—」。</p></div>
            <div class="metric-grid"><MetricCard label="接受率" :value="snapshot.funnel.acceptanceRate" :previous="compare?.previous.acceptanceRate" compare rate accent hint="接受会话 / 推荐会话" /><MetricCard label="导航率" :value="snapshot.funnel.navigationRate" rate hint="导航会话 / 推荐会话" /><MetricCard label="反馈率" :value="snapshot.funnel.feedbackRate" :previous="compare?.previous.feedbackRate" compare rate hint="反馈会话 / 推荐会话" /><MetricCard label="平均候选数量" :value="snapshot.overview.averageCandidateCount" :decimal="1" hint="候选数量不代表匹配准确率" /></div>
            <div class="content-grid content-grid--wide"><section class="panel"><div class="panel-heading"><div><h2>接受与导航趋势</h2><p>行为事件每日数量，区间转化率使用去重会话</p></div></div><TrendChart :rows="outcomeRows" label="接受事件" secondary-label="导航事件" /></section><section class="panel"><div class="panel-heading"><div><h2>反馈分布</h2><p>按反馈发生日期统计 · 未反馈不视为不满意</p></div></div><DataBars :items="snapshot.feedback.map(row => ({ label: feedbackNames[row.result] || row.result, value: row.feedbackCount }))" /><div class="inline-stat"><span>本期反馈记录总数</span><strong>{{ formatNumber(periodFeedbackCount) }}</strong></div></section></div>
            <div class="content-grid"><section class="panel"><div class="panel-heading"><div><h2>行为分布</h2><p>比较事件量与去重会话量</p></div></div><div v-if="snapshot.behaviors.length" class="table-scroll"><table><thead><tr><th scope="col">行为</th><th scope="col">事件数</th><th scope="col">涉及会话</th></tr></thead><tbody><tr v-for="row in snapshot.behaviors" :key="row.behaviorType"><th scope="row">{{ behaviorNames[row.behaviorType] || row.behaviorType }}</th><td>{{ formatNumber(row.eventCount) }}</td><td>{{ formatNumber(row.sessionCount) }}</td></tr></tbody></table></div><div v-else class="empty-inline"><p>当前区间暂无行为记录</p></div></section><section class="panel"><div class="panel-heading"><div><h2>推荐风险分布</h2><p>系统风险标签，不是食品安全结论</p></div></div><DataBars :items="snapshot.risks.map(row => ({ label: riskNames[row.riskLevel] || row.riskLevel, value: row.recommendationCount, tone: row.riskLevel === 'HIGH' ? 'var(--warning)' : undefined }))" /></section></div>
            <section class="panel"><div class="panel-heading"><div><h2>算法与选择模式</h2><p>了解推荐流量的实际组成；数量差异不代表效果提升</p></div></div><div v-if="snapshot.algorithms.length" class="table-scroll"><table><thead><tr><th scope="col">算法版本</th><th scope="col">选择模式</th><th scope="col">推荐数</th><th scope="col">本期占比</th></tr></thead><tbody><tr v-for="row in snapshot.algorithms" :key="`${row.algorithmVersion}-${row.selectionMode}`"><th scope="row"><code>{{ row.algorithmVersion || '未记录' }}</code></th><td>{{ row.selectionMode || '未记录' }}</td><td>{{ formatNumber(row.recommendationCount) }}</td><td>{{ formatPercent(safeRatio(row.recommendationCount, snapshot.overview.periodRecommendations)) }}</td></tr></tbody></table></div><div v-else class="empty-inline"><p>当前区间暂无算法分布</p></div></section>
            <section v-if="snapshot.shadow" class="panel"><div class="panel-heading"><div><h2>Shadow 实验观察</h2><p>观察对照快照覆盖与首选变化，不把差异视为效果提升</p></div><span class="state-badge">{{ snapshot.shadow.available ? '已提供' : '暂不可用' }}</span></div><template v-if="snapshot.shadow.available"><div class="health-grid shadow-summary"><div><span>快照覆盖率</span><strong>{{ formatPercent(snapshot.shadow.summary?.coverageRate) }}</strong></div><div><span>已保存快照</span><strong>{{ formatNumber(snapshot.shadow.summary?.totalSnapshots) }}</strong></div><div><span>首选相同</span><strong>{{ formatNumber(snapshot.shadow.summary?.sameFirstChoice) }}</strong></div><div><span>首选改变</span><strong>{{ formatNumber(snapshot.shadow.summary?.changedFirstChoice) }}</strong></div></div><div v-if="snapshot.shadow.variants?.length" class="table-scroll shadow-table"><table><thead><tr><th scope="col">实验 / 分组</th><th scope="col">线上算法</th><th scope="col">对照算法</th><th scope="col">快照数</th></tr></thead><tbody><tr v-for="row in snapshot.shadow.variants" :key="`${row.experimentKey}-${row.variant}-${row.servedRecommendationAlgorithmVersion}-${row.shadowRecommendationAlgorithmVersion}`"><th scope="row">{{ row.experimentKey }} / {{ row.variant }}</th><td><code>{{ row.servedRecommendationAlgorithmVersion || '未记录' }}</code></td><td><code>{{ row.shadowRecommendationAlgorithmVersion || '未记录' }}</code></td><td>{{ formatNumber(row.snapshotCount) }}</td></tr></tbody></table></div></template><p class="fine-print">{{ snapshot.shadow.note || '实验覆盖表示已保存的对照快照，不能直接推断算法胜率。' }}</p></section>
          </template>

          <template v-else-if="section === 'geography'"><GeoExplorer :locations="snapshot.locations" :range-label="rangeLabel" /></template>

          <template v-else-if="section === 'quality'">
            <div class="method-note quality-scope"><strong>证据质量以快照时点的缓存与任务状态为准</strong><p>这些是数据完整度与服务状态指标，不按推荐日期区间过滤，也不是经过人工标注验证的匹配准确率。</p></div>
            <div class="metric-grid"><MetricCard label="POI 映射记录" :value="quality?.totalMappings" accent hint="快照时点的映射缓存总数" /><MetricCard label="已匹配且有评分" :value="quality?.mappingsWithRatings" hint="存在匹配，并包含百度评分" /><MetricCard label="已匹配但无评分" :value="mappingUnrated" hint="单独保留，不计作未匹配" /><MetricCard label="新鲜映射记录" :value="quality?.freshMappings" hint="以数据服务的有效期口径统计" /></div>
            <div class="content-grid"><section class="panel"><div class="panel-heading"><div><h2>映射状态分布</h2><p>MATCHED 与 NO_MATCH 分开统计</p></div></div><DataBars :items="(quality?.mappingStatuses || []).map(row => ({ label: `${statusNames[row.status] || row.status} · ${row.status}`, value: row.count }))" /><div class="method-note"><strong>评分缺失需要单独解释</strong><p>已匹配地点可能没有平台评分；暂不可用通常与服务或配额有关，不能作为未匹配证据。</p></div></section><section class="panel"><div class="panel-heading"><div><h2>检索与任务健康</h2><p>当前队列状态，不表示本期新增任务量</p></div></div><div class="health-grid"><div><span>排队任务</span><strong>{{ formatNumber(snapshot.evidenceReliability.queuedTasks) }}</strong></div><div><span>重试任务</span><strong>{{ formatNumber(snapshot.evidenceReliability.retryingTasks) }}</strong></div><div><span>新鲜检索缓存</span><strong>{{ formatNumber(snapshot.evidenceReliability.freshQueries) }}</strong></div><div><span>检索冷却状态</span><strong class="health-status" :class="{ 'text-warning': snapshot.evidenceReliability.coolingDown === true }">{{ snapshot.evidenceReliability.coolingDown === true ? '冷却中' : snapshot.evidenceReliability.coolingDown === false ? '未冷却' : '未提供' }}</strong></div></div></section></div>
            <section class="panel"><div class="panel-heading"><div><h2>深度证据来源</h2><p>按来源与处理状态观察覆盖和失败</p></div></div><div v-if="quality?.deepStatuses?.length" class="table-scroll"><table><thead><tr><th scope="col">证据来源</th><th scope="col">状态</th><th scope="col">记录数量</th></tr></thead><tbody><tr v-for="row in quality.deepStatuses" :key="`${row.source}-${row.status}`"><th scope="row">{{ row.source || '未记录来源' }}</th><td><span class="state-badge">{{ statusNames[row.status] || row.status }}</span><code class="status-code">{{ row.status }}</code></td><td>{{ formatNumber(row.count) }}</td></tr></tbody></table></div><div v-else class="empty-inline"><p>当前快照没有深度证据状态数据</p></div></section>
          </template>

          <template v-else-if="section === 'data'">
            <div class="content-grid content-grid--wide"><section class="panel"><div class="panel-heading"><div><p class="eyebrow">SNAPSHOT CONTROL</p><h2>数据刷新与快照</h2><p>浏览器发起任务，服务端执行只读聚合查询</p></div><span class="state-badge">{{ refreshing ? '任务运行中' : '空闲' }}</span></div><dl class="metadata-grid"><div><dt>当前数据区间</dt><dd>{{ rangeLabel }}</dd></div><div><dt>快照生成时间</dt><dd>{{ dateTime(snapshot.meta.snapshotAt) }}</dd></div><div><dt>最近任务开始</dt><dd>{{ dateTime(state?.refresh.startedAt) }}</dd></div><div><dt>最近任务完成</dt><dd>{{ dateTime(state?.refresh.finishedAt) }}</dd></div><div><dt>只读事务验证</dt><dd>{{ snapshot.meta.readOnlyVerified ? '已通过' : '未确认' }}</dd></div><div><dt>查询统计</dt><dd>{{ formatNumber(snapshot.meta.queryCount) }} 次 · {{ formatNumber(snapshot.meta.queryDurationMs) }} ms</dd></div></dl><div class="panel-actions"><button class="button button--primary" :disabled="refreshing || loading" @click="refreshData">{{ refreshing ? '正在拉取…' : '拉取所选区间数据' }}</button><button class="button button--outline" @click="exportJson">导出完整 JSON</button></div><p class="fine-print">仅保留最近 12 份快照。单次区间最多 90 天，并生成同长度前期对比。失败不会覆盖旧快照。</p></section><section class="panel"><div class="panel-heading"><div><h2>分析口径</h2><p>准确理解数字，避免过度解读</p></div></div><ul class="definition-list"><li><strong>匿名标识 ≠ 人数</strong><span>设备、浏览器或缓存变化会重建标识。</span></li><li><strong>活跃标识跨日去重</strong><span>区间活跃不能由每日活跃简单相加。</span></li><li><strong>接受与导航 ≠ 到店</strong><span>这里量化行为意向，不推断实际消费。</span></li><li><strong>空值 ≠ 0</strong><span>缺失数据显示「—」，留存不足观察期显示「待观察」。</span></li></ul></section></div>
            <section class="panel"><div class="panel-heading"><div><h2>历史快照</h2><p>选择一份快照，全站分析与导出同步切换</p></div><span class="muted">{{ state?.history.length ?? 0 }} / 12 份</span></div><div v-if="state?.history.length" class="table-scroll"><table><thead><tr><th scope="col">生成时间（上海）</th><th scope="col">分析区间</th><th scope="col">状态</th><th scope="col">操作</th></tr></thead><tbody><tr v-for="item in state.history" :key="item.id" :class="{ 'selected-row': item.id === snapshot.id }"><th scope="row">{{ dateTime(item.snapshotAt) }}</th><td>{{ item.from }} — {{ item.to }}</td><td><span v-if="item.id === state.currentId" class="state-badge">最新</span><span v-else class="muted">历史</span></td><td><button class="text-button" :disabled="item.id === snapshot.id || loadingHistory || refreshing" @click="selectHistory(item.id)">{{ item.id === snapshot.id ? '正在查看' : '查看快照' }}</button></td></tr></tbody></table></div><div v-else class="empty-inline"><p>当前没有历史快照记录</p></div></section>
            <section class="panel"><div class="panel-heading"><div><h2>数据表规模</h2><p>快照时点全库行数，不能视为所选区间新增量</p></div><label class="search-field"><span class="sr-only">筛选数据表</span><input v-model="tableSearch" type="search" placeholder="搜索数据表…" /></label></div><div v-if="tables.length" class="table-scroll"><table><thead><tr><th scope="col">数据表</th><th scope="col">记录行数</th><th scope="col">相对规模</th></tr></thead><tbody><tr v-for="[name, count] in tables" :key="name"><th scope="row"><code>{{ name }}</code></th><td>{{ formatNumber(count) }}</td><td><span class="table-size-bar" aria-hidden="true"><i :style="{ width: `${count / Math.max(1, ...Object.values(snapshot.tableRows)) * 100}%` }"></i></span></td></tr></tbody></table></div><div v-else class="empty-inline"><p>{{ tableSearch ? '没有匹配的数据表' : '当前快照暂无数据表统计' }}</p></div></section>
            <div class="resource-grid"><a href="/console/legacy/"><strong>旧版导览 <span aria-hidden="true">↗</span></strong><p>产品流程演示、数据库关系图与人工连接指引</p></a><a href="/console/THIRD_PARTY_NOTICES.md" target="_blank" rel="noopener"><strong>数据来源与第三方声明 <span aria-hidden="true">↗</span></strong><p>地图与开源组件的来源、许可和使用说明</p></a></div>
          </template>
        </template>
        <footer class="page-footer"><span>ELMA 数据控制台</span><span>聚合统计 · Asia/Shanghai · {{ snapshot?.meta.sourceMode === 'fixture' ? '当前为演示快照' : '只读数据分析' }}</span></footer>
      </main>
    </div>
  </div>
</template>
