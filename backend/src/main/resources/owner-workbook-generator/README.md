# Mindcrafti School — Owner Workbook Pilot v1

**Status:** ChatGPT-only pilot; no site button, no background generation, no automatic publication. Other teachers' school prompts and UI are unchanged. This directory is packaged as a resource in the MC-School backend, and an owner-gated MCP tool can provide the current master source to the school's ChatGPT workflow.

## Roles and intended workflow

- Only signed-in **ADMIN** or the explicitly allowed Nick teacher account can retrieve the template via `get_owner_workbook_generator`. An API key alone cannot access it.
- All teachers continue using the normal `get_school_prompt` and `get_brand_guide` tools. The pilot **does not change** those prompts.
- The generator runs in the ChatGPT working environment **on explicit request**. It never modifies a live lesson; the teacher checks PDFs first and authorizes publication separately via `prepare_lesson` or the existing draft approval flow.
- Never include pupil names, emails, class transcripts, or private education histories in the public GitHub sample. `lesson.sample.json` is anonymized and illustrative.
- Every new real Mindcrafti document must first use the **current** school prompt, monthly plan and Brand Guide. When the logo is updated, extract the original M from the current Guide and supply it as `--logo=...` instead of relying on the bundled pilot asset.

## Files

- `generate.mjs`: HTML/SVG/print generator; rational math checks (no external npm modules).
- `styles.css`: fixed A4 design (official school colors; answer areas and vector graphics).
- `lesson.sample.json`: **anonymized** six-page math worksheet and three-page teacher answer plan, using a reusable, typed content structure.
- `render_pdf.py`: WeasyPrint HTML → A4 PDF rendering.
- `verify_pdf.py`: PDF structure, logo and overflow checks.
- `assets/logo-m.png`: source image from the approved school Brand Guide at pilot creation time.

## Local / ChatGPT execution

Requirements: Node 20+, Python 3.10+, `weasyprint`, `pymupdf`.

```sh
python3 -m pip install 'weasyprint>=60' pymupdf
node generate.mjs --lesson=lesson.sample.json --basename=Mindcrafti_Pilot_20261019
python3 verify_pdf.py lesson.sample.json out
```

For a new lesson, write a new JSON file in the same schema with original, checked schoolwork and teacher answers. Use a **new output folder** per lesson so filenames never accidentally mix. Use optional flags:

```sh
node generate.mjs --lesson=/tmp/new-lesson.json --out=/tmp/workbook-2026-10-26 --basename=Mindcrafti_Gruppe1_20261026 --logo=/tmp/current-official-logo.png
python3 verify_pdf.py /tmp/new-lesson.json /tmp/workbook-2026-10-26
```

Expected output: `*_Arbeitsheft_90min.pdf` and `*_Lehrerloesungen_90min.pdf` plus the corresponding HTML files. A non-90-minute lesson requires adapting its `time` labels and filename suffix in the data/template; don't simply relabel an incorrectly timed lesson.

Use `node generate.mjs --html-only` to inspect/edit source HTML without PDF conversion. `node generate.mjs --engine=chromium` is an alternate renderer when Chromium is installed; the approved pilot default remains WeasyPrint to preserve visual parity with the reference.

## Design contract

- Page size A4; official palette: navy `#070A20`, orange `#FF6A00`.
- One original official M logo per page; no fabricated logo.
- Student worksheets are in German; teacher answer key is a **separate PDF**.
- Math expressions are vector SVG; fractional rectangles are vector SVG.
- A4 layout must not overlap footer or crop mathematical notation, even on iPad zoom.
- Never auto-publish student material; never expose teacher answers to students.

## Scope / limitations

Pilot v1 fully supports fractions and fraction bars. Coordinate diagrams, geometric figures, and plotting arbitrary functions are **future components**; do not claim those are already implemented. The generator checks answer chains but does not prove that every verbal problem is mathematically correct, so a human must still approve each lesson.

## Backend pilot endpoint

Call the owner-only MCP tool `get_owner_workbook_generator` without parameters for version/file metadata. Then request `file="generate.mjs"`, `file="styles.css"`, and `file="lesson.sample.json"` as needed. The tool does **not** execute code or publish; code runs in ChatGPT's working environment. Fetch current Brand Guide fresh before making new school documents.
