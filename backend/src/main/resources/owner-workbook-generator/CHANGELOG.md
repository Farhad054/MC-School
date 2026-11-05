# Mindcrafti Workbook Generator - change history

## 2026-11-05-production

- Added MathJax 4 SVG and rich text across task descriptions, examples, facts, labels and teacher answers. Kept token arrays and added typed `{latex}`/`{tokens}` input.
- Added split/grid/row/column/stack, task cards, matching, headers, conclusion and answer components with bounded dimensions.
- Added empty coordinate grids, eight graph families, coefficient-derived legends, domain/pole segmentation and reusable intersections. Nonpolynomial intersections are numerical estimates.
- Added bounded SymPy assertions with domain exclusions and explicit coverage reports. Unchecked free-text mathematics still requires review.
- Pinned WeasyPrint 70.0 and added box-tree geometry inspection, raw-TeX/MathJax checks, blank/overflow/answer-area checks and PDF hash-bound QA reports.
- Added teacher-scoped generation from current school/context evidence, unpublished drafts, confirmed publication with idempotency/readback, and preserved owner/admin source access.
- Enabled ordinary teachers' scoped personal workbook/answer workflow and retained direct ChatGPT files, legacy base64 and existing Drive inputs. No mandatory Drive staging.
- Added production CI for legacy/current fixtures and QA rejection tests. Deployment and human draft review remain separate release checks.

## v3 — 2026-10-19 — Clean geometry tasks

- Replaced freehand-looking math labels with an SVG coordinate renderer that uses deterministic positions and legible dark text.
- Geometry defaults: triangle A/B/C vertices, opposite sides a/b/c, angle arcs with Greek symbols alpha/beta/gamma. Numeric measurements are not shown inside a figure unless explicitly requested.
- Cube and cuboid no longer print edge dimensions by default; given dimensions belong in the structured `facts` block below the graphic.
- Introduced the `facts` block with side-by-side GEGEBEN / GESUCHT cards in official Mindcrafti A4 styling.
- Added `examples/clean-tasks-demo.json` covering triangle, parabola, cube and cuboid as a canonical reference, and regression tests asserting the labeling defaults.
- Continued to generate separate student workbook and teacher solutions PDF. No school UI changes, no teacher rollout, no auto-publishing.
- Notes: Student and teacher questions/answers in free text are still manually reviewed. PDF overflow checks and numeric-chain checking do not prove full mathematical correctness.

## v2 — 2026-10-19 — Function graphs and geometry

- Added SVG plots and basic 2D/3D figure rendering, with original official branding.
- Corrected mathematical symbol spacing and mandatory PDF page-layout checks.

## v1 — 2026-10-19 — Branded workbook template

- First reusable template from the reference workbook: `generate.mjs`, `styles.css`, school colors, vector fractions, and teacher answer key.

## Release procedure

- All source changes go to `Nicklit200/MC-School-fork` / `integration/railway-and-ux` / `backend/src/main/resources/owner-workbook-generator/`.
- Each change must be committed with a version bump, a regression test, and a PDF visual review.
- Server deployment must be checked separately; a pushed GitHub commit does not mean the connector has been updated.
- The owner/admin source retrieval tool remains read-only. Teachers use authenticated server generation and explicit draft publication.
