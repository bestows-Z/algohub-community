import { createRouter, createWebHistory, isNavigationFailure, NavigationFailureType } from 'vue-router'
import { ElMessage } from 'element-plus'
import { recordVisit } from '@/api/visits'
import { useAuthStore } from '@/stores/auth'
import { isAdminHost, isPublicHost, adminLoginPath } from '@/utils/adminHost'
import { finishNavigation, pendingNavigation } from '@/composables/useNavigation'

declare module 'vue-router' {
  interface RouteMeta {
    title?: string
    /** 手机顶栏上的标题，默认用 title */
    mobileTitle?: string
    /** 手机顶栏左上角返回到哪一页；底栏也把这一页归到那一项（如「我的」） */
    mobileParent?: string
    fullscreen?: boolean
    public?: boolean
    guestOnly?: boolean
    admin?: boolean
  }
}

const router = createRouter({
  history: createWebHistory(),
  // 换页回到顶部；浏览器前进后退回到原来的位置；同一页只改查询参数（筛选、标签页）时不动
  scrollBehavior(to, from, savedPosition) {
    if (savedPosition) return savedPosition
    if (to.hash) return { el: to.hash, top: 80 }
    if (to.path === from.path) return false
    return { top: 0 }
  },
  routes: [
    { path: adminLoginPath, name: 'admin-login', component: () => import('@/views/LoginView.vue'), meta: { title: '管理登录', fullscreen: true, guestOnly: true } },
    ...(isAdminHost ? [{ path: '/admin/login', redirect: '/' }] : []),
    { path: '/oauth/github/link', component: () => import('@/views/GithubLinkView.vue'), meta: { title: '关联 GitHub', fullscreen: true, public: true } },
    { path: '/verify-email', name: 'verify-email', component: () => import('@/views/VerifyEmailView.vue'), meta: { title: '验证登录邮箱', fullscreen: true } },
    { path: '/forgot-password', name: 'forgot-password', component: () => import('@/views/ForgotPasswordView.vue'), meta: { title: '找回密码', fullscreen: true, public: true } },
    { path: '/complete-academic-profile', name: 'complete-academic-profile', component: () => import('@/views/CompleteAcademicProfileView.vue'), meta: { title: '补全资料', fullscreen: true } },
    { path: '/complete-profile', name: 'complete-profile', component: () => import('@/views/CompleteProfileView.vue'), meta: { title: '完善资料', fullscreen: true } },
    { path: '/select-school', name: 'select-school', component: () => import('@/views/SelectSchoolView.vue'), meta: { title: '选择学校', fullscreen: true } },
    {
      path: '/login',
      name: 'login',
      component: () => import('@/views/LoginView.vue'),
      meta: { title: '登录', fullscreen: true, guestOnly: true }
    },
    {
      path: '/oauth/github/callback',
      name: 'github-callback',
      component: () => import('@/views/OauthCallbackView.vue'),
      meta: { title: 'GitHub 授权', fullscreen: true, public: true }
    },
    ...(!isAdminHost ? [{ path: '/', name: 'home', component: () => import('@/views/HomeView.vue'), meta: { title: '数据总览', mobileTitle: '总览' } }] : []),
    { path: '/announcements', name: 'announcements', component: () => import('@/views/AnnouncementsView.vue'), meta: { title: '公告与资源' } },
    { path: '/friends', name: 'friends', component: () => import('@/views/FriendsView.vue'), meta: { title: '关注与好友', mobileParent: '/me' } },
    { path: '/users/:userId', name: 'public-profile', component: () => import('@/views/PublicProfileView.vue'), meta: { title: '同学档案', mobileParent: '/ranking' } },
    { path: '/luogu-sync', name: 'luogu-sync', component: () => import('@/views/LuoguBrowserSyncView.vue'), meta: { title: '洛谷浏览器同步', mobileParent: '/accounts' } },
    { path: '/school-admin', name: 'school-admin', component: () => import('@/views/SchoolAdminView.vue'), meta: { title: '学校管理' } },
    { path: '/cohort-admin', name: 'cohort-admin', component: () => import('@/views/CohortAdminView.vue'), meta: { title: '本届管理', mobileParent: '/me' } },
    { path: '/qoj-sync', name: 'qoj-sync', component: () => import('@/views/QojBrowserSyncView.vue'), meta: { title: 'QOJ 浏览器同步', mobileParent: '/accounts' } },
    { path: '/leetcode-sync', name: 'leetcode-sync', component: () => import('@/views/LeetCodeBrowserSyncView.vue'), meta: { title: '力扣浏览器同步', mobileParent: '/accounts' } },
    { path: '/ai-archive', name: 'ai-archive', component: () => import('@/views/AiArchiveView.vue'), meta: { title: '交给 AI 分析', mobileParent: '/profile' } },
    {
      path: '/profile',
      name: 'profile',
      component: () => import('@/views/ProfileView.vue'),
      meta: { title: '个人档案' }
    },
    {
      path: '/accounts',
      name: 'accounts',
      component: () => import('@/views/AccountsView.vue'),
      meta: { title: '平台账号' }
    },
    // 手机上「同步记录」进二级页面；电脑上同样的内容在平台账号页里展开
    {
      path: '/accounts/sync-log',
      name: 'accounts-sync-log',
      component: () => import('@/views/AccountsSyncLogView.vue'),
      meta: { title: '同步记录', mobileParent: '/accounts' }
    },
    {
      path: '/activity',
      name: 'activity',
      component: () => import('@/views/ActivityView.vue'),
      meta: { title: '公开动态', mobileParent: '/me' }
    },
    {
      path: '/problems',
      name: 'problemset',
      component: () => import('@/views/ProblemsetView.vue'),
      meta: { title: '题库' }
    },
    {
      path: '/skill-map',
      name: 'skill-map',
      component: () => import('@/views/SkillMapView.vue'),
      meta: { title: '算法地图' }
    },
    {
      path: '/bookmarks',
      name: 'bookmarks',
      component: () => import('@/views/BookmarksView.vue'),
      meta: { title: '我的收藏' }
    },
    {
      path: '/activities',
      name: 'activities',
      component: () => import('@/views/ActivitiesView.vue'),
      meta: { title: '训练活动' }
    },
    {
      path: '/admin/problem-lists',
      name: 'admin-problem-lists',
      component: () => import('@/views/ProblemListsView.vue'),
      props: { management: true },
      meta: { title: '题单管理', admin: true, mobileParent: '/admin' }
    },
    {
      path: '/admin/problem-lists/:id',
      name: 'admin-problem-list-detail',
      component: () => import('@/views/ProblemListDetailView.vue'),
      props: { management: true },
      meta: { title: '管理题单详情', admin: true, mobileParent: '/admin/problem-lists' }
    },
    {
      path: '/problem-lists',
      name: 'problem-lists',
      component: () => import('@/views/ProblemListsView.vue'),
      meta: { title: '题单训练' }
    },
    {
      path: '/problem-lists/:id',
      name: 'problem-list-detail',
      component: () => import('@/views/ProblemListDetailView.vue'),
      meta: { title: '题单详情', mobileParent: '/problem-lists' }
    },
    {
      path: '/ranking',
      name: 'ranking',
      component: () => import('@/views/RankingView.vue'),
      meta: { title: '刷题排行' }
    },
    {
      path: '/contests',
      name: 'contests',
      component: () => import('@/views/ContestsView.vue'),
      meta: { title: '比赛日历', mobileParent: '/me' }
    },
    {
      path: '/messages',
      name: 'messages',
      component: () => import('@/views/MessagesView.vue'),
      meta: { title: '消息', mobileParent: '/me' }
    },
    {
      path: '/reminders',
      name: 'reminders',
      component: () => import('@/views/RemindersView.vue'),
      meta: { title: '目标与提醒', mobileParent: '/me' }
    },
    {
      path: '/settings',
      name: 'settings',
      component: () => import('@/views/SettingsView.vue'),
      meta: { title: '设置与安全', mobileParent: '/me' }
    },
    {
      // 手机底栏的「我的」；电脑和平板没有入口，直接打开时内容居中
      path: '/me',
      name: 'me',
      component: () => import('@/views/MeView.vue'),
      meta: { title: '我的' }
    },
    {
      path: '/admin',
      name: 'admin',
      component: () => import('@/views/AdminView.vue'),
      meta: { title: '管理后台', admin: true, mobileParent: '/me' }
    },
    // 组件样板页：只在开发环境有，生产构建里这一段连同页面代码一起被去掉
    ...(import.meta.env.DEV
      ? [
          {
            path: '/dev/ui',
            name: 'dev-ui',
            component: () => import('@/views/dev/UiKitView.vue'),
            meta: { title: '组件样板', fullscreen: true, public: true }
          }
        ]
      : []),
    { path: '/:pathMatch(.*)*', redirect: '/' }
  ]
})

