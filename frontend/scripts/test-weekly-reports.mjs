import { readFileSync } from 'node:fs'
import vm from 'node:vm'
import assert from 'node:assert/strict'
import { parse, compileScript } from '@vue/compiler-sfc'
import ts from 'typescript'
import * as vue from 'vue'

const settle = async () => { await vue.nextTick(); await new Promise(resolve => setImmediate(resolve)) }
const deferred = () => { let resolve, reject; const promise = new Promise((ok, fail) => { resolve = ok; reject = fail }); return { promise, resolve, reject } }
class ApiError extends Error { constructor(kind, message) { super(message); this.kind = kind } }
function harness(view, api, initialProps = {}, confirm = async () => {}) {
  const { descriptor } = parse(readFileSync(new URL('../src/' + view, import.meta.url), 'utf8'))
  const script = compileScript(descriptor, { id: 'weekly-reports-regression' })
  const code = ts.transpileModule(script.content, { compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 } }).outputText
  const exports = {}, cleanup = [], mounted = [], emitted = [], feedback = []
  vm.runInNewContext(code, { exports, Intl, Date, require(name) {
    if (name === 'vue') return { ...vue, onMounted: fn => mounted.push(fn), onBeforeUnmount: fn => cleanup.push(fn) }
    if (name === 'vue-router') return { onBeforeRouteLeave() {} }
    if (name === 'element-plus') return { ElMessage: { success: text => feedback.push(text) }, ElMessageBox: { confirm } }
    if (name === '@/api/client') return { ApiError, notifyError: e => feedback.push(e.message) }
    if (name === '@/api/weeklyReports') return { currentReportWeek: () => '2026-11-02', ...api }
    if (name === '@/stores/auth') return { useAuthStore: () => ({ me: { id: 1 }, refreshMe: async () => feedback.push('identity-refreshed') }) }
    return {}
  } })
  const props = vue.reactive(initialProps), effect = vue.effectScope()
  const page = effect.run(() => exports.default.setup(props, { expose() {}, emit: (...args) => emitted.push(args) }))
  return { page, props, emitted, feedback, mount() { mounted.forEach(fn => fn()) }, destroy() { cleanup.forEach(fn => fn()); effect.stop() } }
}
const scopeA = { schoolId: 1, enrollmentYear: 2026 }, scopeB = { schoolId: 2, enrollmentYear: 2025 }
const report = { id: 10, userId: 2, schoolId: 1, weekStart: '2026-11-02', version: 1, status: 'SUBMITTED', summary: 'submitted', problems: '', plan: '', history: [] }
const settings = { enabled: true, version: 1, current: { dayOfWeek: 7, time: '22:00', leadHours: 6, emailEnabled: true }, next: { dayOfWeek: 7, time: '22:00', leadHours: 6, emailEnabled: true } }

