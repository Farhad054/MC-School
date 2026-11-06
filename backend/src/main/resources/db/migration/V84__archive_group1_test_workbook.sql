-- Preserve the original workbook from the 19 Oct "Группа 1 тест" lesson
-- as a separate completed-lesson artifact. Do not alter the workbook itself.
INSERT INTO completed_lesson_documents (
    id, teacher_id, event_id, class_id, board_id, filename, pdf
)
SELECT
    '69cbd367-13aa-4d2a-ad84-2267a3813d1a'::uuid,
    lp.teacher_id,
    lp.event_id,
    '971a07aa-69f1-4430-ba71-386eec957436'::uuid,
    'imported-workbook',
    'Группа 1 тест - исходная рабочая тетрадь.pdf',
    lp.workbook_pdf
FROM lesson_preparations lp
WHERE lp.teacher_id = '14e3c7c1-1fc8-41bd-858a-6c20efdd957a'::uuid
  AND lp.event_id = '8r88difeql4iphq2n6aev43hhs'
  AND lp.workbook_pdf IS NOT NULL
  AND length(lp.workbook_pdf) > 0
ON CONFLICT (teacher_id, event_id, class_id, board_id) DO NOTHING;
