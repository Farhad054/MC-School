# Workbook generation and personal publication

`generate_lesson_workbook_draft` returns a durable job, not a completed PDF. The
teacher must own the selected lesson; an administrator selects its teacher.
Reuse the same idempotency key and identical arguments after a transport timeout.
Poll `get_lesson_workbook_job` no sooner than `retryAfterSeconds`.

- `QUEUED` or `RUNNING`: wait. Do not generate another copy.
- `SUCCEEDED`: the response includes a stored draft and fresh preview links.
- `FAILED`: explain `errorCode` and `errorMessage`. No material was published.
  Do not silently retry a possibly charged request. A requested retry uses a new
  key after correcting content or configuration.

When preparing multiple pupils, wait for all jobs then refresh each
`get_lesson_draft` immediately before the final answer. Signed preview URLs still
last five minutes; generation does not start that final preview window. Do not
send URLs with `...` placeholders or claim a PDF was opened merely from metadata.

After the teacher explicitly approves upload, use
`publish_personal_lesson_draft(teacherId,eventId,studentId,draftId,idempotencyKey,confirm=true)`
for a server-generated draft. It copies the two stored PDFs directly to that
pupil, atomically, using existing private workbook/answer services. It requires
no file objects, temporary URLs, base64, widget or Drive. The source stays reusable;
the durable publication receipt identifies its exact pupil and destination.
Shared materials and other pupils are not replaced. Use `publish_lesson_draft`
only when the teacher wants common lesson materials.

Background generation uses the existing renderer, mathematical/layout QA and
idempotency receipts. It commits the PDFs and successful job together. Queued jobs
survive restart. A running job has a renewable 90-second lease; expired jobs are
marked interrupted, not automatically replayed. One worker per replica and a
maximum of six pending jobs per actor bound load. Finished job inputs are cleared.
Status reads recheck current actor, role and exact lesson ownership; guessing a
job ID does not allow another teacher or another administrator to read it.

Logs contain job ID, state, duration and bounded error category/class. They never
contain tokens, prompts, source JSON, student names or signed URLs. MCP transport
errors now also log the method, tool name and role without the request or error
text. A successful `tools/list` does not prove ChatGPT exposed every tool to its
model or rendered the app; verify those separately in the real client.
