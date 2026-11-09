# Teacher handbook in MCH3

`get_teacher_guide` supplies versioned, read-only product documentation to authenticated active teachers and administrators. It does not retrieve student records, generate an AI answer, or mutate any school state. Anonymous callers, students, parents and legacy import API keys cannot discover or invoke it. Current account access is rechecked on every call.

Usage:

- `{}` returns the complete topic index.
- `{"query":"Как дать каждой ученице отдельное задание?"}` returns up to three relevant full articles and additional topic references.
- `{"topicId":"personal-materials"}` reads one full article.
- `{"imageId":"answers-panel"}` returns one authentic demo screenshot as native MCP `image` content, with a text caption and provenance. Use an image ID returned by an article, not a path or URL.

Queries are limited to 300 characters. Search uses bounded lexical matching with Russian, German and English keywords; it is not semantic AI search and does not guarantee a match for every paraphrase. No match returns the index and `found=false`. The assistant should clarify or read an appropriate topic, not invent an answer. The initialization instructions tell the assistant to fetch help for platform questions and distinguish instructions from authorization to perform an action.

The canonical handbook is `backend/src/main/resources/mcp/teacher-guide.json`. Its 26 topics include a complete first-lesson route, a preparation checklist, a map of classroom controls, personal PDFs, solutions, audio routing, cameras, saving and troubleshooting. Each article has a stable ID, title, summary, keywords, interface route, instructions, related topics, repository evidence paths and optional image IDs. It ships atomically with the backend. The response identifies the version, review date and release checked. These are documentation provenance, not proof that a specific room is healthy or has a particular file.

The assistant should start with concrete teacher actions and visible button labels, not internal API instructions. A new teacher can ask "Как провести первый урок?", "Что включить?", "Где вкладка решений? Покажи", or "Почему меня слышит только один ученик?". The connector reads documentation on demand; no new AI calls, continuous lesson processing or image generation are introduced by the handbook.

Screenshots in `backend/src/main/resources/mcp/teacher-guide-images/` were captured from the teacher UI on 2026-11-09, in the isolated synthetic lesson "Kolin: проверка интерактива (без учеников)" with the fake pupil "Kolin PDF QA 2026-11-07". The old Kolin spelling belongs to fixture names, not the assistant brand. No real pupil data, devices, passwords or browser chrome are captured. The microphone and camera were off. Screenshots show common-board controls, personal audio focus and the solutions sidebar. Their captions explain which control to find and distinguish the sample from the user's live state.

Text queries and article reads return image metadata only. A separate `imageId` call returns exactly one PNG, never base64 inside the text or structured result. This follows the native MCP image-content format already used for homework pages. There is no new public asset endpoint, external fetch or writable image source. IDs are selected from a bundled allowlist; assets must exist and remain below 500 KB. The same active-teacher/admin OAuth boundary applies to image reads. Unknown IDs return `found=false`; mixed selectors and oversized inputs are rejected. A client without image presentation still receives the caption and full textual steps.

Maintenance: when changing teacher-facing behavior, review the affected articles, verify button labels and access boundaries in code, update the review metadata and add query regressions. Do not document an unmerged feature as released. This edition describes production through PR 164. Capture updated screenshots in a synthetic lesson only, inspect each PNG for readability and personal data, and update its caption/date. Never invent controls or use a live student lesson for public examples. School policies on duration, pricing, consent and assessment must come from the owner, not be inferred from product functionality.

Verification includes topic coverage, real source paths, search examples in three languages, no-match behavior, bounded inputs and current OAuth access. The MCP website-functions CI runs the tests for both code and handbook-only changes. After an approved deployment, reconnect/refresh MCH3 if the client caches its tool catalog, then test index, topic lookup and realistic teacher questions. No production release is authorized merely by editing this handbook.
