# Deployment

This guide makes the app **run on an always-on server** so students and teachers
can log in any time — independent of any developer's laptop. It runs the whole
stack (PostgreSQL + backend API + nginx-served frontend) with Docker Compose.

The frontend's nginx reverse-proxies `/api` to the backend, so the browser only
ever talks to one origin (no CORS to configure) and everything is reachable on a
single port.

## 1. Prerequisites on the server

- Docker + Docker Compose (Docker Engine 24+)
- A host that stays on: a small cloud VM (AWS Lightsail, Hetzner, DigitalOcean,
  etc.), or any always-on machine. A sleeping laptop will not keep the app online.
- (Recommended) a domain name and TLS certificate for HTTPS — see step 5.

## 2. Configure

```bash
git clone <repo-url> && cd MC-School
cp .env.prod.example .env
# edit .env — set strong DB_PASSWORD, a 32+ char JWT_SECRET, the admin
# credentials, PUBLIC_BASE_URL (your https domain in production), and,
# if you want real emails, MAIL_* with MAIL_ENABLED=true.
```

`.env` holds real secrets and is git-ignored — never commit it.

## 3. Build and start

```bash
docker compose -f docker-compose.prod.yml up -d --build
```

First build compiles the backend jar and the frontend bundle inside Docker, so no
local Java/Node toolchain is needed on the server. Postgres data persists in the
`pgdata` volume across restarts. The stack restarts automatically
(`restart: unless-stopped`), including after a server reboot.

## 4. Verify

```bash
curl http://localhost/actuator/health      # {"status":"UP",...}
docker compose -f docker-compose.prod.yml ps
```

Open `http://<server>/` and log in as the admin from `.env`.

Verified locally on 30 Jul 2026: the stack builds, all three containers report
healthy, `GET /` and SPA routes return 200, and `POST /api/v1/auth/login` returns
a token through the nginx proxy.

## 5. HTTPS (production)

Do **not** expose plain HTTP publicly. Terminate TLS one of these ways:

- Put a reverse proxy in front (Caddy or Traefik with automatic Let's Encrypt, or
  nginx + certbot) that forwards 443 → the frontend container's port 80, and set
  `HTTP_PORT` to an internal-only port.
- Or deploy behind a managed load balancer / platform that provides TLS.

Set `PUBLIC_BASE_URL=https://your-domain` so email links and CORS use the real URL.

## 6. Operations

- **Logs:** `docker compose -f docker-compose.prod.yml logs -f backend`
- **Update:** `git pull && docker compose -f docker-compose.prod.yml up -d --build`
- **Database backup:**
  `docker compose -f docker-compose.prod.yml exec postgres pg_dump -U $DB_USERNAME $DB_NAME > backup.sql`
- **Restore:** pipe a dump into `psql` in the postgres container.
- **Email reminders:** set `REVIEW_REMINDERS_ENABLED=true` (needs `MAIL_ENABLED=true`).

## Deploying to a PaaS instead

The same images work on Render, Railway, Fly.io, etc. Deploy three services
(PostgreSQL, the backend from `backend/Dockerfile`, the frontend from
`frontend/Dockerfile`) and set the backend environment variables from
`.env.prod.example`. If the platform serves the frontend on its own domain, either
keep the nginx `/api` proxy (point it at the backend's internal URL) or build the
frontend with `VITE_API_BASE_URL=https://<backend-domain>/api/v1` and add that
frontend origin to `CORS_ALLOWED_ORIGINS`.

## What still needs a real account/credential

- **SMTP provider** (SendGrid/Mailgun/etc.) for real invitation & reminder emails —
  the code is ready; you only supply `MAIL_*` in `.env`.
- **Domain + TLS certificate** for HTTPS.
- **Server host** to run it on.
