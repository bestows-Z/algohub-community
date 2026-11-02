<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { get } from '@/api/client'
import type { Contest } from '@/api/types'
import { useLogout } from '@/composables/useLogout'
import { useAuthStore } from '@/stores/auth'
import { THEME_MODES, useThemeStore, type ThemeMode } from '@/stores/theme'
import { campusDate, shiftDate } from '@/utils/statistics'
import ResponsiveDialog from '@/components/ui/ResponsiveDialog.vue'

/**
 * 「我的」：手机底栏第 5 项。个人卡片 → 校园（公告与资源、校内动态、目标与提醒、比赛日历）→ 设置 → 管理（仅管理员）→ 退出登录。
 * 电脑和平板没有入口（侧栏和个人菜单已经覆盖）；宽屏直接打开时照常显示，内容居中、最宽 560。
 */
const auth = useAuthStore()
const logout = useLogout()
const themeStore = useThemeStore()

const name = computed(() => auth.me?.displayName || auth.me?.username || '')
const initial = computed(() => name.value.slice(0, 1).toUpperCase() || '?')

/** 学校 · 年级 · 班级；还在审核、没加入学校时照实说 */
const schoolLine = computed(() => {
  const me = auth.me
  if (!me) return ''
  const approved = auth.approvedMembership
  if (approved) {
    return [approved.schoolName, approved.enrollmentYear ? `${approved.enrollmentYear} 级` : null, approved.className]
      .filter(Boolean)
      .join(' · ')
  }
  const pending = me.memberships.find((m) => m.status === 'PENDING')
  if (pending) return `${pending.schoolName} · 审核中`
  return me.role === 'ADMIN' ? '平台管理员' : auth.isSchoolAdmin ? '学校管理员' : '还没有加入学校'
})

// 比赛日历的副标题写下一场什么时候开始（北京时间）
const nextContest = ref('近期各平台的比赛')
const timeFormat = new Intl.DateTimeFormat('zh-CN', { timeZone: 'Asia/Shanghai', hour: '2-digit', minute: '2-digit', hourCycle: 'h23' })

function startText(startAt: string, now: number) {
  const start = new Date(startAt)
  const day = campusDate(start)
  const today = campusDate(new Date(now))
  const time = timeFormat.format(start)
  if (day === today) return `今天 ${time}`
  if (day === shiftDate(today, 1)) return `明天 ${time}`
  const [, month, date] = day.split('-').map(Number)
  return `${month}月${date}日 ${time}`
}

onMounted(async () => {
  try {
    const { contests } = await get<{ contests: Contest[] }>('/contests')
    const now = Date.now()
    const next = contests.find((contest) => Date.parse(contest.startAt) > now)
    nextContest.value = next ? `下一场：${startText(next.startAt, now)}` : '近期没有比赛'
  } catch {
    // 读不到就保留默认说明，不影响这一页
  }
})

type Tone = 'success' | 'info' | 'warning' | 'neutral' | 'brand'
const TONE: Record<Tone, string> = {
  success: 'bg-success-soft text-success-ink',
  info: 'bg-info-soft text-info-ink',
  warning: 'bg-warning-tint text-warning-ink',
  neutral: 'bg-surface-2 text-body',
  brand: 'bg-brand-soft text-brand-600'
}

interface Row {
  label: string
  sub?: string
  icon: string
  tone: Tone
  /** 链接；没有时是按钮（外观） */
  to?: string
  value?: string
}

const appearanceOpen = ref(false)
const modeLabel = computed(() => THEME_MODES.find((option) => option.value === themeStore.mode)?.label ?? '')

const groups = computed(() => {
  const list: { title: string; rows: Row[] }[] = [
    {
      title: '校园',
      rows: [
        { label: '消息', sub: '系统通知与好友消息', icon: 'i-lucide-mail', tone: 'brand', to: '/messages' },
        { label: '关注与好友', sub: '我的关注、互关好友和关注数量', icon: 'i-lucide-users-round', tone: 'brand', to: '/friends' },
        { label: '公告与资源', sub: '学校通知和常用学习资源', icon: 'i-lucide-megaphone', tone: 'warning', to: '/announcements' },
        { label: '公开动态', sub: '同学们最近通过的题', icon: 'i-lucide-activity', tone: 'success', to: '/activity' },
        { label: '目标与提醒', sub: '学习进度、持续计划与提醒设置', icon: 'i-lucide-bell', tone: 'brand', to: '/reminders' },
        { label: '比赛日历', sub: nextContest.value, icon: 'i-lucide-calendar', tone: 'info', to: '/contests' }
      ]
    },
    {
      title: '设置',
      rows: [
        { label: '设置与安全', sub: '资料、隐私、登录设备', icon: 'i-lucide-settings', tone: 'neutral', to: '/settings' },
        { label: '外观', icon: 'i-lucide-moon', tone: 'neutral', value: modeLabel.value }
      ]
    }
  ]
  if (auth.isAdmin) {
    list.push({ title: '管理', rows: [{ label: '管理后台', icon: 'i-lucide-shield', tone: 'brand', to: '/admin' }] })
  }
  if (auth.isSchoolAdmin || auth.isAdmin) list.push({title:'学校管理',rows:[{label:'学校管理',sub:'成员审核、班级与校内公告',icon:'i-lucide-school',tone:'brand',to:'/school-admin'}]})
  if (auth.isCohortLeader) list.push({title:'年级管理',rows:[{label:'本届管理',sub:'负责年级的成员审核与资料维护',icon:'i-lucide-users-round',tone:'brand',to:'/cohort-admin'}]})
  return list
})

