<script setup lang="ts">
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { pendingNavigation, prefetchPage } from '@/composables/useNavigation'
import { useAccounts } from '@/stores/accounts'
import { useAuthStore } from '@/stores/auth'
import { navGroupsForRole, isNavActive } from './nav'
import UserMenu from './UserMenu.vue'

/** 平板（768–1023）的图标栏：图标 + 短名称，分组之间一条短线；「账号」有待处理时右上角一个琥珀色圆点。 */
const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const accounts = useAccounts(!auth.isAdmin)

const groups = computed(() => navGroupsForRole(auth.isAdmin, auth.isSchoolAdmin, auth.isCohortLeader))
</script>

<template>
  <aside
    class="sticky top-0 box-border h-screen w-[72px] flex shrink-0 flex-col items-center gap-1 border-0 border-r border-solid border-line bg-surface pb-[18px] pt-4"
  >
    <RouterLink
      to="/"
      class="brand mb-3.5 size-10 flex shrink-0 items-center justify-center"
      aria-label="AlgoHub，回到数据总览"
      title="AlgoHub"
    >
      <img src="/algohub-mark.svg" alt="" class="size-10" />
    </RouterLink>

    <nav aria-label="主导航" class="min-h-0 flex flex-1 flex-col items-center gap-1 overflow-y-auto">
      <template v-for="(group, index) in groups" :key="group.title">
        <span v-if="index > 0" class="my-2 h-px w-8 shrink-0 bg-line" aria-hidden="true" />
        <RouterLink
          v-for="item in group.items"
          :key="item.path"
          :to="item.path"
          @pointerenter="prefetchPage(router, item.path)"
          @focus="prefetchPage(router, item.path)"
          @touchstart.passive="prefetchPage(router, item.path)"
          class="rail-item"
          :class="{ 'is-active': isNavActive(item, pendingNavigation?.path ?? route.path) }"
          :title="item.label"
          :aria-label="item.pending && accounts.pendingCount ? `${item.label}，${accounts.pendingCount} 个待处理` : item.label"
        >
          <span :class="item.icon" class="text-[22px]" aria-hidden="true" />
          {{ item.short }}
          <span v-if="item.pending && accounts.pendingCount" class="rail-dot" aria-hidden="true" />
        </RouterLink>
      </template>
    </nav>

    <!-- 头像右边缘离图标栏右边框 17px，再空 8px：菜单整个落在图标栏外面 -->
    <UserMenu compact placement="right-end" :offset="25" class="mt-2" />
  </aside>
</template>

<style scoped>
.brand,
.brand:hover {
  color: #fff;
  text-decoration: none;
}

.rail-item {
  position: relative;
  display: flex;
  flex-shrink: 0;
  flex-direction: column;
  align-items: center;
  gap: 4px;
  width: 62px;
  padding: 9px 0 7px;
  border-radius: 12px;
  color: var(--text-muted);
  font-size: 11px;
  line-height: 1.3;
  text-decoration: none;
  transition: background-color 0.15s ease;
}

.rail-item:hover {
  background: var(--bg-subtle);
  color: var(--text-strong);
  text-decoration: none;
}

.rail-item.is-active {
  background: var(--brand-soft);
  color: var(--brand-600);
  font-weight: 600;
}

.rail-item:focus-visible {
  outline: 2px solid var(--brand-500);
  outline-offset: -2px;
}

.rail-dot {
  position: absolute;
  top: 6px;
  left: 38px;
  width: 8px;
  height: 8px;
  border: 2px solid var(--bg-elevated);
  border-radius: 50%;
  background: var(--warning);
  box-sizing: content-box;
}
</style>
