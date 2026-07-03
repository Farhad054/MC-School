# MC-School — Flashcard App Backend

Spring Boot backend for the Mindcraft School flashcard MVP: a spaced-repetition
web app where **teachers create individual flashcards for each student**, students
answer them as multiple-choice questions (1 correct answer + 3 distractors taken
from their other cards), and the system schedules mandatory reviews after
3 / 7 / 21 days (simplified SM-2). See the project-description PDF (PRD) for the
full product requirements.

The backend is a versioned REST API (`/api/v1`) designed to serve the web app now
and a mobile app later.

## Current implementation status

**Implemented:**

- Environment-based configuration (no secrets in git), Flyway migrations,
  Hibernate schema validation, global JSON error handling, actuator health check
- User model with roles `ADMIN` / `TEACHER` / `STUDENT` and the PRD account
  lifecycle: **there is no self-registration** — an admin creates teacher
  accounts, a teacher creates student accounts, and the invited person activates
  the account via an invitation token by setting a password
- Stateless JWT authentication (login, activation, `/auth/me`)
- Teacher management (admin only) and student management (owning teacher only)
- Unit tests + Testcontainers integration tests against real PostgreSQL

**Not yet implemented:** flashcards, decks/import, study sessions, SM-2
scheduling, email notifications (invitation emails included), i18n. See
"Recommended next steps" below.

## Architecture

Modular monolith, packaged by feature:

```
com.mcschool.flashcard
├── auth/        login, invitation activation, JWT issuing/verification, request filter
├── users/       User entity, roles, repository, invitation helpers, admin bootstrap
├── teachers/    teacher account management (admin only)
├── students/    student account management (owning teacher only)
├── common/      shared error model + global exception handler
└── config/      Spring Security + CORS + JWT configuration properties
```

Conventions used throughout:

- Controllers are thin; business rules live in services; persistence in Spring Data repositories
- JPA entities are never returned from controllers — every endpoint uses DTO records
- Constructor injection only; `@Transactional` at the service layer
- Ownership checks in the service layer (e.g. teachers only ever query their own students)
- The DB schema is owned by Flyway (`src/main/resources/db/migration`);
  `spring.jpa.hibernate.ddl-auto=validate` keeps Hibernate honest

### Key decisions

| Decision | Reasoning |
|---|---|
| JWT (stateless) over sessions | PRD allows either; one API must serve web + future mobile, and token auth avoids cookie/CSRF handling for the mobile client. No refresh tokens in the MVP — access tokens live 24h (configurable). |
| Single `users` table | The MVP has no role-specific profile data; a `role` column + nullable `teacher_id` (owner of a student) is the simplest correct model. Separate profile tables can be added later without breaking the API. |
| Invitation tokens returned in the create-account response | The PRD requires invitation *emails*, but email sending (SendGrid/Mailgun) is not integrated yet. Until then the admin/teacher passes the token on manually. Remove this from the response once emails exist. |
| Initial admin via env vars | Someone must create the first account. On startup, if `ADMIN_EMAIL`/`ADMIN_PASSWORD` are set and no admin exists, one is created. No-op otherwise. |
| UUID primary keys | Safe to expose in URLs/API responses, no sequence coordination for future imports. |

## Requirements

- Java 17+ (project targets 17; Java 21 works)
- Docker (for PostgreSQL and for the integration tests via Testcontainers)
- Maven is provided by the wrapper (`./mvnw`) — no local install needed

## Configuration

All settings have local-dev defaults matching `flashcard/compose.yaml`. Override
via environment variables (see `flashcard/.env.example` for the full list):

| Variable | Default | Purpose |
|---|---|---|
| `DB_HOST` / `DB_PORT` / `DB_NAME` | `localhost` / `5434` / `mydb` | PostgreSQL connection |
| `DB_USERNAME` / `DB_PASSWORD` | `myuser` / `mypassword` | DB credentials (defaults are local-dev only) |
| `JWT_SECRET` | dev-only default | HS256 signing key, min 32 chars — **must be set in production** |
| `JWT_EXPIRATION_MINUTES` | `1440` | Access-token lifetime |
| `CORS_ALLOWED_ORIGINS` | `http://localhost:3000,http://localhost:5173` | Browser origins allowed to call the API |
| `ADMIN_EMAIL` / `ADMIN_PASSWORD` / `ADMIN_NAME` | empty | Creates the first admin account at startup when set |

Never commit a `.env` file or real credentials.

## Running locally

```bash
cd flashcard

# 1. Start PostgreSQL (published on host port 5434)
docker compose up -d

# 2. Start the app (Flyway migrates the schema automatically on startup).
#    Set the admin variables once so the first admin account gets created.
ADMIN_EMAIL=admin@mcschool.local ADMIN_PASSWORD='ChangeMe123!' ./mvnw spring-boot:run
```

