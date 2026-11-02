/**
 * 外壳导航：电脑侧栏、平板图标栏、手机底栏共用。
 * 图标是 UnoCSS 的 lucide 类名；写在 .ts 里的类名能生成，是因为 uno.config.ts 把 src 下的 .ts 也加进了扫描范围。
 * 练习一组（题单、题库、算法地图、训练活动、收藏、AI 分析）暂不放进导航，页面与路由保留。
 */
export interface NavItem {
  path: string
  /** 侧栏里的名称 */
  label: string
  /** 图标栏、底栏里的短名称 */
  short: string
  icon: string
  /** 显示平台账号的待处理数 */
  pending?: boolean
}

export interface NavGroup {
  /** 分组名；第一组没有 */
  title: string
  items: NavItem[]
  adminOnly?: boolean
}

export const NAV_GROUPS: NavGroup[] = [
  {
    title: '',
    items: [
      { path: '/', label: '数据总览', short: '总览', icon: 'i-lucide-house' },
      { path: '/ranking', label: '刷题排行', short: '排行', icon: 'i-lucide-trophy' },
      { path: '/profile', label: '个人档案', short: '档案', icon: 'i-lucide-chart-column' },
      { path: '/accounts', label: '平台账号', short: '账号', icon: 'i-lucide-link', pending: true }
    ]
  },
  {
    title: '更多',
    items: [
      { path: '/announcements', label: '公告与资源', short: '公告', icon: 'i-lucide-megaphone' },
      { path: '/activity', label: '公开动态', short: '动态', icon: 'i-lucide-activity' },
      { path: '/friends', label: '关注与好友', short: '好友', icon: 'i-lucide-users-round' },
      { path: '/messages', label: '消息', short: '消息', icon: 'i-lucide-mail' },
      { path: '/reminders', label: '目标与提醒', short: '提醒', icon: 'i-lucide-bell' },
      { path: '/contests', label: '比赛日历', short: '比赛', icon: 'i-lucide-calendar' },
      { path: '/settings', label: '设置与安全', short: '设置', icon: 'i-lucide-settings' }
    ]
  },
  {
    title: '管理',
    adminOnly: true,
    items: [{ path: '/admin', label: '管理后台', short: '管理', icon: 'i-lucide-shield' }]
  }
]

/** 管理账号可管理站点、查看总榜与公告，不展示学生个人档案和 OJ 绑定。 */
export const ADMIN_NAV_ITEMS: NavItem[] = [
  { path: '/admin', label: '管理后台', short: '管理', icon: 'i-lucide-shield' },
  { path: '/messages', label: '消息', short: '消息', icon: 'i-lucide-mail' },
  { path: '/ranking', label: '总榜', short: '总榜', icon: 'i-lucide-trophy' },
  { path: '/announcements', label: '公告与资源', short: '公告', icon: 'i-lucide-megaphone' },
  { path: '/settings', label: '设置与安全', short: '设置', icon: 'i-lucide-settings' }
]

export function navGroupsForRole(isAdmin: boolean, isSchoolAdmin = false, isCohortLeader = false): NavGroup[] {
  if (isAdmin) return [{ title: '', items: ADMIN_NAV_ITEMS }]
  const groups = NAV_GROUPS.filter((group) => !group.adminOnly)
  if (isSchoolAdmin) return [...groups, { title: '管理', items: [{ path: '/school-admin', label: '本校管理', short: '本校', icon: 'i-lucide-school' }] }]
  return isCohortLeader ? [...groups, { title: '管理', items: [{ path: '/cohort-admin', label: '本届管理', short: '本届', icon: 'i-lucide-users-round' }] }] : groups
}

/** 当前页是否属于这个导航项（总览只认首页本身，其他的也认下一级路径） */
export function isNavActive(item: NavItem, path: string) {
  if (item.path === '/') return path === '/'
  return path === item.path || path.startsWith(`${item.path}/`)
}

/**
 * 手机底栏：前四项和侧栏第一组一一对应，第五项是「我的」。
 * 动态、比赛、设置、管理在路由里标了 mobileParent: '/me'，在这些页面时「我的」高亮。
 */
export const TAB_ITEMS: NavItem[] = [
  ...NAV_GROUPS[0].items,
  { path: '/me', label: '我的', short: '我的', icon: 'i-lucide-user-round' }
]

export function isTabActive(item: NavItem, path: string, mobileParent?: string) {
  if (item.path === '/me') return path === '/me' || mobileParent === '/me'
  return isNavActive(item, path)
}
