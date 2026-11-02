# Cohort Leaders Implementation Plan

> Execute tasks inline in this chat, with a separate code review before completion. User authorized implementation on 2026-11-01; no additional execution-choice prompt is required.

**Goal:** Allow school administrators to delegate scoped student membership management to cohort leaders.

**Architecture:** Store cohort grants independently of platform roles. Dedicated cohort endpoints enforce school/year/member permissions on every request; the existing profile-reading policy gains a scoped management branch. Database triggers permanently revoke grants when the underlying membership changes.

**Tech Stack:** Java 21, Spring Boot/MyBatis/JdbcTemplate, PostgreSQL 16, Redis 7; Vue 3, TypeScript, Node.js 22.19.0.

**Spec:** [Approved design](../specs/2026-11-01-cohort-leader-design.md).

## Global constraints

- Keep `app_user.role` unchanged; do not broaden platform or school administrator privileges.
- Use new V63 migration after the upstream V62; never edit published migrations.
- Scope list, count, search, update and profile access by server-side school/year grants.
- Reject profile-update payloads containing fields other than `className` and `studentNo`.
- Membership changes permanently revoke old grants; restoring membership values must not resurrect them.
- Use dedicated loopback PostgreSQL `_test` database and Redis DB 15; no production or shared data.
- Use synthetic identities for tests and screenshots.

## Task 1: Grant model and membership lifecycle

**Files:** Create `backend/src/main/resources/db/migration/V63__school_cohort_leaders.sql`, `school/CohortLeaderMapper.java`, `school/CohortLeaderAccess.java`, `school/CohortLeaderService.java`; add `school/CohortLeaderBoundaryTest.java` and `school/CohortLeaderLifecycleTest.java` under backend test sources.

**Interfaces:** `CohortLeaderAccess.requireManager(long actor, long schoolId)`; `requireScope(long actor, long schoolId, int year)`; `CohortLeaderMapper.scopes(long actor)` returning `List<Map<String,Object>>`; `CohortLeaderService.grant(long actor,long schoolId,long userId,int year)` returning a grant map.

- [x] Add failing integration tests that give a school manager a local synthetic member and verify duplicate grant idempotency and membership-change revocation.
- [x] Prepare Java 21 and dedicated PostgreSQL/Redis services and run the new integration tests. The first attempt was blocked by Maven cache access before compilation; a valid pre-implementation red run was not recorded.
- [x] Create grant table and active unique index:

```sql
create unique index school_cohort_leader_active_unique
on school_cohort_leader(school_id, enrollment_year, user_id)
where revoked_at is null;
```

- [x] Add membership and school-admin-promotion triggers, grant validation, scoped access checks and audit writes. Acquire involved platform-administrator users first, then other users by ID with FOR NO KEY UPDATE, before membership/grant locks; retain compatibility with existing audit foreign-key checks.
- [x] Run both integration classes and fix failures before building on the model.

## Task 2: Manager and leader APIs

**Files:** Create `school/CohortLeaderGrantController.java`, `school/CohortAdminController.java`; modify `admin/AdminSchoolController.java`; extend the boundary test.

**Interfaces:** Manager grant endpoints under `/api/school-admin/{schoolId}/cohort-leaders`; leader endpoints under `/api/cohort-admin/{schoolId}/{year}/members`; service methods `members`, `review`, `profile`, `revoke`, `candidates`, `grants`.

- [x] Add API tests for cross-school/year member IDs, forbidden target roles, self review, inactive grants, invalid payloads and list/count/search scope.
- [x] Implement validated request types; profile updates whitelist fields before calling the service:

```java
if (!java.util.Set.of("className", "studentNo").containsAll(input.keySet())) {
    throw ApiException.validation("只能修改班级和学号");
}
```

- [x] Restrict cohort updates to the existing member's school/year and protected-user policy; preserve existing profile fields.
- [x] Extend school-deletion protection to the new grant table and verify permission errors retain 401/403/404 behavior.
- [x] Run targeted cohort and existing school-admin boundary tests.

## Task 3: Scoped profile reading and identity response

