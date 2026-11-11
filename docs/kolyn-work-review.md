# Kolyn: conversation and pupil work review

The teacher's private lesson chat supports ordinary explanations, confirmed task drafts, and on-demand reviews of saved pupil work. It reuses the existing OpenAI key, Responses transport, worksheet generator and annotation replay renderer.

## Teacher workflow

- `Как лучше объяснить дробь?` returns an explanation. A question about an existing worksheet's difficulty does not request another worksheet.
- `Проверь задание у Мелиссы` captures her currently open personal page, including the workbook PDF and saved annotations. The teacher can keep a different board open.
- `Что неправильно решила Мелисса?` or `Проверь всю тетрадь у Мелиссы` reads the complete saved personal workbook, including appended pages. This also works when the pupil is offline.
- `Дай Мелиссе пять заданий по дробям` still uses the checked PDF generator. Requests for an interactive still use the existing interactive schema. Adding either material requires the existing confirmation button.

A review includes the pupil name, page numbers, capture time and a clickable thumbnail. Full-workbook previews have a page selector. The answer identifies readable errors, corrections and teaching suggestions. It does not change annotations, grades, worksheet assignments or audio focus.

Only saved marks are included. A stroke still being drawn or waiting to save may be absent. Handwriting can be ambiguous; the assistant must identify uncertainty and unreviewed work rather than infer missing symbols. Annotations can include the teacher's writing, so handwriting alone is not proof of authorship.

## Context and access

- The server verifies the lesson host and pupil membership. An explicitly named pupil takes precedence over the teacher's selected board. Unknown or ambiguous names require clarification.
- Current-page selection uses the existing private LiveKit `view` channel. The authenticated sender must own the personal board. Reports expire after 15 seconds. Closing or hiding the personal board invalidates its report immediately. No guessed teacher page is substituted.
- Workbook review does not require a live page report. The renderer reads every original PDF page and every saved appended annotation page. A changed PDF version aborts the capture. Failure to read a personal PDF never falls back silently to the common PDF.
- Whole-workbook requests allow up to 40 pages and 16,000,000 base64 image characters total (roughly 12 MB of image bytes). A larger document requires a current-page request; it is never silently truncated. Each JPEG is capped at 1.5 MB, 3072 pixels per axis and 6 million pixels.
- Saved transcript context is fetched at question time, with up to 1000 final segments and 120,000 serialized characters. The response shows availability, coverage, truncation and latest saved time. This does not imply that an inactive microphone was recorded or that a speaker label proves identity.
- Images exist only in the request and private browser memory. They are not added to chat history, the database, public file storage or logs. Older in-page preview images are released. OpenAI requests use `store: false`.
- Retry after a network failure reuses the same snapshot and request key. A changed or unavailable source requires a fresh capture and key. Successful replies retain their original capture metadata.

## Model and diagnostics

`KOLIN_MODEL` now defaults to `gpt-6-astra`; an explicit environment value still overrides it. Ordinary conversation uses a small answer/generate contract without the worksheet schema. Only an explicit generation decision invokes the existing material generator. Reviews send labelled page images through a separate vision request with the same key and HTTP transport.

Server context logs contain class ID, transcript state/count/truncation, transcription state and supplied page count. They exclude text, images, pupil names and credentials. The Kolyn message proxy timeout is scoped separately from other API routes.

## Verification

- Unit tests cover authenticated pupil selection, case endings, unknown/ambiguous recipients, image bounds, stale views, changed PDFs, scoped replay, whole-workbook pages, retries and cross-lesson cancellation.
- Browser fixtures exercise the real PDF/Konva composition and preview, including a teacher on the common board while a pupil works on page 3. Provider responses and lesson APIs are mocked in these browser checks.
- `KolinLiveReviewTest` is opt-in using `KOLYN_LIVE_CHECK=true` and `OPENAI_API_KEY`. It calls the real model only with synthetic work and transcripts. Reports under `backend/target/kolyn-live-review` exclude credentials. Normal CI never spends API credits.
- Production verification must use a separate test lesson after release approval. Confirm a genuine pupil page report, saved handwriting, transcript receipt and an unchanged board after review.

No schema migration or production data modification is required for this feature.
