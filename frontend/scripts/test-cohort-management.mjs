import { readFileSync } from 'node:fs'
import vm from 'node:vm'
import assert from 'node:assert/strict'
import { parse, compileScript } from '@vue/compiler-sfc'
import ts from 'typescript'
import * as vue from 'vue'

const settle = async () => { await vue.nextTick(); await new Promise(resolve => setImmediate(resolve)) }
const deferred = () => {
  let resolve, reject
  const promise = new Promise((ok, fail) => { resolve = ok; reject = fail })
  return { promise, resolve, reject }
}
class ApiError extends Error { constructor(kind, message) { super(message); this.kind = kind } }
const compile = source => ts.transpileModule(source, { compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 } }).outputText
function harness(view, api, initialProps = {}, confirm = async () => {}) {
  const { descriptor } = parse(readFileSync(new URL('../src/' + view, import.meta.url), 'utf8'))
  const script = compileScript(descriptor, { id: 'cohort-regression' })
  const exports = {}, cleanup = [], mounted = [], feedback = []
  vm.runInNewContext(compile(script.content), { exports, require(name) {
    if (name === 'vue') return { ...vue, onMounted: fn => mounted.push(fn), onBeforeUnmount: fn => cleanup.push(fn) }
    if (name === 'element-plus') return { ElMessage: { success: text => feedback.push(text) }, ElMessageBox: { confirm } }
    if (name === '@/api/client') return { ApiError, notifyError: error => feedback.push(error.message) }
    if (name === '@/api/cohortLeaders') return api
    if (name === '@/stores/auth') return { useAuthStore: () => ({ refreshMe: async () => feedback.push('identity-refreshed') }) }
    return {}
  } })
  const props = vue.reactive(initialProps), scope = vue.effectScope()
  const page = scope.run(() => exports.default.setup(props, { expose() {}, emit() {} }))
  return { page, props, feedback, mount() { mounted.forEach(fn => fn()) }, destroy() { cleanup.forEach(fn => fn()); scope.stop() } }
}
const scopeA = { schoolId: 1, schoolName: 'Synthetic A', enrollmentYear: 2026 }
const scopeB = { schoolId: 1, schoolName: 'Synthetic A', enrollmentYear: 2025 }
const member = { id: 10, userId: 11, displayName: 'Synthetic member', status: 'PENDING', className: 'A', studentNo: '100', canReview: true, canEdit: true }