**Files:** Modify `auth/AuthDtos.java`, `auth/AuthService.java`, `auth/AppUserMapper.java`; extend cohort boundary tests and existing private-profile boundary tests.

**Interfaces:** Add `MeView.managedCohorts`; map each scope to `schoolId`, `schoolName`, `enrollmentYear`; add a fresh scoped profile lookup returning its matching school/year.

- [x] Add tests for all existing profile sections using private synthetic profiles in the same year, another year and another school.
- [x] Include scopes in `me()` without changing `managedSchools` or `role`.
- [x] Extend `requireReadableProfile` only, retaining ordinary visibility checks for follow/message operations; log `COHORT_PRIVATE_PROFILE_VIEW` with identifiers.
- [x] Assert hidden email/accounts remain hidden, public cross-year profiles remain readable and old tokens lose management access after revocation.
- [x] Run cohort, school-admin, and private-read permission suites.

## Task 4: School-manager appointment UI

**Files:** Create `frontend/src/api/cohortLeaders.ts`, `frontend/src/components/admin/CohortLeaderGrants.vue`; modify `frontend/src/views/SchoolAdminView.vue`; add frontend component regression script.

**Interfaces:** Typed grant/candidate/scope requests; `<CohortLeaderGrants :school-id="schoolId" />` within school management when member management is permitted.

- [x] Write a component-script regression for delayed old-school results and duplicate saves.
- [x] Add candidate search, editable management year defaulted from the selected candidate, grant list and revoke action; suppress repeated mutations and clear data on school changes.
- [x] Verify runtime errors preserve retry options and expired authorization clears old data.
- [x] Run the new regression and `npm run type-check`.

## Task 5: Leader workspace and navigation

**Files:** Create `frontend/src/views/CohortAdminView.vue`; modify `api/types.ts`, `stores/auth.ts`, `router/index.ts`, `components/layout/nav.ts`, `AppSidebar.vue`, `AppRail.vue`, `UserMenu.vue`, and `views/MeView.vue`; extend the frontend regression.

**Interfaces:** `auth.isCohortLeader`; `navGroupsForRole(isAdmin,isSchoolAdmin,isCohortLeader)`; route `/cohort-admin` with current auth scopes; API profile body exactly `{className,studentNo}`.

- [x] Test ordinary-user navigation, cohort-leader navigation and school-admin precedence.
- [x] Implement scope switching, member search/status/pagination, review, class/student-number editing and eligible profile links using existing UI components.
- [x] Isolate delayed scope responses, clear stale editing/review state, and refresh identity on 403 without leaving an old member list visible.
- [x] Add the page regression to package scripts and CI; run it with all existing frontend regression scripts.

## Task 6: Complete verification and review

**Files:** Update the design status, add user-facing cohort administration documentation, and include validation evidence in the implementation commit description.

- [x] Run backend `mvn -B verify` against dedicated PostgreSQL/Redis services and investigate failures by root cause without relaxing assertions.
- [x] Run Node 22.19.0 type check, build, browser-sync, announcement-editor, friend-actions, list-recovery, sync-view, recent-accepted and cohort-management scripts.
- [x] Inspect the working diff, run Gitleaks and request the separate reviewer required by the code-review skill. Resolve material findings, then rerun affected checks.
- [x] Record actual results and limitations; commit the implementation on this feature branch. Do not push, publish an Issue/PR or deploy without explicit user instruction.

## Completed verification

- Backend verify: 719 tests, 0 failures, 0 errors, 2 live-source tests skipped.
- New coverage: 10 cohort boundary tests and 3 real PostgreSQL concurrency/lifecycle tests.
- Node 22.19.0 type check, production build and all existing frontend scripts passed; new cohort-management script is included in CI.
- Chrome synthetic-user workflow: appointment, profile edit, membership review, year isolation and revocation cleanup passed; 390px mobile layout had no horizontal overflow or page errors.
- Independent review identified account-reactivation and lock-order issues; both were fixed and reviewed again with no remaining material findings.
- School-administrator promotion is handled by a database trigger rather than a controller-only hook, covering SQL writes as well as the existing API.
