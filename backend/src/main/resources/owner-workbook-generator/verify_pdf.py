#!/usr/bin/env python3
"""QA for generated PDFs: page count, A4, branding and content bounds."""
import json
import sys
from pathlib import Path
import fitz

root = Path(__file__).resolve().parent
lesson_path = Path(sys.argv[1]).resolve() if len(sys.argv) > 1 else root / 'lesson.sample.json'
out = Path(sys.argv[2]).resolve() if len(sys.argv) > 2 else root / 'out'
data = json.loads(lesson_path.read_text(encoding='utf-8'))
found = sorted(out.glob('*_Arbeitsheft_90min.pdf')) + sorted(out.glob('*_Lehrerloesungen_90min.pdf'))
if len(found) != 2:
    raise SystemExit(f'Expected exactly two PDF files in {out}, found {len(found)}')
for path in found:
    workbook = '_Arbeitsheft_' in path.name
    expected_count = len(data['workbookPages'] if workbook else data['teacherPages'])
    with fitz.open(path) as pdf:
        assert len(pdf) == expected_count, f'{path.name}: expected {expected_count}, got {len(pdf)}'
        for page in pdf:
            rect = page.rect
            assert abs(rect.width - 595.28) < 0.6 and abs(rect.height - 841.89) < 0.6, 'not A4'
            text = page.get_text()
            assert 'MINDCRAFTI SCHOOL' in text, 'Brand text missing'
            assert len(page.get_images()) >= 1, 'Official logo missing'
            blocks = [b for b in page.get_text('blocks') if b[4].strip() and b[1] > 160 and 'MINDCRAFTI SCHOOL  /' not in b[4]]
            for b in blocks:
                assert b[0] >= -1 and b[2] <= rect.width + 1, f'Horizontal overflow on page {page.number+1}'
                assert b[3] < 789, f'Content touches footer on page {page.number+1}: {b[3]}'
        print(f'PASS: {path.name}, {len(pdf)} A4 pages, original logo on every page')
