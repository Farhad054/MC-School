# Illustrated classroom controls

These are documentation cards built from authentic teacher UI screenshots, with a crop, a highlighted control and an explanation. They do not recreate or change the production UI.

- `source/` contains the original JPEG captures from an isolated lesson and a synthetic pupil. Two images are captured fragments: device toggles/join and return-to-pupil audio. No real pupils, camera preview, account contacts, passwords or device names appear.
- `cards.json` defines the source crop, focus rectangle, title, explanation, UI location and associated control labels for each card. Coordinates refer to the unscaled source image.
- `index.html?id=answers-panel` renders one 960 by 540 card. Serve this folder on a loopback-only static server, open the page in a browser with at least that viewport size, and capture the top-left 960 by 540 rectangle after the image loads. The page exposes `data-ready=true` on its body for read-only readiness checks.
- Save each reviewed screenshot as `backend/src/main/resources/mcp/teacher-guide-images/<id>.jpg`. The browser captures JPEG, so the extension, MCP `image/jpeg` type and actual byte signature must agree.

The handbook's image metadata maps these IDs to control labels. Match the relevant control to the step being explained and show its card there. Avoid using one full-room screenshot as the only illustration for an entire lesson walkthrough. Image reads remain opt-in and individually authorized.

Review every card for correct highlight placement, legible text and no clipping. Check that the footer does not overlap the location caption. Keep source captures so the cards can be updated without photographing a live pupil lesson. Changes to this folder and to the shipped images trigger handbook CI.

The screenshots were reviewed against production through PR 164 on 2026-11-09. In particular the current preparation screen uses `Общая рабочая тетрадь`, `Открыть тетрадь`, and `Документ пройденного урока` / `Все документы`. Common solutions are available from the classroom's `Общая группа` / `Ответы` panel. Do not infer visible labels solely from the legacy LessonDetailPage component.