Success looks like `Tomcat started on port 8080` and
`Started FlashcardApplication`. Verify:

```bash
curl http://localhost:8080/actuator/health
# {"groups":["liveness","readiness"],"status":"UP"}
```

If host port 5434 is taken on your machine, publish another port and set
`DB_PORT` accordingly (e.g. `DB_PORT=5435`).
For verbose SQL logging use the dev profile: `SPRING_PROFILES_ACTIVE=dev`.

## Running tests

```bash
cd flashcard
./mvnw test                                              # everything (needs Docker)
./mvnw test -Dtest='*ServiceTest'                        # unit tests only (no Docker)
./mvnw test -Dtest=AccountAndAuthFlowIntegrationTest     # API integration tests
```

Integration tests start a disposable PostgreSQL 16 container via Testcontainers
and run the real Flyway migrations — they never touch your local database.

## API

All endpoints are under `/api/v1`. Errors always have this shape:

```json
{
  "timestamp": "2026-07-03T14:31:36Z",
  "status": 400,
  "errorCode": "VALIDATION_FAILED",
  "message": "Request validation failed",
  "path": "/api/v1/auth/login",
  "fieldErrors": [{ "field": "email", "message": "must be a well-formed email address" }]
}
```

### Public endpoints

| Method | Path | Purpose |
|---|---|---|
| `POST` | `/api/v1/auth/login` | Log in with email + password |
| `POST` | `/api/v1/auth/activate` | Accept an invitation: set password, get logged in |
| `GET` | `/actuator/health` | Health check |

Everything else requires `Authorization: Bearer <accessToken>`.

### Authenticated endpoints

| Method | Path | Who | Purpose |
|---|---|---|---|
| `GET` | `/api/v1/auth/me` | any logged-in user | Current account info |
| `POST` | `/api/v1/teachers` | admin | Create (invite) a teacher — `201` |
| `GET` | `/api/v1/teachers` | admin | List all teachers |
| `POST` | `/api/v1/students` | teacher | Create (invite) a student owned by the caller — `201` |
| `GET` | `/api/v1/students` | teacher | List the caller's own students |

### Example flow

```bash
# Admin logs in
curl -X POST http://localhost:8080/api/v1/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"email": "admin@mcschool.local", "password": "ChangeMe123!"}'
# 200 → {"accessToken":"...","tokenType":"Bearer","expiresAt":"...","user":{...}}

# Admin invites a teacher (use the admin accessToken)
curl -X POST http://localhost:8080/api/v1/teachers \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"fullName": "Maria Teacher", "email": "maria@example.com"}'
# 201 → {"teacher":{...,"status":"INVITED"},"invitationToken":"...","invitationExpiresAt":"..."}

# The teacher activates the invitation (public endpoint) and is logged in
curl -X POST http://localhost:8080/api/v1/auth/activate \
  -H 'Content-Type: application/json' \
  -d '{"invitationToken": "<token from previous step>", "password": "TeacherPass123!"}'
# 200 → {"accessToken":"...", "user":{...,"status":"ACTIVE"}}

# The teacher invites a student, then lists their students
curl -X POST http://localhost:8080/api/v1/students \
  -H "Authorization: Bearer $TEACHER_TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"fullName": "Sam Student", "email": "sam@example.com"}'
curl http://localhost:8080/api/v1/students -H "Authorization: Bearer $TEACHER_TOKEN"
```

Failure cases: wrong password → `401 INVALID_CREDENTIALS`; missing/invalid token →
`401 UNAUTHORIZED`; wrong role → `403 ACCESS_DENIED`; duplicate email →
`409 CONFLICT`; used/expired invitation → `400 INVALID_INVITATION`;
invalid body → `400 VALIDATION_FAILED` with `fieldErrors`.

Invitation tokens expire after 7 days.

## Known limitations

- Invitation emails are not sent yet — the token is returned in the API response instead
- No password reset, no token refresh/revocation (out of MVP scope per PRD)
- List endpoints are not paginated (PRD scale: 2 teachers, ~25 students)
- No OpenAPI/Swagger UI yet

## Recommended next steps

1. **Flashcards**: `cards` migration + entity (question, correct answer, owning
   student), teacher CRUD with ownership checks (`4.1`/`4.2` in the PRD)
2. **Text import** with separator choice and preview (PRD `4.1`, way 2)
3. **Study sessions**: session + answer-attempt persistence, distractor selection
   (3 random correct answers from the student's other cards, min-4-cards rule),
   wrong cards re-queued within the session (PRD `4.3`)
4. **SM-2 scheduling**: next-review dates 3/7/21 days, "learned" after 3
   successful reviews; voluntary practice that doesn't affect the schedule (PRD `4.4`/`4.5`)
5. **Email integration** (SendGrid/Mailgun) for invitations and review-day
   reminders; then stop returning invitation tokens in API responses
6. OpenAPI documentation (springdoc), pagination where lists can grow
