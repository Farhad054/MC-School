# Mindcrafti Workbook v3 — owner-only MCP pilot

1. `prepare_workbook_context(teacherId, eventId)` verifies the lesson against the signed-in teacher's calendar. It returns the active month plan and **candidate** lesson-by-calendar-order, prior-lesson metadata, recent homework delivery/submission statuses, verified first-try card errors, homework IDs to inspect, and current school prompts. A missing submission is not an error. The model must inspect handwriting via the existing `get_homework_submission_pages` before diagnosing mistakes in handwritten answers.
2. ChatGPT constructs checked typed `lessonJson` with `meta`, `workbookPages` and `teacherPages` using the relevant school prompts; no 7-day homework, flashcards or reports are generated.
3. `generate_workbook(teacherId,eventId,contextToken,lessonJson)` runs the **existing canonical** v3 SVG/CSS/WeasyPrint generator using the current official Brand Guide logo. The calendar duration is binding unless `confirmDurationOverride: true` is supplied based on an explicit teacher request.
4. Both PDFs pass built-in PDF QA and a second server-side PDF/logo validation. They are saved only as an unpublished teacher-owned `lesson_pdf_drafts` row. The user downloads them via `get_lesson_draft` / `download_lesson_draft_pdf`, checks in ChatGPT and explicitly calls `publish_lesson_draft(confirm:true)` if accepted.

Auth is restricted to signed-in administrator and the designated owner teacher. API keys, other teachers and students cannot use these two tools. The normal teacher prompts and `create_lesson_draft` remain unchanged. Nothing is assigned as homework.

## Runtime and rollout

The backend Dockerfile gains Node22, Python3, WeasyPrint and PyMuPDF to execute the exact same versioned renderer. The `generate.mjs`/`verify_pdf.py` scripts now honor any validated 30–240-minute duration, including 60 and 90. Visual review of each PDF remains mandatory: the machine checks for some known overlap classes, not every possible diagram-label collision.

Source: `Nicklit200/MC-School-fork` / `integration/railway-and-ux`. A Git push is **not proof of Railway deployment**. Before opening for staff, validate representative group/individual lessons, real homework handwriting evidence, timing overrides and math correctness, and separately authorize the wider teacher rollout.
