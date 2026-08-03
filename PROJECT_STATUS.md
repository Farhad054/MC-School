# MC-School Codebase and Requirements Status

Analysis date: 23 July 2026

## Scope reviewed

- Product requirements: `../PRD_Flashcard_App_MVP (2).pdf`, seven pages,
  MVP 1.0 dated May 2026
- Backend application, persistence migrations, security, notification seam, and tests
- Frontend routes, screens, API client, role guards, responsive styling, and translations
- Local configuration, Docker Compose, Maven/npm scripts, and repository documentation

Only one requirements PDF exists in the workspace.

## Executive summary

The repository is a coherent, working full-stack MVP foundation. The core account,
card, study-session, simplified SM-2, role-isolation, and bilingual UI flows are
implemented. The frontend production build succeeds, and all 57 backend tests pass,
including 26 Testcontainers integration tests against PostgreSQL 16.

The product is not yet complete against the PDF's MVP definition. Real transactional
email is the principal missing feature. There are also two functional edge cases that
should be fixed before release: a card may be undeletable after it is referenced by
session history, and questions may have fewer than four options when answers are
duplicated. The 21-day scheduling/learned-state wording in the PRD also needs a product
decision because the current implementation stores a 21-day date while immediately
removing the card from mandatory reviews.

## Update — 30 July 2026

The analysis below reflects the 23 July snapshot. Since then the following P0/P1
items have been resolved (product decisions were confirmed by the owner):

- **Card deletion is now safe (P0).** Cards are soft-deleted (`cards.archived`,
  migration V5); the row is kept so session history stays intact and there is no
  FK error. Covered by an integration test that deletes a card mid-session.
- **Real transactional email is implemented (P0), off by default.** When
  `MAIL_ENABLED=true` and SMTP is configured, `EmailNotificationService` sends
  DE/RU invitation emails on teacher/student creation (with a frontend activation
  link) and due-review reminders with a login link; otherwise emails are logged.
  Mail outages are logged, never fatal, and the mail health indicator is disabled
  so SMTP does not gate app health.
- **Teacher low-card warning added (P1).** The student detail page warns when a
  student has fewer than four cards.
- **SM-2 wording — decided to keep current behavior:** learned after 3 successful
  reviews; the 21-day date is informational and no 4th mandatory review fires.
- **Four-option guarantee — decided to allow fewer options** when a student's cards
  share duplicate answers (no session-blocking); the four-card minimum stays.
- **Deployment/operational readiness (P1) started:** backend + frontend Dockerfiles,
  a production `docker-compose.prod.yml` (PostgreSQL + backend + nginx-served
  frontend proxying `/api`), an env template, and `DEPLOY.md`. This is what lets the
  app run on an always-on server instead of a developer's laptop.
- **Tests:** now 61 backend tests, all green (added soft-delete + notification-content
  coverage).

Still open from the list below: real SMTP provider account/credentials + HTTPS +
a host to deploy on; frontend automated tests and device/accessibility QA;
notification scheduler timezone/retry hardening; and the P2 improvements.

---

## Architecture

### Backend

- Java 17 and Spring Boot 4.1
- REST API under `/api/v1`
- Stateless JWT authentication with BCrypt passwords
- Method-level role authorization for `ADMIN`, `TEACHER`, and `STUDENT`
- PostgreSQL persistence with four Flyway migrations and Hibernate validation
- Persisted, resumable study sessions
- Testcontainers integration testing

Main backend areas:

| Area | Responsibility |
|---|---|
| `auth` | Login, invitation activation, JWT issue/verification |
| `users` | Accounts, roles, language preference, admin bootstrap |
| `teachers` | Admin-created teacher accounts |
| `students` | Teacher-owned student accounts |
| `cards` | Card CRUD, summaries, and text-import preview/confirmation |
| `study` | Today view, sessions, distractors, results, scheduling |
| `notifications` | Daily reminder scheduling seam; logging transport only |

### Frontend

- React 18, TypeScript, React Router, and Vite
- One application with role-protected routes
- Wide staff layout and narrow mobile-first student layout
- Russian and German translation dictionaries
- JWT session restored from `localStorage`

The frontend uses no state-management or UI framework beyond React itself, which is
appropriate for the current project size.

## Requirement traceability

| PDF requirement | Status | Evidence / notes |
|---|---|---|
| Admin creates teacher accounts | Implemented | Admin-only teacher API and `TeachersPage` |
| Teacher creates student accounts | Implemented | Ownership-scoped student API and `StudentsPage` |
| Invitee follows a link and sets a password | Partial | Secure token activation exists; email/link delivery is absent and the UI exposes a code |
| Email/password login | Implemented | BCrypt and JWT; no self-registration |
| Cards are individual to one student | Implemented | `cards.student_id`; teacher ownership checks on every card operation |
| Teachers cannot see each other's students/cards | Implemented | Service-level ownership filters plus integration tests |
| Manual question/answer creation | Implemented | Backend validation and teacher form |
| Text import with selectable separators and preview | Implemented | Literal parser, warnings, preview, confirmation |
| Edit and delete cards | Partial | Edit works; delete can fail for cards referenced by session history |
| Teacher sees active/awaiting/learned totals | Implemented | Summary endpoint and teacher UI |
| Minimum four cards before a session | Implemented | Enforced by the backend and displayed to the student |
| Teacher is warned when fewer than four cards exist | Missing | Teacher detail page shows totals but no explicit warning |
| Exactly four answer options | Partial | Usually four; duplicate correct answers can produce fewer options |
| Immediate correct/incorrect feedback | Implemented | Student session screen |
| Wrong answer returns in the same session | Implemented | Persisted queue position and integration test |
| Session ends only after all cards are correct | Implemented | Pending items must reach zero |
| Progress bar | Implemented | Current correctly completed count over session total |
| First-try result | Implemented | Stored per session and shown on result screen |
| Reviews after 3 / 7 / 21 days | Needs clarification | 3 and 7 are active reviews; after the third success a 21-day date is stored but the card becomes `LEARNED` immediately |
| Mistakes do not reset the interval | Implemented | Scheduling advances only once the scheduled session completes |
| Learned after three successful repetitions | Implemented as written | Third scheduled success marks `LEARNED` |
| Voluntary “practice now” | Implemented | Uses all student cards and does not change the schedule |
| Daily review email with quick-login link | Missing | Scheduler exists, but transport only logs and no quick-login link is generated |
| German and Russian UI | Implemented | Complete parallel translation key sets; student can persist language |
| Student mobile-first / staff desktop layout | Implemented structurally | CSS uses role variants; real-device/browser QA is still needed |
| Persist users, cards, session history, schedule | Implemented | PostgreSQL schema and Flyway migrations |

