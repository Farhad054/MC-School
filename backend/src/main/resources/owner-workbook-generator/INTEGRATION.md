# MCH3 workbook integration

## Server-side drafts

Teachers should use the server-side workbook generation tool. The source-code retrieval endpoint remains owner/admin only.

`generate_lesson_workbook_draft({eventId,instruction?,teacherId?,idempotencyKey})` resolves the exact owned lesson and reads fresh school prompts, Brand Guide, available monthly plan and actual student evidence. The content provider creates structured JSON; the locked MathJax/SVG/WeasyPrint renderer and algebra/layout QA produce two PDFs. The response includes `draftId`, exact `eventId`/`teacherId`, duration, filenames/sizes, QA status and `published:false`.

No server AI key is required for the alternate content path: call `prepare_workbook_context`, follow its declarative `rendererContract` to prepare `lessonJson` in ChatGPT, then call the same generation tool with both `lessonJson` and `contextToken` in addition to the event and idempotency key. The server re-reads context and rejects a stale token. No generator source is returned, and the trusted renderer and QA remain server-side. This path also requires actual `qa.algebra` assertions.

Active OAuth teachers are limited to their own students and lessons. ADMIN must select an accessible teacher. Authorization runs before generation, file resolution and publication and is repeated when returning an idempotent receipt. Students, parents and API keys have no generation access. Low-level `generate_workbook` and `get_owner_workbook_generator` remain owner/admin workflows.

`prepare_workbook_context({eventId,teacherId?})` exposes available context. Homework status is evidence of submission, not proof of mistakes. An unreviewed handwritten PDF is explicitly ungraded. Curriculum catalog entries are not pupil mastery records.

## Review and publication

1. Call `download_lesson_draft_pdf` for `kind:"workbook"` and `kind:"answers"` and review both.
2. Wait for the user's concrete publication instruction, for example "Загружай".
3. Call `publish_lesson_draft({draftId,teacherId?,confirm:true,idempotencyKey})` with the exact approved draft.
4. Publication replaces workbook/answers slots, updates draft status and reads the lesson back. The response confirms `success`, exact `eventId`, `hasWorkbook`, `hasAnswers` and exact filenames.

Generation only creates an unpublished draft. Publication preserves schedule, transcript, homework, student status, personal worksheets and annotations. Personal workbooks take priority on My board; Shared board uses the common workbook. Personal answers remain staff-only. Identical retries with the same idempotency key do not create duplicate writes; reusing a key with different arguments is rejected.

## Direct files without Google Drive

For PDFs generated or attached in ChatGPT, prefer direct file input. Do not upload to Google Drive as an intermediate step. Use Google Drive only when the source document already exists there.

Declared ChatGPT file parameters (`_meta.openai/fileParams`) are supported by `prepare_lesson` (`workbookFile`, `answersFile`), the `lesson_preparation.uploadWorkbook`/`uploadAnswers` site functions and personal upload tools (`pdfFile`). The runtime supplies `download_url` and `file_id`, optionally `mime_type` and `file_name`. A bare sandbox path or unresolvable file ID is not a download transport.

Each supplied slot takes exactly one source: direct file, legacy base64, or existing teacher-scoped Drive file ID. Omitted slots remain unchanged. File resolution uses HTTPS on configured exact trusted hosts, public DNS addresses, no redirects or forwarded credentials, a bounded timeout and a 15 MB PDF limit. Expired references are rejected without changing stored files. Authorization precedes download. Idempotency fingerprints exclude short-lived signed URL query strings.

Server-generated drafts publish their stored exact bytes directly, without a ChatGPT upload round trip or Drive staging.

## Deployment and validation

The Dockerfile bundles Node 22, locked npm dependencies and pinned Python requirements. Optionally configure `OPENAI_API_KEY` and `WORKBOOK_OPENAI_MODEL` (or the existing `OPENAI_MODEL`) for server content generation; otherwise use the prepared JSON path above. `MINDCRAFTI_WORKBOOK_MODULES` points to MathJax node_modules. `MINDCRAFTI_CHATGPT_FILE_HOSTS` configures exact trusted file hosts, without wildcards or arbitrary URL inputs.

Production layout QA inspects the WeasyPrint 70.0 box tree before clipping, then verifies PDF bytes and a SHA-256-bound geometry report. Mathematical QA covers explicit algebra assertions and supported numeric chains, not all generated teaching content. Reports disclose assertion counts and manual graph-label warnings. Preview remains required before publication.

CI renders anonymized current/v3 fixtures and tests rejection of raw TeX, MathJax errors, overflowing SVG, footer overlap, tiny answer areas, blank pages and mismatched QA hashes. It also builds the production Dockerfile and runs `ci/workbook-container-smoke.py` inside that image with networking disabled, using its existing non-root app user, packaged JAR resources, installed node_modules and pinned Python runtime. This catches missing packaged resources and container font/native-library differences. Backend ownership/publication/direct-file tests run in a separate Java job. Verify deployed MCH3 tools separately; local checks or a Git push do not prove Railway deployed the change.
