# Mindcrafti School — Owner Workbook Pilot v1

**Status:** ChatGPT-only pilot with SVG graphing + geometry (v2); no changes to the public site or teacher UI. ChatGPT-only pilot; no site button, no background generation, no automatic publication. Other teachers' school prompts and UI are unchanged. This directory is packaged as a resource in the MC-School backend, and an owner-gated MCP tool can provide the current master source to the school's ChatGPT workflow.

## Roles and intended workflow

- Only signed-in **ADMIN** or the explicitly allowed Nick teacher account can retrieve the template via `get_owner_workbook_generator`. An API key alone cannot access it.
- All teachers continue using the normal `get_school_prompt` and `get_brand_guide` tools. The pilot **does not change** those prompts.
- The generator runs in the ChatGPT working environment **on explicit request**. It never modifies a live lesson; the teacher checks PDFs first and authorizes publication separately via `prepare_lesson` or the existing draft approval flow.
- Never include pupil names, emails, class transcripts, or private education histories in the public GitHub sample. `lesson.sample.json` is anonymized and illustrative.
- Every new real Mindcrafti document must first use the **current** school prompt, monthly plan and Brand Guide. When the logo is updated, extract the original M from the current Guide and supply it as `--logo=...` instead of relying on the bundled pilot asset.

## Files

- `generate.mjs`: HTML/SVG/print generator; rational math checks (no external npm modules).
- `diagrams.mjs`: coordinate-grid/function plotting and geometrically consistent SVG for triangle, rectangle, quadrilateral and circle.
- `diagrams.test.mjs`: no-dependency tests for functions, dimensions, safety, and exact geometry.
- `examples/graphs-geometry-demo.json`: illustrated test workbook for graphics and geometry; anonymized, not a real lesson.
- `styles.css`: fixed A4 design (official school colors; answer areas and vector graphics).
- `lesson.sample.json`: **anonymized** six-page math worksheet and three-page teacher answer plan, using a reusable, typed content structure.
- `render_pdf.py`: WeasyPrint HTML → A4 PDF rendering.
- `verify_pdf.py`: PDF structure, logo and overflow checks.
- `assets/logo-m.png`: source image from the approved school Brand Guide at pilot creation time.

## Local / ChatGPT execution

Requirements: Node 20+, Python 3.10+, `weasyprint`, `pymupdf`.

```sh
python3 -m pip install 'weasyprint>=60' pymupdf
node diagrams.test.mjs
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

Pilot v2 supports fractions and fraction bars, **equal-scale coordinate grids**, function graphs of types `linear`, `quadratic`, `polynomial` (degree ≤ 4), `sine`, and `cosine`, plus coordinate-built `triangle`, `rectangle`, `quadrilateral`, and `circle` figures. The image is precise vector SVG, not a generated bitmap. Geometry side labels and angle arcs are computed from input coordinates, and rectangle constraints are checked. **Not yet supported:** arbitrary expression parsing, 3D graphics, constructions with compasses, advanced geometric theorem validation. The rational-answer checker verifies numeric chains, not free-text or algebraic answers; human review is still required.

## Backend pilot endpoint

Call the owner-only MCP tool `get_owner_workbook_generator` without parameters for version/file metadata. Then request `file="generate.mjs"`, `file="diagrams.mjs"`, `file="styles.css"`, `file="lesson.sample.json"`, and `file="examples/graphs-geometry-demo.json"` as needed. The tool does **not** execute code or publish; code runs in ChatGPT's working environment. Fetch current Brand Guide fresh before making new school documents.


## New SVG component format (v2)

Embed each diagram directly as one `blocks[]` entry (in student or teacher page):

```json
{
  "type":"graph", "label":"KOORDINATENSYSTEM", "xRange":[-5,5], "yRange":[-4,6], "gridStep":1,
  "series":[{"kind":"linear","m":1,"b":1,"label":"f(x) = x + 1"}]
}
```

```json
{
  "type":"geometry", "figure":"triangle", "unit":"cm", "showSides":true, "showAngles":true,
  "vertices":[{"label":"A","x":0,"y":0},{"label":"B","x":6,"y":0},{"label":"C","x":0,"y":8}]
}
```

See `examples/graphs-geometry-demo.json` for tested quadratic, circle, rectangle and point examples. To generate a preview:

```sh
node generate.mjs --lesson=examples/graphs-geometry-demo.json --out=/tmp/mindcrafti-diagrams --basename=Mindcrafti_Diagram_Pilot
```

The function coefficients and geometry vertices are **data**; every rendered path is computed, no unsafe `eval` and no user-supplied SVG markup. Every new PDF gets mandatory page-layout QA. Run diagram unit tests before changing plotting logic. Plot function legends are human-specified strings, so always cross-check them against coefficients before publication. If the user asks for a non-90-minute lesson, adapt pagination/timing conventions with deliberate QA.
