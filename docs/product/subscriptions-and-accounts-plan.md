# Subscriptions and accounts: plan for turning MC-School into a product

Status: proposal, nothing built. Written 2026-11-11 after a code review.

## Decision (2026-11-12): two phases
**Phase 1, now: finish the product and run it for our own school's teachers and students.**
Selling to other schools is a possible later step, so phase 2 below is deliberately not built yet.
What phase 1 needs:
- Test the Apple Pencil fix and the teacher connector with real users.
- Pupil data erase and export endpoints (`deleteStudent` only archives today), parental consent
  and a short privacy policy, because children's notebooks and lesson transcripts are sensitive
  even for a single school.
- Backups, uptime monitoring and error alerts so a lesson never fails silently.
- Finish the board polish.
- Rule for new code: do not spread the "there is only one school" assumption. The ADMIN role
  currently sees everything; new features should check ownership through the existing access
  services instead of relying on that, so phase 2 stays cheap.

**Phase 2, later, only if we sell it:** everything below (tenants, billing, limits, DPAs).

## Where the code is today
- **One school.** `Role` is ADMIN / TEACHER / STUDENT / PARENT. The ADMIN is the school
  owner and sees every teacher; there is no organization (tenant) concept, and no table
  that says which school a user, class, lesson or workbook belongs to.
- **No billing.** There is no payment code, plan, limit or entitlement anywhere.
- **Costs that need limits:** AI generation (OpenAI), live-class minutes (LiveKit),
  transcription (Soniox), recordings and PDFs (object storage).

## The one change everything else depends on: tenants
Without it a second school would see the first school's data, so it must come first.
1. New `organizations` table (id, name, plan, status, created_at, region).
2. `users.organization_id NOT NULL` (backfill: one row for today's school).
3. Add `organization_id` to every top-level teacher/student-owned table (classes,
   lessons, workbooks, homework, transcripts, cards, groups) and put it in every
   repository query. The existing "ownership" checks (`requireOwnedStudent`, the
   `LiveClassAuthorizationArchitectureTest` guard) already centralise most access,
   so the work is mechanical but wide.
4. Rename today's ADMIN to ORG_OWNER (full rights inside one organization). Add a
   separate PLATFORM_ADMIN for you, who is the only role that crosses organizations.
5. Add a cross-tenant test: for each controller, a user of school A gets 404 for
   school B's ids. Run it in CI so a future query cannot forget the filter.

## Billing
- Provider: **Stripe** (Checkout for sign-up, Customer Portal for changes and invoices,
  webhooks as the only source of truth for plan state). Do not store card data.
- Tables: `subscriptions` (organization_id, stripe ids, plan, status, current_period_end),
  `usage_counters` (organization_id, metric, period, amount).
- Plan state machine: TRIALING -> ACTIVE -> PAST_DUE (grace 7 days, read-only) -> CANCELED
  (data kept 30 days, then deleted; see retention below).
- Entitlements checked in one place (`EntitlementService`), not scattered `if`s.

Suggested limits to decide on (numbers are yours to set):
| Metric | Why it is limited |
|---|---|
| active teachers / students | pricing unit |
| AI workbook + homework generations per month | OpenAI cost |
| live-class hours per month | LiveKit cost |
| transcription hours per month | Soniox cost |
| storage GB (recordings, PDFs) | storage cost |

## Accounts and sign-in for a product
- Self-service sign-up creates an organization and its owner; teachers and students are
  invited. Invitation e-mails already exist.
- E-mail verification, password reset, optional 2FA for owners and teachers.
- Rate limiting and account lockout are already in place (see `SECURITY.md`).
- Move the access token from `localStorage` to an httpOnly, Secure, SameSite=Strict cookie
  with a short-lived access token + refresh token, once the product has users outside
  your school. It needs CSRF protection and CORS changes, and the MCP/OAuth flow stays on
  bearer tokens. Today's strict CSP (`script-src 'self'`) already makes token theft by XSS hard.

## Children's data (this is the legal part, not optional for a product)
You store names, transcripts of lessons with minors, handwritten notebooks and audio.
For Germany/EU that means GDPR (DSGVO) and, for under-16s, parental consent.
- Contract: a Data Processing Agreement (Auftragsverarbeitung) between each school and you.
- Sub-processors to list and sign DPAs with: hosting (Railway), LiveKit, Soniox, OpenAI,
  object storage, Stripe, e-mail provider. Prefer EU regions where they offer them.
- Rights: export a pupil's data, delete a pupil's data on request (today `deleteStudent`
  only archives; a real erase endpoint must remove notebooks, annotations, transcripts and
  recordings, not just hide the row).
- Retention (see below), a privacy policy, and parental consent captured at sign-up.

## Retention and deletion (what exists, what to add)
Exists: recordings 90 days, transcripts 365 days (`ClassRetentionService`, configurable);
trial-guest names are now anonymised after 30 days (`TrialGuestAnonymizer`).
To add: personal notebooks / annotations after a pupil leaves, org deletion 30 days after
cancellation, an erase-pupil endpoint, and a retention setting per organization.

## Suggested order
1. Tenants + cross-tenant tests (blocks everything).
2. Erase-pupil + export endpoints, DPAs, privacy policy (needed before the second school).
3. Stripe Checkout + webhooks + entitlements, with limits only on the costly metrics.
4. Self-service sign-up, e-mail verification, 2FA for owners.
5. Cookie-based sessions.

## Decisions needed from you
- Price per teacher, per student, or per school? Free trial length?
- Which countries first (decides GDPR scope, VAT, language)?
- Will you resell AI usage as a limit, or bundle it?
