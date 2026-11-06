# Independent deploy: Render or any Docker Compose VPS

## What is ready

- Code source: `Nicklit200/MC-School-fork`, branch `integration/railway-and-ux`.
- `render.yaml` provisions an independent frontend, private Spring Boot API,
  and paid PostgreSQL on Render, in Frankfurt.
- The frontend uses `API_PROXY_SCHEME`, `API_PROXY_HOST` and
  `API_PROXY_PORT` to route requests to its *own* API, never Railway.
- `docker-compose.prod.yml` now points its frontend to its own `backend:8080`,
  enabling an independently hosted VPS with Docker Compose.
- Render auto-deploys GitHub pushes on the configured branch.

## Activate the backup

1. Connect the Render plugin/account and GitHub repo in Render.
2. Create a Blueprint from `render.yaml` on `integration/railway-and-ux`.
   **Review prices**: private API, web server, and database are paid separately.
3. Check healthy deployment; open the provided onrender.com URL, confirm
   `/actuator/health`, and test the login screen.
4. Configure needed integration credentials as **secrets in Render**, not Git.
5. Do not send students to the backup until their data have been restored and
   validated. A fresh Render database is EMPTY, despite the app being deployed.

## Daily snapshots / failover - not yet implemented

1. Identify and access the real production PostgreSQL and any S3/R2/Drive
   storage of PDFs (database backup alone may omit external files).
2. Configure encrypted off-Railway backups, suggested 22:00 Europe/Berlin,
   retaining 30 restore points and alerting on failed backups.
3. Test restoring into an isolated database, then the Render standby.
4. Confirm all users, homework, cards, uploaded PDF and related data.
5. Before switching users, prevent further writes to the original primary;
   disable duplicate scheduled notifications or payment jobs; restore the
   latest consistent snapshot; test the alternate service; change DNS.
6. Plan reverse migration before switching back to Railway.

A nightly backup gives a *target* of at most 24 hours of data loss only if each
backup succeeds. Actual recovery time is not guaranteed. Never allow writes
to two unsynchronized production databases at once.

## Alternative VPS

The existing `docker-compose.prod.yml` supports a separate Hetzner or other
Docker-capable host. Follow `DEPLOY.md`, secure HTTPS with a reverse proxy,
keep PostgreSQL internal, and store backups outside that VPS. For updates,
`git pull` and `docker compose -f docker-compose.prod.yml up -d --build`.