// 除登录与 OAuth 回调外，所有页面都要求登录：学习记录与排行榜都属于身份相关数据。
router.beforeEach(async (to, from) => {
  pendingNavigation.value = to.path !== from.path ? to : null
  if (isPublicHost && (to.path === '/admin' || to.path.startsWith('/admin/'))) {
    window.location.replace('https://admin.algohub.top/')
    return false
  }
  if (isAdminHost && to.name === 'login') return { name: 'admin-login' }
  const auth = useAuthStore()
  if (!auth.loaded) {
    await auth.bootstrap()
  }
  if (to.meta.public) {
    return true
  }
  if (to.meta.guestOnly) {
    if (!auth.isLoggedIn && 'redirect' in to.query) {
      const { redirect: _redirect, ...query } = to.query
      return { path: to.path, query, hash: to.hash, replace: true }
    }
    return auth.isLoggedIn ? { path: auth.isAdmin ? '/admin' : '/' } : true
  }
  if (!auth.isLoggedIn) {
    return { name: isAdminHost || to.meta.admin ? 'admin-login' : 'login' }
  }
  // 管理员是管理身份，不需要完成学生的邮箱、入校与刷题账号引导。
  // 邮箱仍可在设置中验证，敏感操作的身份确认由后端单独检查。
  if (auth.isAdmin) {
    if (to.name === 'complete-academic-profile' || to.name === 'home' || to.name === 'verify-email' || to.name === 'select-school' || to.name === 'complete-profile') return { path: '/admin' }
    return true
  }
  if (!auth.me?.emailVerified && to.name !== 'verify-email') return { name: 'verify-email' }
  // 引导第 1 步：邮箱已经验证过（邮箱登录、GitHub 登录的同学）就直接去第 2 步选学校
  if (to.name === 'verify-email') {
    if (!auth.me?.emailVerified) return true
    return auth.me?.onboardingCompleted ? { path: auth.approvedMembership ? '/' : '/ranking' } : { name: 'select-school' }
  }
  if (to.meta.admin && !auth.isAdmin) {
    return { path: '/' }
  }
  if (to.name === 'school-admin') return auth.isSchoolAdmin ? true : { path: '/' }
  if (to.name === 'cohort-admin') return auth.isCohortLeader ? true : { path: '/' }
  if (auth.me?.profileCompletionRequired) return to.name === 'complete-academic-profile' ? true : { name: 'complete-academic-profile' }
  if (to.name === 'complete-academic-profile') return { path: '/' }
  if (!auth.me?.onboardingCompleted && to.name !== 'select-school' && to.name !== 'complete-profile') return { name: 'select-school' }
  if (to.name === 'complete-profile') {
    let chosen = false
    try { chosen = JSON.parse(sessionStorage.getItem(`algohub-onboarding-${auth.me!.id}`) ?? '{}').schoolChosen === true } catch { /* invalid draft */ }
    if (!chosen) return { name: 'select-school' }
  }
  if (to.name === 'home' && !auth.approvedMembership && auth.me?.onboardingCompleted) return { name: 'ranking' }
  // 未入校的用户也能绑定 OJ 账号、使用总榜和设置；校内功能仍需先加入学校。
  const withoutSchool = ['messages', 'friends', 'reminders', 'public-profile', 'announcements', 'ranking', 'accounts', 'accounts-sync-log', 'luogu-sync', 'qoj-sync', 'leetcode-sync', 'settings', 'profile', 'me', 'complete-profile']
  if (!auth.approvedMembership && to.name !== 'select-school' && !withoutSchool.includes(String(to.name))) return { name: 'select-school' }
  // 已入校但还没走完引导（先申请、管理员先批了）的同学要留在引导里，否则和上面「没完成引导就去选学校」来回跳转卡死页面
  if (auth.approvedMembership && auth.me?.onboardingCompleted && to.name === 'select-school') return { path: '/' }
  return true
})

// 访问统计：进了哪个页面报一次路由名（管理员和管理员域名不计，见 api/visits.ts）
router.afterEach((to, _from, failure) => {
  if (isNavigationFailure(failure, NavigationFailureType.duplicated)) pendingNavigation.value = null
  else finishNavigation(to)
  if (!failure) recordVisit(to.name)
})

router.onError((error, to) => {
  // 旧标签页可能引用已经发布过的分包。仅自动恢复一次，且去目标页而非重放已消费的 OAuth 操作。
  if (/Failed to fetch dynamically imported module|Importing a module script failed|Loading chunk.*failed|Unable to preload CSS/i.test(error.message)) {
    const key = `algohub-chunk-recovery:${import.meta.url}`
    try {
      if (Date.now() - Number(sessionStorage.getItem(key) ?? 0) > 60000) {
        sessionStorage.setItem(key, String(Date.now()))
        window.location.replace(router.resolve(to).href)
        return
      }
    } catch { /* 浏览器不允许保存恢复标记时，保留手动重试，避免刷新循环。 */ }
  }
  if (pendingNavigation.value?.fullPath !== to.fullPath) return
  finishNavigation(to)
  ElMessage.error({ message: '页面加载失败，请刷新后重试', grouping: true })
})

export default router
