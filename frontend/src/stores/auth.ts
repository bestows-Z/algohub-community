import { defineStore } from 'pinia'
import { computed, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { ApiError, getSessionGeneration, logoutSession, pendingLogout, logoutMarkerPersistent, retryPendingLogout, post, refreshSession, setAccessToken, setEmailGateHandler, setUnauthenticatedHandler } from '@/api/client'
import type { Me, TokenView } from '@/api/types'
import { clearOnboardingDrafts } from '@/utils/onboardingStorage'
import { adminLoginPath, isAdminHost } from '@/utils/adminHost'

export const useAuthStore = defineStore('auth', () => {
  const me = ref<Me | null>(null)
  const loaded = ref(false)
  const bootstrapError = ref('')
  /** 后端因为「没有已验证的登录邮箱」拦下过请求；页面顶部据此显示引导条。 */
  const emailGateBlocked = ref(false)

  setEmailGateHandler(() => {
    emailGateBlocked.value = true
  })

  const isLoggedIn = computed(() => me.value !== null)
  const isSchoolAdmin = computed(() => !!me.value?.managedSchools?.length)
  const isCohortLeader = computed(() => !isSchoolAdmin.value && me.value?.role === 'STUDENT' && !!me.value?.managedCohorts?.length)
  const isAdmin = computed(() => me.value?.role === 'ADMIN')
  const approvedMembership = computed(
    () => me.value?.memberships.find((m) => m.status === 'APPROVED') ?? null
  )

  async function apply(token: TokenView): Promise<boolean> {
    if (pendingLogout.value) {
      if (pendingLogout.value.mode !== 'all' || !pendingLogout.value.requiresIdentity) {
        throw new ApiError('LOGOUT_PENDING', '请先完成服务端退出，再登录其他账号')
      }
      await retryPendingLogout({ accessToken: token.accessToken, userId: token.user.id })
      if (pendingLogout.value) throw new ApiError('LOGOUT_PENDING', '服务端退出尚未完成，请重试')
      clear()
      ElMessage.success('所有设备已退出，请重新登录使用')
      return false
    }
    if (me.value?.id !== token.user.id) clearOnboardingDrafts()
    setAccessToken(token.accessToken, token.user.id)
    me.value = token.user
    return true
  }

  function clear() {
    clearOnboardingDrafts()
    setAccessToken(null)
    me.value = null
    emailGateBlocked.value = false
  }

  watch(pendingLogout, (operation, previous) => {
    if (operation) {
      clear()
      if (operation.requiresIdentity && !previous?.requiresIdentity && loaded.value) {
        void import('@/router').then(({ default: router }) => router.replace(isAdminHost ? adminLoginPath : '/login'))
      }
    }
    else if (previous) {
      clear()
      bootstrapError.value = ''
      loaded.value = true
      void import('@/router').then(({ default: router }) => router.replace(isAdminHost ? adminLoginPath : '/login'))
    }
  }, { flush: 'sync' })

  /**
   * 启动时用 HttpOnly 的刷新令牌 Cookie 换一次访问令牌：
   * 换到就是已登录，换不到就是访客。访问令牌本身从不落盘。
   * main.ts 与路由守卫在首屏会同时调用：共用同一个请求，否则两次刷新带着同一个刷新令牌并发换新，
   * 后到的那次可能因旧令牌已作废而把刚恢复的登录状态清掉，或者多出一条登录设备记录。
   * 几个标签页同时打开也一样，refreshSession 跨标签页排队。
   */
  let bootstrapping: Promise<void> | null = null
  function bootstrap() {
    bootstrapping ??= (async () => {
      bootstrapError.value = ''
      const generation = getSessionGeneration()
      try {
        if (pendingLogout.value) {
          clear()
          try { await retryPendingLogout() } catch (error) {
            if (!pendingLogout.value?.requiresIdentity) throw error
          }
          loaded.value = true
          return
        }
        const result = await refreshSession<TokenView>()
        if (generation !== getSessionGeneration() || pendingLogout.value) {
          loaded.value = true
          return
        }
        if (result?.success) {
          await apply(result.data)
        } else {
          // Recover a host-only login issued before www was canonicalized. No credentials enter JS.
          const key = 'algohub-domain-migration-attempted'
          if (location.hostname === 'algohub.top' && !sessionStorage.getItem(key)) {
            sessionStorage.setItem(key, '1')
            location.replace('https://www.algohub.top/api/auth/domain-migration/start?returnTo=' +
              encodeURIComponent(location.pathname + location.search + location.hash))
            await new Promise<never>(() => {})
          }
          clear()
        }
        loaded.value = true
      } catch (error) {
        bootstrapError.value = error instanceof Error ? error.message : '暂时无法恢复登录，请重试'
        throw error
      } finally {
        bootstrapping = null
      }
    })()
    return bootstrapping
  }

  async function login(username: string, password: string) {
    return apply(await post<TokenView>('/auth/login', { username, password }))
  }

  async function logout() {
    const userId = me.value?.id ?? null
    try {
      await logoutSession(false, userId)
    } finally {
      clear()
    }
  }

  async function logoutAll() {
    const userId = me.value?.id ?? null
    try {
      await logoutSession(true, userId)
    } finally {
      clear()
    }
  }

  async function refreshMe() {
    const generation = getSessionGeneration()
    const { get } = await import('@/api/client')
    const user = await get<Me>('/me')
    if (generation !== getSessionGeneration() || pendingLogout.value) return
    if (me.value?.id !== user.id) clearOnboardingDrafts()
    me.value = user
  }

  /**
   * 用着用着登录失效了（刷新令牌过期、在别处退出了所有设备）：清掉本地状态，带着当前页回登录页。
   * 同一时刻好几个请求一起 401 只跳一次、只提示一次；本来就在登录页这类公开页面不跳。
   */
  let redirecting = false
  setUnauthenticatedHandler(() => {
    const wasLoggedIn = me.value !== null
    clear()
    if (redirecting || !loaded.value) return
    redirecting = true
    void import('@/router')
      .then(async ({ default: router }) => {
        const current = router.currentRoute.value
        if (current.meta.public || current.meta.guestOnly) return
        if (wasLoggedIn && !pendingLogout.value) ElMessage.warning('登录已失效，请重新登录')
        await router.replace({ name: current.meta.admin ? 'admin-login' : 'login' })
      })
      .finally(() => {
        redirecting = false
      })
  })

  return {
    me,
    loaded,
    bootstrapError,
    emailGateBlocked,
    pendingLogout,
    logoutMarkerPersistent,
    retryPendingLogout,
    isLoggedIn,
    isAdmin,
    isSchoolAdmin,
    isCohortLeader,
    approvedMembership,
    apply,
    clear,
    bootstrap,
    login,
    logout,
    logoutAll,
    refreshMe
  }
})
