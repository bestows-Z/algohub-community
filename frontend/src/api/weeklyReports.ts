import { del, get, post } from '@/api/client'

export type ReportStatus = 'DRAFT' | 'SUBMITTED' | 'REVIEWED' | 'RETURNED' | 'NOT_CREATED'
export const reportLabels: Record<string, string> = { DRAFT: '草稿', SUBMITTED: '已提交', REVIEWED: '已评价', RETURNED: '退回后未重新提交', NOT_CREATED: '未创建' }
export const mailLabels: Record<string, string> = { PENDING: '待发送', SENDING: '发送中', SENT: '邮件已发出', FAILED: '发送失败', SKIPPED: '已跳过', NONE: '未生成提醒' }
export const cycleLabels: Record<string, string> = { PENDING: '待统计', COMPLETE: '已统计', FAILED: '统计失败，等待重试', CANCELLED: '已停止统计' }
export const reportTime = (value: string | null | undefined) => value ? new Date(value).toLocaleString('zh-CN', { timeZone: 'Asia/Shanghai', hour12: false }) : '—'
export interface ReportRow { id: number; userId?: number; schoolId?: number; schoolName?: string; weekStart: string; displayName?: string; username?: string; studentNo?: string; enrollmentYear?: number; status: ReportStatus; version: number; submittedAt: string | null }
export interface ReportEvent { version: number; status: ReportStatus; action: string; summary: string; problems: string; plan: string; comment: string; actorName: string | null; happenedAt: string }
export interface ReportDetail extends ReportRow { summary: string; problems: string; plan: string; history: ReportEvent[] }
export interface ReportContext { weekStart: string; timezone: string; deadline: string | null; membership: { schoolId: number; schoolName: string } | null }
export interface DraftInput { id: number | null; weekStart: string; version: number; summary: string; problems: string; plan: string }
export interface ReportScope { schoolId: number; enrollmentYear?: number }
export interface ReportRule { effectiveWeek?: string; dayOfWeek: number; time: string; leadHours: number; emailEnabled: boolean }
export interface ReportSettings { enabled: boolean; configured: boolean; version: number; current: ReportRule; next: ReportRule; nextCutoffAt: string | null; nextReminderAt: string | null; automaticRemindersEnabled?: boolean; mailConfigured?: boolean }
export interface ReportCycle { id: number; weekStart: string; cutoffAt: string; generatedAt: string | null; status: string; error: string | null; eligibleCount: number | null; submittedCount: number | null; missingCount: number | null }
export interface MissingReport { id: number; userId: number; displayName: string; username: string; enrollmentYear: number | null; className: string | null; studentNo: string | null; cutoffStatus: ReportStatus; lastEditedAt: string | null; cutoffAt: string; generatedAt: string; reportId: number | null; currentStatus: ReportStatus | null; submittedAt: string | null; lateAt: string | null; reminderStatus: string | null; reminderNote: string | null; sentAt: string | null; currentReminderStatus: string | null; currentReminderNote: string | null; currentSentAt: string | null; hasVerifiedEmail: boolean }
export interface Page<T> { rows: T[]; total: number }
const root = (scope: ReportScope) => '/weekly-report-management/' + scope.schoolId
const scoped = (scope: ReportScope, path: string) => root(scope) + path + (scope.enrollmentYear ? '?year=' + scope.enrollmentYear : '')
const scopeParams = (scope: ReportScope) => ({ year: scope.enrollmentYear })
export const loadReportContext = () => get<ReportContext>('/weekly-reports/context')
export const loadOwnReports = (page: number) => get<Page<ReportRow>>('/weekly-reports', { page })
export const loadOwnReport = (id: number) => get<ReportDetail>('/weekly-reports/' + id)
export const findOwnReport = (week: string) => get<ReportDetail | null>('/weekly-reports/find', { week })
export const saveOwnReport = (input: DraftInput, submit: boolean) => post<ReportDetail>('/weekly-reports/' + (submit ? 'submit' : 'draft'), input)
export const deleteOwnDraft = (id: number, version: number) => del('/weekly-reports/' + id + '?version=' + version)
export const reportAvailability = (scope: ReportScope) => get<{ manager: boolean; statisticsEnabled: boolean }>(root(scope) + '/availability', scopeParams(scope))
export const loadReportSettings = (scope: ReportScope) => get<ReportSettings>(root(scope) + '/settings')
export const saveReportSettings = (scope: ReportScope, input: ReportRule & { enabled: boolean; version: number; confirmDisable: boolean }) => post<ReportSettings>(root(scope) + '/settings', input)
export const loadManagedReports = (scope: ReportScope, params: { week: string; status: string; q: string; page: number }) => get<Page<ReportRow>>(root(scope) + '/reports', { ...scopeParams(scope), ...params })
export const loadManagedReport = (scope: ReportScope, id: number) => get<ReportDetail>(root(scope) + '/reports/' + id, scopeParams(scope))
export const reviewReport = (scope: ReportScope, id: number, input: { version: number; returned: boolean; comment: string }) => post<ReportDetail>(scoped(scope, '/reports/' + id + '/review'), input)
export const loadReportCycles = (scope: ReportScope) => get<ReportCycle[]>(root(scope) + '/cycles', scopeParams(scope))
export const loadMissingReports = (scope: ReportScope, params: { week: string; className: string; q: string; late: string; reminder: string; page: number }) => get<Page<MissingReport>>(root(scope) + '/missing', { ...scopeParams(scope), ...params })
export const loadReminderOverview = (scope: ReportScope, week: string) => get<{ status: string; reason: string | null; count: number }[]>(root(scope) + '/reminders', { week })
export function currentReportWeek() {
  const day = new Intl.DateTimeFormat('sv-SE', { timeZone: 'Asia/Shanghai', year: 'numeric', month: '2-digit', day: '2-digit' }).format(new Date())
  const date = new Date(day + 'T12:00:00Z'); date.setUTCDate(date.getUTCDate() - (date.getUTCDay() + 6) % 7)
  return date.toISOString().slice(0, 10)
}
