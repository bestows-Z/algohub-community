<script setup lang="ts">
import { computed } from 'vue'
import { useRouter } from 'vue-router'
import UserAvatar from '@/components/ui/UserAvatar.vue'
import { useLogout } from '@/composables/useLogout'
import { useAccountsStore } from '@/stores/accounts'
import { useAuthStore } from '@/stores/auth'
import { THEME_MODES, useThemeStore, type ThemeMode } from '@/stores/theme'

/**
 * 个人菜单：侧栏底部是头像 + 名字 + 年级班级，图标栏底部只有头像（compact）。
 * 点开：设置与安全、平台账号（带待处理数）、外观（跟随系统 / 浅色 / 深色）、退出登录。
 */
const props = withDefaults(
  defineProps<{
    compact?: boolean
    placement?: 'top-start' | 'right-end' | 'bottom-end'
    /** 菜单离头像的距离，默认 12 */
    offset?: number
  }>(),
  { compact: false, placement: 'top-start', offset: 12 }
)

const auth = useAuthStore()
const accounts = useAccountsStore()
const router = useRouter()
const logout = useLogout()
const themeStore = useThemeStore()

const name = computed(() => auth.me?.displayName || auth.me?.username || '')
const initial = computed(() => name.value.slice(0, 1).toUpperCase() || '?')

/** 名字下面一行：学生是「2026 级 · 计应 1 班」，管理员是「管理员」（有班级时带上班级） */
const subtitle = computed(() => {
  const me = auth.me
  if (!me) return ''
  const membership = auth.approvedMembership
  if (me.role === 'ADMIN') return membership?.className ? `平台管理员 · ${membership.className}` : '平台管理员'
  if (auth.isSchoolAdmin) return '学校管理员'
  if (auth.isCohortLeader) return '年级负责人'
  if (membership) {
    const year = membership.enrollmentYear ? `${membership.enrollmentYear} 级` : null
    return [year, membership.className ?? membership.schoolName].filter(Boolean).join(' · ')
  }
  return me.memberships.some((m) => m.status === 'PENDING') ? '学校审核中' : '还没有加入学校'
})

const popperOptions = computed(() => ({ modifiers: [{ name: 'offset', options: { offset: [0, props.offset] } }] }))

function onCommand(command: string) {
  if (command.startsWith('theme:')) themeStore.setMode(command.slice('theme:'.length) as ThemeMode)
  else if (command === 'logout') void logout()
  else void router.push(command)
}
</script>

<template>
  <el-dropdown trigger="click" :placement="placement" :popper-options="popperOptions" @command="onCommand">
    <button
      v-if="compact"
      type="button"
      class="user-trigger size-[38px] justify-center rounded-full p-0"
      :aria-label="`个人菜单：${name}`"
      :title="name"
    >
      <el-avatar :size="38">
        <UserAvatar :src="auth.me?.avatarUrl" class="size-full flex items-center justify-center rounded-full">{{ initial }}</UserAvatar>
      </el-avatar>
    </button>
    <button v-else type="button" class="user-trigger w-full gap-2.5 rounded-[10px] p-2 text-left" :aria-label="`个人菜单：${name}`">
      <el-avatar :size="36" class="shrink-0">
        <UserAvatar :src="auth.me?.avatarUrl" class="size-full flex items-center justify-center rounded-full">{{ initial }}</UserAvatar>
      </el-avatar>
      <span class="flex min-w-0 flex-1 flex-col leading-[1.4]">
        <span class="truncate text-sm font-semibold text-ink">{{ name }}</span>
        <span class="truncate text-xs text-muted">{{ subtitle }}</span>
      </span>
      <span class="i-lucide-chevrons-up-down shrink-0 text-base text-muted" aria-hidden="true" />
    </button>
    <template #dropdown>
      <el-dropdown-menu class="min-w-[200px]">
        <li v-if="compact" class="px-4 pb-2 pt-1.5 leading-[1.4]" role="presentation">
          <div class="text-sm font-semibold text-ink">{{ name }}</div>
          <div class="text-xs text-muted">{{ subtitle }}</div>
        </li>
        <el-dropdown-item v-if="auth.isSchoolAdmin || auth.isAdmin" command="/school-admin">学校管理</el-dropdown-item>
        <el-dropdown-item v-if="auth.isCohortLeader" command="/cohort-admin">本届管理</el-dropdown-item>
        <el-dropdown-item command="/settings" :divided="compact">
          <span class="i-lucide-settings mr-2.5 text-base" aria-hidden="true" />设置与安全
        </el-dropdown-item>
        <el-dropdown-item v-if="!auth.isAdmin" command="/accounts">
          <span class="i-lucide-link mr-2.5 text-base" aria-hidden="true" />平台账号
          <template v-if="accounts.pendingCount">
            <span
              class="num ml-auto inline-flex h-5 min-w-5 items-center justify-center rounded-full bg-warning-tint px-1.5 text-xs font-semibold text-warning-ink"
              aria-hidden="true"
            >
              {{ accounts.pendingCount }}
            </span>
            <span class="sr-only">，{{ accounts.pendingCount }} 个待处理</span>
          </template>
        </el-dropdown-item>
        <li class="mt-1.5 border-0 border-t border-line border-solid px-4 pb-1 pt-2 text-xs text-muted" role="presentation">外观</li>
        <el-dropdown-item v-for="option in THEME_MODES" :key="option.value" :command="`theme:${option.value}`">
          <span :class="option.icon" class="mr-2.5 text-base" aria-hidden="true" />
          {{ option.label }}
          <template v-if="themeStore.mode === option.value">
            <span class="i-lucide-check ml-auto pl-3 text-base text-brand" aria-hidden="true" />
            <span class="sr-only">（当前）</span>
          </template>
        </el-dropdown-item>
        <el-dropdown-item command="logout" divided>
          <span class="i-lucide-log-out mr-2.5 text-base" aria-hidden="true" />退出登录
        </el-dropdown-item>
      </el-dropdown-menu>
    </template>
  </el-dropdown>
</template>

<style scoped>
.user-trigger {
  display: flex;
  align-items: center;
  border: 0;
  background: transparent;
  color: inherit;
  font: inherit;
  cursor: pointer;
}

.user-trigger:hover {
  background: var(--bg-subtle);
}

.user-trigger:focus-visible {
  outline: 2px solid var(--brand-500);
  outline-offset: 1px;
}
</style>
