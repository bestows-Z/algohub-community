export interface Membership {
  id: number
  schoolId: number
  schoolName: string
  status: 'PENDING' | 'APPROVED' | 'REJECTED'
  enrollmentYear: number | null
  className: string | null
  /** 学号：成员接口会返回；只在更新资料时原样带回，免得被清空 */
  studentNo?: string | null
  memberRole: string
}

export interface PrivacySettings {
  profileVisibility: 'PUBLIC' | 'SCHOOL' | 'PRIVATE'
  publicActivity: boolean
  showOnRanking: boolean
  showAccounts: boolean
  showStudentNo: boolean
  showEmail: boolean
}

export interface OauthBinding {
  provider: string
  login: string | null
  displayName: string | null
  profileUrl: string | null
  avatarUrl: string | null
  linkedAt: string | null
}

export interface Me {
  id: number
  username: string
  displayName: string
  email: string | null
  role: 'STUDENT' | 'ADMIN'
  managedSchools?: { id: number; name: string }[]
  managedCohorts?: { schoolId: number; schoolName: string; enrollmentYear: number }[]
  avatarUrl: string | null
  bio: string | null
  hasPassword: boolean
  onboardingCompleted: boolean
  profileCompletionRequired: boolean
  nicknameNeedsReview: boolean
  enrollmentYear: number | null
  className: string | null
  studentNo: string | null
  emailVerified: boolean
  lastLoginAt: string | null
  createdAt: string | null
  memberships: Membership[]
  privacy: PrivacySettings
  bindings: OauthBinding[]
}

export interface TokenView {
  accessToken: string
  tokenType: string
  expiresInSeconds: number
  expiresAt: string
  user: Me
}

export interface SessionInfo {
  hash: string
  createdAt: string | null
  device: string
  current: boolean
}

export interface AuthEvent {
  event: string
  ip: string | null
  userAgent: string | null
  detail: string | null
  createdAt: string
}

export interface PlatformAccount {
  id: number
  userId: number
  platform: string
  platformUid: string | null
  handle: string
  ownershipStatus: 'UNVERIFIED' | 'RESOLVED' | 'VERIFIED' | 'MISMATCH'
  authMode: string
  syncStatus: string
  supportsHistory: boolean
  historyComplete: boolean
  lastSuccessAt: string | null
  lastAttemptAt: string | null
  lastErrorKind: string | null
  lastErrorMsg: string | null
  note: string | null
  hasCredential: boolean
  /** ACTIVE 可用；INVALID 源站判定已失效（不再使用，等待重新设置）；没有凭据为 null */
  credentialStatus?: 'ACTIVE' | 'INVALID' | null
  /** 平台统计题数：最近一次成功快照里的数字；从没取得过为 null（不是 0） */
  platformSolvedTotal?: number | null
  platformSolvedAt?: string | null
  /** 已收录的题目明细（去重通过题数）、其中有通过时间的、其中来自牛客 Tracker 的 */
  collectedProblems?: number
  timedProblems?: number
  trackerProblems?: number
  /** 各数据项的同步状态 */
  dataItems?: AccountDataItem[]
  /** 牛客 Tracker：通过题数、其中与竞赛题同题已合并的、题号相同但标题不同另记的 */
  trackerAccepted?: number | null
  trackerMerged?: number | null
  trackerConflicts?: number | null
  /** 比赛与 Rating（Codeforces、AtCoder）：参加场次、计分场次、当前与最高 Rating；没有记录时不给 */
  contests?: number | null
  ratedContests?: number | null
  currentRating?: number | null
  maxRating?: number | null
  /** 归属变化说明，例如经申诉审核后转为个人关注 */
  ownershipNote?: string | null
  /** VERIFIED 正式绑定；CLAIMABLE 待认领；WATCHING 个人关注 */
  bindingKind?: 'VERIFIED' | 'CLAIMABLE' | 'WATCHING'
  proofSupported?: boolean
  username?: string
  displayName?: string
}

/** 账号某一数据项的同步状态：平台统计题数 / 逐题通过列表 / 提交历史。 */
export interface AccountDataItem {
  item: 'TOTALS' | 'SOLVED_LIST' | 'HISTORY' | 'RATING'
  status: 'SUCCESS' | 'FAILED' | 'NEEDS_CREDENTIAL'
  lastAttemptAt: string | null
  lastSuccessAt: string | null
  errorKind: string | null
  errorMsg: string | null
  /** 最近一次成功时的数量；失败时保留上次的值 */
  observedCount: number | null
  detail: string | null
}

/** 一场比赛的参赛记录；不计分的场次 rated 为 false。 */
export interface RatedContestRow {
  contestKey: string
  contestName: string
  place: number | null
  oldRating: number | null
  newRating: number | null
  performance: number | null
  rated: boolean
  finishedAt: string
}