// Old school results cannot replace the current list or populate candidates.
{
  const requests = [], searchReply = deferred()
  const h = harness('components/admin/CohortLeaderGrants.vue', {
    loadLeaderGrants: school => { const d = deferred(); requests.push({ school, ...d }); return d.promise },
    searchLeaderCandidates: () => searchReply.promise,
  }, { schoolId: 1 })
  const search = h.page.search()
  h.props.schoolId = 2; await settle()
  requests[1].resolve([{ id: 2 }]); await settle()
  requests[0].resolve([{ id: 1 }]); searchReply.resolve([{ userId: 11, enrollmentYear: 2026 }])
  await search; await settle()
  assert.equal(h.page.grants.value[0].id, 2)
  assert.equal(h.page.candidates.value.length, 0)
  h.destroy()
}
// Duplicate saves share one mutation; a late result cannot affect another school.
{
  const response = deferred(); let calls = 0
  const h = harness('components/admin/CohortLeaderGrants.vue', {
    loadLeaderGrants: async () => [], grantCohortLeader: () => { calls++; return response.promise },
  }, { schoolId: 1 })
  h.page.candidates.value = [{ userId: 11, enrollmentYear: 2026 }]
  h.page.selected.value = 11; await settle()
  const one = h.page.assign(), two = h.page.assign()
  assert.equal(calls, 1)
  h.props.schoolId = 2; await settle()
  response.resolve({ id: 1 }); await Promise.all([one, two])
  assert.equal(h.feedback.length, 0)
  h.destroy()
}
// Old confirmation must not revoke access in a different school.
{
  const confirmation = deferred(); let revoked = 0
  const h = harness('components/admin/CohortLeaderGrants.vue', {
    loadLeaderGrants: async () => [], revokeCohortLeader: async () => { revoked++ },
  }, { schoolId: 1 }, () => confirmation.promise)
  const pending = h.page.revoke({ id: 1, displayName: 'Synthetic', enrollmentYear: 2026 })
  h.props.schoolId = 2; await settle(); confirmation.resolve(); await pending
  assert.equal(revoked, 0)
  h.destroy()
}
// Lost manager authorization clears existing grants/candidates on a failed search.
{
  const h = harness('components/admin/CohortLeaderGrants.vue', {
    loadLeaderGrants: async () => [{ id: 1 }],
    searchLeaderCandidates: async () => { throw new ApiError('FORBIDDEN', 'manager revoked') },
  }, { schoolId: 1 })
  await settle()
  h.page.candidates.value = [{ userId: 11, enrollmentYear: 2026 }]
  await h.page.search()
  assert.equal(h.page.grants.value.length, 0)
  assert.equal(h.page.candidates.value.length, 0)
  assert.equal(h.page.searching.value, false)
  h.destroy()
}
// Out-of-order cohort responses cannot mix lists or edits.
{
  const requests = []
  const h = harness('views/CohortAdminView.vue', {
    loadCohortScopes: async () => [scopeA, scopeB],
    loadCohortMembers: scope => { const d = deferred(); requests.push({ scope, ...d }); return d.promise },
  })
  h.mount(); await settle()
  h.page.editing.value = member
  h.page.selected.value = '1:2025'; await settle()
  requests[1].resolve({ rows: [{ ...member, id: 20 }], total: 1 }); await settle()
  requests[0].resolve({ rows: [member], total: 1 }); await settle()
  assert.equal(h.page.members.value[0].id, 20)
  assert.equal(h.page.editing.value, null)
  h.destroy()
}
// Previous-cohort confirmations cannot submit a review after switching scope.
{
  const confirmation = deferred(); let reviews = 0
  const h = harness('views/CohortAdminView.vue', {
    loadCohortScopes: async () => [scopeA, scopeB], loadCohortMembers: async () => ({ rows: [member], total: 1 }),
    reviewCohortMember: async () => { reviews++ },
  }, {}, () => confirmation.promise)
  h.mount(); await settle()
  const pending = h.page.review(member, true)
  h.page.selected.value = '1:2025'; await settle()
  confirmation.resolve(); await pending
  assert.equal(reviews, 0)
  assert.equal(h.feedback.length, 0)
  h.destroy()
}
// Revocation clears the old student data before refreshing identity and scopes.
{
  let revoked = false
  const h = harness('views/CohortAdminView.vue', {
    loadCohortScopes: async () => revoked ? [] : [scopeA],
    loadCohortMembers: async () => {
      if (revoked) throw new ApiError('FORBIDDEN', 'grant revoked')
      return { rows: [member], total: 1 }
    },
  })
  h.mount(); await settle()
  h.page.editing.value = member; revoked = true
  await h.page.load(); await settle()
  assert.equal(h.page.members.value.length, 0)
  assert.equal(h.page.total.value, 0)
  assert.equal(h.page.editing.value, null)
  assert.equal(h.page.scopes.value.length, 0)
  assert.ok(h.feedback.includes('identity-refreshed'))
  h.destroy()
}
// Profile updates whitelist the fields instead of posting the entire member object.
{
  const exports = {}, sent = []
  vm.runInNewContext(compile(readFileSync(new URL('../src/api/cohortLeaders.ts', import.meta.url), 'utf8')), {
    exports, require: () => ({ post: async (url, body) => sent.push({ url, body }) }),
  })
  await exports.updateCohortMember(scopeA, { ...member, enrollmentYear: 2025, schoolId: 2 })
  assert.equal(sent[0].url, '/cohort-admin/1/2026/members/10/profile')
  assert.deepEqual(Object.keys(sent[0].body).sort(), ['className', 'studentNo'])
}
// Navigation gives the delegated workspace, never school/platform management.
{
  const exports = {}
  vm.runInNewContext(compile(readFileSync(new URL('../src/components/layout/nav.ts', import.meta.url), 'utf8')), { exports })
  const paths = (...roles) => exports.navGroupsForRole(...roles).flatMap(group => group.items.map(item => item.path))
  assert.ok(!paths(false, false, false).includes('/cohort-admin'))
  assert.ok(paths(false, false, true).includes('/cohort-admin'))
  assert.ok(!paths(false, false, true).includes('/school-admin'))
  assert.ok(!paths(false, true, true).includes('/cohort-admin'))
  assert.ok(!paths(true, false, true).includes('/cohort-admin'))
}
console.log('Cohort management: scope isolation, duplicate submissions, stale confirmations, revocation, field whitelist and navigation passed')