function chooseMode(next: ThemeMode) {
  themeStore.setMode(next)
  appearanceOpen.value = false
}
</script>

<template>
  <div class="mx-auto max-w-[560px] flex flex-col gap-[18px]">
    <!-- 手机上标题在顶栏里，这里只留给读屏软件 -->
    <h1 class="sr-only md:(not-sr-only m-0 text-[22px] font-bold leading-[30px] text-ink) lg:(text-[26px] leading-[34px])">我的</h1>

    <RouterLink :to="{ path: '/settings', query: { tab: 'profile' } }" class="me-card flex items-center gap-3.5 border border-line rounded-2xl border-solid bg-surface p-4">
      <UserAvatar
        :src="auth.me?.avatarUrl"
        class="size-14 flex shrink-0 items-center justify-center rounded-full bg-brand-btn text-[23px] text-white font-semibold"
      >
        {{ initial }}
      </UserAvatar>
      <span class="min-w-0 flex flex-1 flex-col gap-1">
        <span class="truncate text-lg text-ink font-650">{{ name }}</span>
        <span class="text-[12.5px] text-muted leading-[1.5]">{{ schoolLine }}</span>
      </span>
      <span class="shrink-0 text-[12.5px] text-muted">编辑资料</span>
      <span class="i-lucide-chevron-right shrink-0 text-[17px] text-faint" aria-hidden="true" />
    </RouterLink>

    <section v-for="group in groups" :key="group.title" class="flex flex-col gap-1.5">
      <h2 class="m-0 px-1 text-[12.5px] text-muted font-normal">{{ group.title }}</h2>
      <nav :aria-label="group.title" class="overflow-hidden border border-line rounded-[14px] border-solid bg-surface">
        <component
          :is="row.to ? 'RouterLink' : 'button'"
          v-for="row in group.rows"
          :key="row.label"
          :to="row.to"
          :type="row.to ? undefined : 'button'"
          class="me-row"
          @click="row.to ? undefined : (appearanceOpen = true)"
        >
          <span class="size-9 flex shrink-0 items-center justify-center rounded-[10px]" :class="TONE[row.tone]" aria-hidden="true">
            <span :class="row.icon" class="text-[19px]" />
          </span>
          <span class="min-w-0 flex flex-1 flex-col gap-px">
            <span class="text-[15px] text-ink">{{ row.label }}</span>
            <span v-if="row.sub" class="text-xs text-muted">{{ row.sub }}</span>
          </span>
          <span v-if="row.value" class="shrink-0 text-[13.5px] text-muted">{{ row.value }}</span>
          <span class="i-lucide-chevron-right shrink-0 text-[17px] text-faint" aria-hidden="true" />
        </component>
      </nav>
    </section>

    <button type="button" class="logout-button" @click="logout">
      <span class="i-lucide-log-out text-lg" aria-hidden="true" />
      退出登录
    </button>

    <ResponsiveDialog v-model="appearanceOpen" title="外观" :width="400">
      <div role="radiogroup" aria-label="外观" class="flex flex-col gap-2">
        <label
          v-for="option in THEME_MODES"
          :key="option.value"
          class="theme-option"
          :class="{ 'is-active': themeStore.mode === option.value }"
        >
          <input
            type="radio"
            name="me-theme"
            class="sr-only"
            :checked="themeStore.mode === option.value"
            @change="chooseMode(option.value)"
          />
          <span :class="option.icon" class="shrink-0 text-lg text-muted" aria-hidden="true" />
          <span class="min-w-0 flex flex-1 flex-col gap-0.5 py-2.5">
            <span class="text-[15px] text-ink">{{ option.label }}</span>
            <span v-if="option.hint" class="text-xs text-muted">{{ option.hint }}</span>
          </span>
          <span v-if="themeStore.mode === option.value" class="i-lucide-check shrink-0 text-lg text-brand" aria-hidden="true" />
        </label>
      </div>
    </ResponsiveDialog>
  </div>
</template>

<style scoped>
.me-card,
.me-card:hover {
  text-decoration: none;
}

.me-card:hover {
  border-color: var(--border-strong);
}

.me-row {
  display: flex;
  align-items: center;
  gap: 12px;
  width: 100%;
  min-height: 60px;
  padding: 0 14px;
  border: 0;
  border-bottom: 1px solid var(--border-soft);
  background: transparent;
  color: inherit;
  font: inherit;
  text-align: left;
  text-decoration: none;
  cursor: pointer;
}

.me-row:last-child {
  border-bottom: 0;
}

.me-row:hover {
  background: var(--bg-subtle);
  text-decoration: none;
}

.me-row:focus-visible,
.me-card:focus-visible,
.logout-button:focus-visible {
  outline: 2px solid var(--brand-500);
  outline-offset: -2px;
}

.logout-button {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  height: 50px;
  border: 1px solid var(--border);
  border-radius: 14px;
  background: var(--bg-elevated);
  color: var(--danger-ink);
  font: inherit;
  font-size: 15px;
  cursor: pointer;
}

.logout-button:hover {
  border-color: color-mix(in srgb, var(--danger) 35%, var(--border));
}

.theme-option {
  display: flex;
  align-items: center;
  gap: 12px;
  min-height: 52px;
  padding: 0 14px;
  border: 1px solid var(--border);
  border-radius: 12px;
  cursor: pointer;
}

.theme-option.is-active {
  border-color: var(--brand-400);
  background: color-mix(in srgb, var(--brand-soft) 45%, var(--bg-elevated));
}

.theme-option:has(input:focus-visible) {
  outline: 2px solid var(--brand-500);
  outline-offset: 1px;
}
</style>
