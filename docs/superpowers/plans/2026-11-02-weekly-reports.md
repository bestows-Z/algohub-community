# Weekly Reports Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Deliver student weekly reports, scoped evaluation, opt-in school snapshots and private reminders, then validate and publish an Issue and PR.

**Architecture:** Dedicated JDBC services under `weeklyreport` separate report content, access, school scheduling, temporal roster snapshots and notification delivery. Append-only report events and roster history reconstruct deadline state; school cycle records freeze configuration. Reuse existing verified email, study notifications/mail outbox and branded mail without changing the meaning of automatic study reports.

**Tech Stack:** Java 21, Spring Boot/JDBC, PostgreSQL16, Vue3/TypeScript, existing Element Plus components. No new dependencies.

**Spec:** `docs/superpowers/specs/2026-11-02-weekly-reports.md`

## Global Constraints

- All times Asia/Shanghai, Monday natural weeks; school/year scopes remain existing delegated grants.
- Statistics default off; student reporting and evaluation remain available while off.
- Never expose drafts, complete login email, self-list membership, foreign school/year data.
- Configuration changes next week; disable immediately; preserve immutable cutoff snapshots and submitted versions.
- Existing published migrations unchanged. No workflow scope expansion or production operations.

## Task 1: Report lifecycle and temporal history

Files: `backend/src/main/resources/db/migration/V64__weekly_reports.sql`; `weeklyreport/ReportTime.java`, `ReportAccess.java`, `WeeklyReportService.java`, `WeeklyReportController.java`; `weeklyreport/WeeklyReportTest.java`.

Interfaces: `ReportTime.now()/week(Instant)`; `ReportAccess.manager(actor,school)`, `scope(actor,school,year)`; personal `/api/weekly-reports` endpoints; manager `/api/weekly-report-management/{school}` with optional authorized year.

- [ ] Add PostgreSQL fixture tests for draft privacy, submit/return/resubmit and optimistic conflicts, using real service operations and MockMvc roles.
- [ ] Add V64 report/config/cycle/snapshot/reminder and temporal roster tables and triggers, retaining original report versions.
- [ ] Implement scoped owner and manager endpoints, append events on every state transition; return owner drafts but only submitted event bodies to managers.
- [ ] Execute targeted tests with disposable PostgreSQL/Redis; correct any isolation failures.

Example assertions: `assertThat(service.mine(student, week).get("status")).isEqualTo("DRAFT")`; manager detail response must not contain unsent draft summary; stale `version` action must throw `ApiException.conflict`.

## Task 2: School settings and cutoff snapshots

Files: `weeklyreport/ReportSettingsService.java`, `ReportCycleService.java`, management controller; `WeeklyReportScheduleTest.java`.

Interfaces: settings GET/POST with `{enabled,dayOfWeek,time,leadHours,emailEnabled,version,confirmDisable}`; cycles GET and missing GET with week/year/class/query/late/reminder/page filters; `ensureCycle(school,week)`, `processCycle(id,now)`.

- [ ] Add assertions for default-off, next-week configuration, Monday/lead validation, zero missing and failed/retried cutoff reconstruction.
- [ ] Freeze school/week configuration on cycle creation; build roster and report cutoff state from temporal events, including missing year and delayed execution.
- [ ] Preserve missing snapshot and expose later submission separately; deny ordinary students and exclude captain self rows/counts.
- [ ] Check school deletion guards include retained weekly data and execute schedule tests.

## Task 3: Private reminder delivery

Files: `weeklyreport/ReportReminderService.java`, `ReportJobs.java`; existing `reminder/StudyReminderJobs.java`; `WeeklyReportReminderTest.java`.

Interfaces: `generate(cycle,now)`, `deliver(now)`, school-scoped reminder summary; weekly-specific notifications reuse existing inbox and outbox, but are excluded from the generic consumer.

- [ ] Test one notification per user/school/week, actual-send eligibility, opted-out/no-email states, failed mail retry, expiry and disable cancellation with mocked sender only.
- [ ] Generate notifications from reminder-time roster/state; deliver through school-serialized transactions, rechecking each recipient and cutoff before actual mail.
- [ ] Keep generic study reminders unchanged; retain bounded retries and clear, non-sensitive per-school result counts.
- [ ] Execute reminder and existing StudyReminderTest suites.

## Task 4: Student and scoped management UI

Files: `frontend/src/api/weeklyReports.ts`; `views/WeeklyReportsView.vue`; `components/weeklyreport/{ReportEditor,ReportReview,ReportManagement,ReportSettings,ReportMissing}.vue`; routes/nav/school/cohort/mobile views; `scripts/test-weekly-reports.mjs`.

Interfaces: type-safe API contracts match backend; reusable management panel takes `{schoolId,enrollmentYear?}` and only school scope renders settings. Existing school/cohort pages host panels; personal route `/weekly-reports` accessible for historical reading without active school.

- [ ] Add mounted component regressions covering delayed request fencing, scope switches, permission loss and stale edit confirmations.
- [ ] Build student history/editor, manager submitted review, opt-in settings and snapshot/reminder panels from existing UI controls.
- [ ] Integrate nav/desktop/mobile entry points and readable390px layout; never render complete email or self-list data.
- [ ] Wire regression into existing CI-tested list recovery entry; run type-check, all frontend tests and build.

## Task 5: Review, acceptance and publication

Files: `docs/weekly-reports.md`, README and synthetic screenshots.

- [ ] Request independent read-only code review per requesting-code-review skill; fix material findings and add focused regression coverage.
- [ ] Run complete backend verify against disposable database; full frontend checks; real browser student/manager/cohort, deadline/disable flows with synthetic data only.
- [ ] Document migration, consent/privacy, config effective dates, retry/snapshot semantics and actual test evidence.
- [ ] Review staged diff, run Gitleaks, commit/push branch; create authorized Issue and PR with tested results/screenshots and issue-closing link. Verify remote head and CI status; do not merge.
