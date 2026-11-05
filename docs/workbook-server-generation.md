# Server workbook generation

Teachers should use the server-side workbook generation tool.
The source-code retrieval endpoint remains owner/admin only (OAuth ADMIN).
Students, parents and import API keys have no workbook generator or draft access.

1. Find the exact teacher/event using `find_lessons`.
2. Call `generate_lesson_workbook_draft` with `eventId`, optional `instruction`, and a stable `idempotencyKey` when server AI is configured.
   A teacher may omit `teacherId`; an administrator selects the accessible teacher explicitly.
   Without a server AI key, call `prepare_workbook_context` first. ChatGPT uses its current evidence and
   declarative `rendererContract` to prepare `lessonJson`, then calls the same high-level generation tool
   with `lessonJson` and `contextToken`. This route requires no separate AI API key and exposes no generator source.
   The server re-reads all relevant context and rejects the token if prompts, Brand Guide, plan or evidence changed.
3. Open both signed PDF download URLs in the returned `workbook` and `answers` metadata and review them.
   Links expire after five minutes. `get_lesson_draft` refreshes them without regenerating the files.
   `download_lesson_draft_pdf` retains the legacy base64 response for compatible clients.
4. Only after the user explicitly says to publish, call `publish_lesson_draft` with `draftId`,
   `confirm=true`, and a stable publication `idempotencyKey`.

Generation reads the current school prompts, Brand Guide, exact lesson duration and date,
available monthly plan, previous lesson notes/transcript, recent homework status, documented card errors,
saved student learning profile and available skills. Missing evidence stays missing.
Handwritten submissions are not automatically classified as wrong. Missing homework is not an error.

AI produces structured lesson JSON. The versioned code renders math with MathJax SVG,
graphs and geometry with deterministic SVG, then HTML/CSS and two A4 PDFs.
The PDF/math checks must pass before any draft is saved. Draft creation does not attach files,
change lesson notes, assign homework, change pupil state or schedule anything.

Publication updates the common workbook and staff answer slots atomically. The exact approved
PDF bytes are retained. The lesson is read back and the event ID, both filenames and sizes are verified.
Personal worksheets, annotations, homework, transcripts and schedule data are preserved.
The same idempotency key and arguments return the original result; a changed payload with the same key fails.

For PDFs generated or attached in ChatGPT, prefer direct file input.
Do not upload to Google Drive as an intermediate step.
Use Google Drive only when the source document already exists there.

`prepare_lesson` accepts `workbookFile` / `answersFile`. Site functions
`lesson_preparation.uploadWorkbook` and `lesson_preparation.uploadAnswers` accept top-level `file`.
Personal tools accept `pdfFile`. Each slot accepts exactly one supplied source: a ChatGPT file reference,
legacy base64, or an existing Drive file ID. An omitted common slot is preserved.
References use the supported ChatGPT `download_url` / `file_id` object, not arbitrary URLs or local paths.
Private worksheets take priority in the pupil's My board; Shared board continues to show the common workbook.
Private teacher answers remain staff-only. Active teachers can use personal tools for their own admitted pupils.

## Runtime

The backend image installs the pinned Node dependencies and Python renderer/QA dependencies.
`MINDCRAFTI_WORKBOOK_MODULES` points to the installed `node_modules` directory
(default `/opt/mindcrafti-workbook/node_modules`). Jobs use isolated temporary directories and bounded execution time.
For single-call server content generation configure `OPENAI_API_KEY` and `WORKBOOK_OPENAI_MODEL`
(or reuse an explicitly configured `OPENAI_MODEL`). No model name is guessed.
Missing configuration returns instructions for the ChatGPT-prepared JSON route; the service never substitutes a sample workbook.
Signed previews use `WORKBOOK_DOWNLOAD_SECRET` or the existing configured `JWT_SECRET` (at least 32 bytes).
There is no generated or development-secret fallback. Every download verifies the HMAC and five-minute expiry
before reading draft data, then checks the issuing account is still active staff and the selected teacher still
owns the exact lesson and draft. The token binds issuer, role, teacher, draft, document kind and content hash.
Responses are PDF attachments with caching disabled. Only the exact signed download route bypasses account login.

Refresh the MCH3 connection after deploying to discover the new tool catalog.
The legacy admin source endpoint, base64/Drive interfaces and v3 JSON remain supported.
