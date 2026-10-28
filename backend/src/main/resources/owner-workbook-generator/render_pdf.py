#!/usr/bin/env python3
"""Render self-contained HTML to vector PDF. Requires: pip install weasyprint."""
import sys
from weasyprint import HTML
if __name__ == '__main__':
    if len(sys.argv) != 3:
        raise SystemExit('Usage: python render_pdf.py input.html output.pdf')
    HTML(filename=sys.argv[1]).write_pdf(sys.argv[2])