## Work remaining for MVP

### P0 — release blockers

1. Implement transactional invitation emails.
   - Send teacher invitations when an admin creates a teacher.
   - Send student invitations when a teacher creates a student.
   - Include a frontend activation URL with the token.
   - Keep tokens out of normal production API/UI responses.
   - Add provider configuration, templates in DE/RU, delivery error handling, and tests.

2. Implement real due-review emails.
   - Replace `LoggingNotificationService` with a provider-backed implementation.
   - Include the due-card count and a safe login/deep link.
   - Send only to active students.
   - Define the scheduler timezone explicitly.
   - Add retry/observability behavior and scheduler tests.

3. Make card deletion safe.
   - `study_session_items.card_id` references `cards.id` without delete behavior.
   - A teacher deleting a card used by a current or historical session can receive
     an unexpected database error/HTTP 500.
   - Prefer soft deletion so session history stays intact, or define an explicit
     archive/conflict policy. Add integration tests for historical and in-progress sessions.

4. Guarantee four usable answer options.
   - Four cards are insufficient when several cards share the same correct answer.
   - Decide whether to block sessions until four distinct answers exist, prevent
     duplicate answers, or introduce a fallback distractor strategy.
   - Reflect the decision in teacher/student warnings and integration tests.

### P1 — product decision and release hardening

1. Resolve the SM-2 wording ambiguity.
   - Current behavior: success 1 schedules +3 days, success 2 schedules +7 days,
     and success 3 stores +21 days but marks the card learned, so that date is never due.
   - Decide whether the 21-day session must happen before a card becomes learned.
   - Update the scheduler, result copy, entity/migration comments, and tests together.

2. Add the explicit teacher warning required when a student has fewer than four
   cards, ideally also accounting for distinct answer count.

3. Add frontend automated tests.
   - There are currently no frontend test/spec files.
   - Cover role routing, activation, card import, wrong/correct feedback, result
     navigation, language persistence, and API error rendering.

4. Add end-to-end browser/device checks.
   - Student: Chrome and Safari on representative iOS/Android viewport sizes.
   - Staff: Chrome, Safari, and Firefox on desktop.
   - Include accessibility checks for keyboard navigation, focus, labels, contrast,
     and screen-reader announcements after answers.

5. Add backend coverage for notification scheduling, card deletion/history, duplicate
   distractors, the complete three-review lifecycle, concurrent answer submission, and
   scheduler date/timezone boundaries.

6. Improve operational readiness.
   - Production secrets/configuration and deployment manifests
   - HTTPS and secure hosting
   - Database backup/restore procedure
   - Structured logs, monitoring, and alerting
   - Email-provider webhook/bounce handling

### P2 — useful improvements, not explicit MVP blockers

- Batch card/student summary queries instead of one request per student
- Pagination for growing card, student, teacher, and session-history data
- OpenAPI/Swagger documentation
- Resend/expire/revoke invitation controls
- Admin/teacher account deactivation and clearer interpretation of “manage accounts”
- Display the student's name on the teacher's student-detail page
- Display teacher last-activity data if the page-6 design reference is adopted
- Roll back the optimistic language change if persistence fails
- Consider an HttpOnly-cookie or other hardened token strategy for production

## Explicitly outside MVP

Do not treat these PDF items as unfinished MVP work:

- Pre-test/post-test progress measurement: proposed for version 1.5
- Google login and password recovery: version 2.0
- Topic folders
- Ukrainian UI
- Shared cards between teachers
- Teacher alerts for unfinished homework
- Push notifications
- Native iOS/Android application
- Admin analytics
- Built-in AI card generation
- Manually entered distractors

## Quality verification

Verified on 23 July 2026:

| Check | Result |
|---|---|
| `frontend: npm run build` | Passed; TypeScript and Vite production build succeeded |
| `backend: ./mvnw test` | Passed; 57 tests, 0 failures, 0 errors |
| PostgreSQL integration suites | Passed; 26 integration tests |
| TODO/FIXME scan | No TODO/FIXME markers in application source |
| Frontend automated tests | None present |

The backend suite is strong for the implemented foundation, especially account
isolation and the basic card/study flow. Passing tests do not cover the P0 edge cases
listed above.

## Recommended delivery sequence

1. Product decision: distinct distractors and the 21-day learned-state interpretation.
2. Fix card deletion/history behavior and add integration coverage.
3. Implement invitation email delivery end to end.
4. Implement review reminder email delivery, timezone, retries, and monitoring.
5. Add the teacher minimum-card warning and frontend/e2e tests.
6. Complete device/accessibility QA and production operations work.

