<script setup lang="ts">
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { pendingNavigation, prefetchPage } from '@/composables/useNavigation'
import { useAccounts } from '@/stores/accounts'
import { useAuthStore } from '@/stores/auth'
import { navGroupsForRole, isNavActive } from './nav'
import SyncStatus from './SyncStatus.vue'
import UserMenu from './UserMenu.vue'

/** 电脑（≥ 1024）的侧栏：品牌、导航、同步状态、个人菜单。「平台账号」右边是待处理数。 */
const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const accounts = useAccounts(!auth.isAdmin)

const groups = computed(() => navGroupsForRole(auth.isAdmin, auth.isSchoolAdmin, auth.isCohortLeader))
</script>

<template>
  <aside
    class="sticky top-0 box-border h-screen w-60 flex shrink-0 flex-col border-0 border-r border-solid border-line bg-surface px-3 pb-3.5 pt-5"
  >
    <RouterLink to="/" class="brand flex items-center gap-2.5 px-2 pb-5" aria-label="AlgoHub，回到数据总览">
      <img src="/algohub-mark.svg" alt="" class="size-9 shrink-0" />
      <span class="flex flex-col leading-[1.35]">
        <span class="text-[15px] font-650 text-ink">AlgoHub</span>
        <span class="text-xs text-muted">算法学习与成长档案</span>
      </span>
    </RouterLink>

    <nav aria-label="主导航" class="min-h-0 flex flex-1 flex-col gap-0.5 overflow-y-auto">
      <template v-for="group in groups" :key="group.title">
        <div v-if="group.title" class="px-3 pb-1.5 pt-[18px] text-xs text-muted tracking-[0.04em]">{{ group.title }}</div>
        <RouterLink
          v-for="item in group.items"
          :key="item.path"
          :to="item.path"
          @pointerenter="prefetchPage(router, item.path)"
          @focus="prefetchPage(router, item.path)"
          @touchstart.passive="prefetchPage(router, item.path)"
          class="nav-item"
          :class="{ 'is-active': isNavActive(item, pendingNavigation?.path ?? route.path) }"
        >
          <span :class="item.icon" class="nav-icon text-[19px]" aria-hidden="true" />
          {{ item.label }}
          <template v-if="item.pending && accounts.pendingCount">
            <span
              class="num ml-auto inline-flex h-5 min-w-5 items-center justify-center rounded-full bg-warning-tint px-1.5 text-xs font-semibold text-warning-ink"
              aria-hidden="true"
            >
              {{ accounts.pendingCount }}
            </span>
            <span class="sr-only">，{{ accounts.pendingCount }} 个待处理</span>
          </template>
        </RouterLink>
      </template>
    </nav>

    <SyncStatus v-if="!auth.isAdmin" class="mb-2.5 mt-3" />
    <UserMenu />
  </aside>
</template>

<style scoped>
.brand,
.brand:hover {
  text-decoration: none;
}

.nav-item {
  display: flex;
  align-items: center;
  gap: 12px;
  height: 40px;
  padding: 0 12px;
  border-radius: 10px;
  color: var(--text);
  font-size: 14px;
  text-decoration: none;
  transition: background-color 0.15s ease;
}

.nav-item:hover {
  background: var(--bg-subtle);
  color: var(--text-strong);
  text-decoration: none;
}

.nav-item.is-active {
  background: var(--brand-soft);
  color: var(--brand-600);
  font-weight: 600;
}

.nav-icon {
  color: var(--text-muted);
}

.nav-item.is-active .nav-icon {
  color: var(--brand-500);
}

.nav-item:focus-visible {
  outline: 2px solid var(--brand-500);
  outline-offset: -2px;
}
</style>
