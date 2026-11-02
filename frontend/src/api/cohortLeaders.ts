import { del, get, post } from '@/api/client'

export interface CohortScope {
  schoolId: number
  schoolName: string
  enrollmentYear: number
}
export interface LeaderCandidate {
  userId: number
  username: string
  displayName: string
  enrollmentYear: number
}
export interface CohortGrant extends LeaderCandidate {
  id: number
  createdAt: string
}
export interface CohortMember {
  id: number
  userId: number
  displayName: string
  username: string
  status: 'PENDING' | 'APPROVED' | 'REJECTED'
  enrollmentYear: number
  className: string | null
  studentNo: string | null
  canEdit: boolean
  canReview: boolean
}
export interface CohortMembers { rows: CohortMember[]; total: number }
export const memberStatusLabels = { PENDING: '待审核', APPROVED: '已通过', REJECTED: '已拒绝' }
const grantsPath = (school: number) => '/school-admin/' + school + '/cohort-leaders'
const membersPath = (scope: CohortScope) => '/cohort-admin/' + scope.schoolId + '/' + scope.enrollmentYear + '/members'
export const loadLeaderGrants = (school: number) => get<CohortGrant[]>(grantsPath(school))
export const searchLeaderCandidates = (school: number, q: string) => get<LeaderCandidate[]>(grantsPath(school) + '/candidates', { q })
export const grantCohortLeader = (school: number, userId: number, enrollmentYear: number) =>
  post<CohortGrant>(grantsPath(school), { userId, enrollmentYear })
export const revokeCohortLeader = (school: number, id: number) => del(grantsPath(school) + '/' + id)
export const loadCohortScopes = () => get<CohortScope[]>('/cohort-admin/scopes')
export const loadCohortMembers = (scope: CohortScope, params: { q: string; status: string; page: number }) =>
  get<CohortMembers>(membersPath(scope), params)
export const reviewCohortMember = (scope: CohortScope, id: number, approve: boolean) =>
  post(membersPath(scope) + '/' + id + '/review', { approve })
export const updateCohortMember = (scope: CohortScope, member: CohortMember) =>
  post(membersPath(scope) + '/' + member.id + '/profile', { className: member.className?.trim() || null, studentNo: member.studentNo?.trim() || null })