// Delayed school results may not replace a newly selected management scope.
{
  const replies = []
  const h = harness('components/weeklyreport/ReportManagement.vue', {
    reportAvailability: async () => ({ manager: false, statisticsEnabled: true }),
    loadManagedReports: scope => { const d = deferred(); replies.push({ scope, ...d }); return d.promise }
  }, { scope: scopeA })
  await settle(); h.props.scope = scopeB; await settle()
  replies[1].resolve({ rows: [{ id: 2 }], total: 1 }); await settle()
  replies[0].resolve({ rows: [{ id: 1 }], total: 1 }); await settle()
  assert.equal(h.page.rows.value[0].id, 2); h.destroy()
}
// A denied subsequent request removes every visible record and open review.
{
  let denied = false
  const h = harness('components/weeklyreport/ReportManagement.vue', {
    reportAvailability: async () => ({ manager: false, statisticsEnabled: true }),
    loadManagedReports: async () => { if (denied) throw new ApiError('FORBIDDEN', 'revoked'); return { rows: [report], total: 1 } }
  }, { scope: scopeA })
  await settle(); h.page.detail.value = report; denied = true; await h.page.load()
  assert.equal(h.page.rows.value.length, 0); assert.equal(h.page.detail.value, null); assert.equal(h.page.allowed.value, false)
  h.destroy()
}
// An old return confirmation cannot act on another student's report.
{
  const confirmation = deferred(); let calls = 0
  const h = harness('components/weeklyreport/ReportReview.vue', { reviewReport: async () => { calls++; return report } }, { scope: scopeA, report }, () => confirmation.promise)
  h.page.comment.value = 'please revise'; const pending = h.page.decide(true)
  h.props.report = { ...report, id: 11 }; await settle(); confirmation.resolve(); await pending
  assert.equal(calls, 0); h.destroy()
}
// Captains cannot evaluate their own report even when invoking the handler directly.
{
  let calls = 0
  const h = harness('components/weeklyreport/ReportReview.vue', { reviewReport: async () => { calls++ } }, { scope: scopeA, report: { ...report, userId: 1 } })
  h.page.comment.value = 'self'; await h.page.decide(false); assert.equal(calls, 0); h.destroy()
}
// School disable confirmation is fenced against a scope change.
{
  let calls = 0; const confirmation = deferred()
  const h = harness('components/weeklyreport/ReportSettings.vue', { loadReportSettings: async () => settings, saveReportSettings: async () => { calls++; return settings } }, { scope: scopeA }, () => confirmation.promise)
  await settle(); h.page.enabled.value = false; const pending = h.page.save(); h.props.scope = scopeB; await settle()
  confirmation.resolve(); await pending; assert.equal(calls, 0); h.destroy()
}
// Saved drafts survive failures, and late save results cannot populate a different week.
{
  const response = deferred()
  const h = harness('components/weeklyreport/ReportEditor.vue', { saveOwnReport: () => response.promise }, { report: null, weekStart: '2026-11-02', canWrite: true })
  h.page.summary.value = 'private draft'; const pending = h.page.save(false)
  h.props.weekStart = '2026-11-09'; await settle(); response.resolve(report); await pending
  assert.equal(h.emitted.filter(([name]) => name === 'saved').length, 0); assert.equal(h.page.summary.value, '')
  h.destroy()
  const f = harness('components/weeklyreport/ReportEditor.vue', { saveOwnReport: async () => { throw new ApiError('NETWORK', 'offline') } }, { report: null, weekStart: '2026-11-02', canWrite: true })
  f.page.summary.value = 'retain this'; await f.page.save(false); assert.equal(f.page.summary.value, 'retain this'); assert.equal(f.page.saving.value, false); f.destroy()
}
// A late missing-list response does not bring records from a former school back.
{
  const replies = []
  const h = harness('components/weeklyreport/ReportMissing.vue', {
    loadReportCycles: async () => [], loadMissingReports: scope => { const d = deferred(); replies.push({ scope, ...d }); return d.promise }
  }, { scope: scopeA, manager: false })
  await settle(); h.props.scope = scopeB; await settle(); replies[1].resolve({ rows: [{ id: 2 }], total: 1 }); await settle(); replies[0].resolve({ rows: [{ id: 1 }], total: 1 }); await settle()
  assert.equal(h.page.rows.value[0].id, 2); h.destroy()
}
// Detail loading is explicit; week lookup works independently of the current history page.
{
  const response = deferred()
  const h = harness('views/WeeklyReportsView.vue', { findOwnReport: () => response.promise })
  h.page.context.value = { weekStart: '2026-11-02', membership: { schoolId: 1 } }
  h.page.page.value = 2; h.page.rows.value = []; h.page.editing.value = report
  const pending = h.page.create(); await settle(); assert.equal(h.page.detailLoading.value, true)
  await h.page.create(); await h.page.open({ id: 99 }); assert.equal(h.page.detailLoading.value, true)
  response.resolve({ ...report, id: 12 }); await pending
  assert.equal(h.page.editing.value.id, 12); assert.equal(h.page.detailLoading.value, false); assert.equal(h.page.editorKey.value, 1)
  h.destroy()
}
// Confirming discard in the same new week always creates a new editor instance.
{
  let confirmations = 0
  const h = harness('views/WeeklyReportsView.vue', { findOwnReport: async () => null }, {}, async () => { confirmations++ })
  h.page.context.value = { weekStart: '2026-11-02', membership: { schoolId: 1 } }; h.page.week.value = '2026-11-02'; h.page.dirty.value = true
  await h.page.create(); assert.equal(confirmations, 1); assert.equal(h.page.editorKey.value, 1); assert.equal(h.page.dirty.value, false)
  h.destroy()
}
console.log('Weekly reports: scope changes, permission loss, stale confirmations, self-review, delayed details and draft recovery passed')