/** 一个账号的比赛与 Rating 记录，按时间先后。 */
export interface RatingSeries {
  accountId: number
  platform: string
  handle: string
  contests: RatedContestRow[]
}

/** 绑定申请（验证串）。 */
export interface BindingRequest {
  id: number
  platform: string
  platformUid: string
  handle: string
  /** 重新申请时交回的查询标识（码蹄集是数字 ID，其他平台是 handle） */
  lookupKey: string
  token: string
  expiresAt: string
  expired: boolean
  attempts: number
  maxAttempts: number
  /** 账号已被他人验证：核验成功后转为申诉 */
  ownedByOther: boolean
  location: string | null
  /** 源站特有的说明，例如力扣简介需要审核 */
  note: string | null
  validMinutes: number | null
  /** 直接改资料的设置页（如 AtCoder 的 https://atcoder.jp/settings）；没有时「打开」链到平台首页 */
  settingsUrl?: string | null
  instructions: string
}

/** 查询确认身份的结果。 */
export interface IdentityLookup {
  platform: string
  platformUid: string
  handle: string
  displayName: string
  /** 申请验证时交回的查询标识（码蹄集是数字 ID，其他平台是 handle） */
  lookupKey: string
  proofSupported: boolean
  state: 'AVAILABLE' | 'MINE_PENDING' | 'MINE_VERIFIED' | 'MINE_UNVERIFIED' | 'OWNED_BY_OTHER'
  accountId: number | null
  requestId: number | null
}

export interface OwnershipAppeal {
  id: number
  platform: string
  platformUid: string
  handle: string
  status: 'PENDING' | 'APPROVED' | 'REJECTED' | 'WITHDRAWN'
  reason: string | null
  provedAt: string
  createdAt: string
  reviewedAt: string | null
  reviewNote: string | null
}

export interface PlatformCapability {
  platform: string
  displayName: string
  homeUrl: string
  publicIdentity: boolean
  submissionHistory: boolean
  totalsSnapshot: boolean
  requiresCredential: boolean
  difficultySource: string
  tagSource: string
  contestList: boolean
  notes: string
  syncSupported: boolean
  /** 公开资料归属证明方式；为空表示只能添加为个人关注 */
  ownershipProof: { location: string; note: string; validMinutes: number } | null
}

export interface NavResource {
  id: number
  category: string
  title: string
  url: string
  description: string | null
  sortOrder: number
  enabled: boolean
}

export interface Contest {
  id: number
  platform: string
  contestKey: string
  title: string
  startAt: string
  endAt: string | null
  durationSec: number | null
  status: string
  category: string | null
  url: string | null
  source: string
}

export interface SyncTask {
  id: number
  taskType: string
  platform: string | null
  accountId: number | null
  status: string
  attempt: number
  maxAttempt: number
  errorKind: string | null
  errorMsg: string | null
  durationMs: number | null
  updatedAt: string
  handle?: string
  username?: string
}

export interface RankingRow {
  rank: number
  position: number
  userId: number
  displayName: string
  /** 同学选的 OJ 头像；没选或取不到时为空，显示字标 */
  avatarUrl?: string | null
  enrollmentYear: number | null
  className: string | null
  schoolName?: string | null
  solved?: number
  uniqueSolved?: number
  score?: number
  hasUnverifiedSource: boolean
  lastSuccessAt?: string | null
}

export interface Ranking {
  scope?: 'GLOBAL' | 'SCHOOL'
  participantCount: number
  myRank: RankingRow | null
  neighbors: RankingRow[]
  myRankNote: string
  /**
   * 没上榜的原因（上榜时为空）：NO_SCHOOL 没选学校、HIDDEN 关了参与排行、PRIVATE_PROFILE 档案仅自己可见、
   * NO_VERIFIED 没有验证过归属的账号、YEAR / CLASS / PLATFORM 不在当前筛选范围、NO_DATA 这段时间没有可计入的数据
   */
  myRankReason?: 'NO_SCHOOL' | 'HIDDEN' | 'PRIVATE_PROFILE' | 'NO_VERIFIED' | 'YEAR' | 'CLASS' | 'PLATFORM' | 'NO_DATA' | null
  /** 年级、班级筛选的选项：参与排行的同学有哪些年级、所选年级里有哪些班 */
  yearOptions?: number[]
  classOptions?: string[]
  type: string
  metric: string
  description: string
  note: string
  from?: string
  to?: string
  rows: RankingRow[]
  coverage: { platform: string; users: number; lastSuccessAt: string | null }[]
}

export interface HeatmapData {
  from: string
  to: string
  timezone: string
  metrics: Record<string, Record<string, number>>
  metricNotes: Record<string, string>
}

export interface SchoolMember {
  id: number
  userId: number
  username: string
  displayName: string
  studentNo: string | null
  enrollmentYear: number | null
  className: string | null
  status: string
  memberRole: string
  appliedAt: string
  schoolName: string
}
