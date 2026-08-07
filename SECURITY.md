# Security Audit & Hardening

Defensive security audit of MC-School (React/Vite frontend, Spring Boot API,
PostgreSQL/Flyway, Spring Security + JWT, Docker deployment). Scope was limited to
this repository and a locally-run instance; no external systems were tested and no
destructive testing was performed.

Tools used: **Gitleaks** (secret scan), **Trivy** (Docker image CVEs), **npm audit**
(frontend deps), and manual code review against the OWASP API Security Top 10 and
the access-control / sensitive-data / security-headers skills.

## Result summary

| Area | Verdict | Action |
|---|---|---|
| Role authorization (admin/teacher/student) | ✅ Sound | Class-level `@PreAuthorize` on every controller; self-scoped `/auth/me`, `/users/me/settings` |
| BOLA / IDOR | ✅ Sound | Service-layer ownership checks (`requireOwnedStudent/Card/Session`); cross-tenant access returns 404 |
| Mass assignment | ✅ Sound | Explicit DTO records; role/status/teacher set server-side; entities never bound from requests |
| JWT signing/verification | ✅ Sound + hardened | jjwt 0.12 blocks `alg:none`/confusion; **added guard against the committed default secret** |
| CORS | ✅ Sound | Config-driven allowlist (no wildcard), no credentials, explicit methods/headers |
| CSRF | ✅ Correct | Disabled — stateless bearer-token API uses no cookies |
| Sensitive-data exposure | ✅ Mostly | No password hash / stack traces / user-enumeration; invitation-token handling documented below |
| HTTP security headers | ⚠️ Fixed | **Added full header set** to nginx + hardened API response headers |
| Committed secrets | ⚠️ Fixed | Only a dummy test key flagged; **added Gitleaks config + CI + JWT default-secret guard** |
| Dependency vulns (frontend) | ⚠️ Triaged | Non-breaking fixes applied; remainder are dev/build-time only (see below) |
| Docker image vulns | ⚠️ Fixed | **Bumped base images** (frontend 35→11 HIGH, both CRITICALs gone; backend 6→4) + non-root user |

## Findings and fixes

### 1. Committed default JWT secret (High) — FIXED
`application.properties` ships a working default `JWT_SECRET`. Since it is public in
the repo, anyone could forge admin tokens if a deployment forgot to override it. The
length check (≥32) did not catch a *known* value.
**Fix:** `JwtService` now detects the built-in insecure value; it logs a loud
`SECURITY WARNING` in dev and **refuses to start** when
`app.security.jwt.fail-on-insecure-secret=true` (set in `docker-compose.prod.yml`).
Covered by unit tests.

### 2. Missing HTTP security headers (Medium) — FIXED
The nginx-served frontend returned no security headers.
**Fix (`frontend/nginx.conf`):** `Content-Security-Policy` (script-src `'self'`;
inline styles allowed only for React style attributes), `X-Frame-Options: DENY`,
`X-Content-Type-Options: nosniff`, `Referrer-Policy: no-referrer`,
`Permissions-Policy`, `Strict-Transport-Security`, `X-XSS-Protection: 0`, and
`server_tokens off`. The API adds a locked-down CSP (`default-src 'none'`) and
`Referrer-Policy` via Spring Security. Verified live — all headers present, SPA and
login still work.

### 3. Docker base-image vulnerabilities (Medium) — FIXED
Trivy (HIGH/CRITICAL, fixed-only) on the built images:
- Frontend `nginx:1.27-alpine` → **`nginx:1.29-alpine`**: 35 → **11** HIGH, 2 CRITICAL → **0**.
- Backend `eclipse-temurin:21-jre` → **`eclipse-temurin:21-jre-alpine`** + **non-root user**: 6 → **4** HIGH.
Residual HIGHs are upstream base-image packages; the CI Trivy job reports them so
they are picked up as base images are refreshed.

### 4. Frontend dependency advisories (Low) — TRIAGED
`npm audit` reported 5 (1 high, 4 moderate). `npm audit fix` (non-breaking) applied.
The remainder require major bumps and affect **dev/build-time only**, not the
deployed static bundle:
- **esbuild/vite** dev-server advisory → only affects `npm run dev`; production is
  static files served by nginx.
- **react-router** open-redirect needs attacker-controlled `<Link>` targets, which
  this app never renders; the SSR-hydration issue does not apply (client-only SPA).
Recommendation: bump vite and react-router at a planned major-version upgrade.

### 5. Secret scanning (Low) — FIXED / hygiene
Gitleaks flagged one item: a **dummy HS256 key in `JwtServiceTest`** (false positive).
**Fix:** `.gitleaks.toml` allowlists test fixtures and documented `*.example`
placeholders. A ready-to-use CI pipeline is provided at **`ci/security-scan.yml`**
(Gitleaks + npm audit + Trivy); to activate it, move it to
`.github/workflows/security-scan.yml` — that requires pushing with a token that has
the `workflow` scope, or adding the file via the GitHub web UI.

## Accepted residual risks (documented, not changed)

- **Invitation token in the create-account response / dev logs.** The token is
  returned only to the authenticated admin/teacher who created the account (over
  HTTPS) and is logged only by the dev-only `LoggingNotificationService` (when email
  is disabled). Recommendation: once SMTP is enabled in production, stop returning
  the token in the API/UI.
- **No login rate limiting.** Brute-force protection (e.g. per-IP throttling or
  lockout) is not implemented. Recommended before a large rollout; put the API
  behind a rate-limiting reverse proxy or add a filter.
- **Local-dev default DB credentials** in `application.properties` / `compose.yaml`
  are intentional and overridden by env vars in `docker-compose.prod.yml`.

## Running the scans yourself

```bash
# Secrets (Docker, no install needed)
docker run --rm -v "$PWD":/repo ghcr.io/gitleaks/gitleaks:latest \
  detect --source /repo --config /repo/.gitleaks.toml --redact -v

# Frontend dependencies
cd frontend && npm audit

# Docker images (build first, then scan)
docker build -t mcschool-backend ./backend && docker build -t mcschool-frontend ./frontend
docker run --rm -v /var/run/docker.sock:/var/run/docker.sock aquasec/trivy:latest \
  image --severity HIGH,CRITICAL --ignore-unfixed mcschool-frontend
```
