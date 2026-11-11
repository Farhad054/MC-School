import { cleanup, render, screen } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { afterEach, expect, it, vi } from 'vitest';
import { api } from '../../api/client';
import type { StudentLessonHistoryDetail } from '../../api/types';
import { I18nProvider } from '../../i18n/I18nContext';
import { StudentLessonHistoryDetailPage } from './StudentLessonHistoryDetailPage';

vi.mock('../../api/client', () => ({ api: { students: {
  lessonHistoryDetail: vi.fn(), lessonHistoryCompletedDocuments: vi.fn(), lessonHistoryFile: vi.fn(),
} } }));

afterEach(() => { cleanup(); vi.unstubAllGlobals(); vi.clearAllMocks(); });

it('shows automatic speech alongside an existing PDF and teacher transcript in student history', async () => {
  const detail: StudentLessonHistoryDetail = {
    sourceTeacherId: 'former-teacher', sourceTeacherName: 'Преподаватель', eventId: 'lesson-1',
    title: 'Дроби', groupName: null, startsAt: '2026-10-23T14:00:00Z', endsAt: '2026-10-23T15:00:00Z',
    homeworkNotes: null, difficulties: null, lessonPlan: null, coveredMaterial: null,
    hasWorkbook: false, workbookFilename: null, hasAnswers: false, answersFilename: null,
    hasTranscript: true, transcriptFilename: 'Учитель.pdf', transcriptText: 'Текст преподавателя',
    sonioxTranscriptText: '[00:00:02] Student: Автоматическая фраза',
  };
  vi.mocked(api.students.lessonHistoryDetail).mockResolvedValue(detail);
  vi.mocked(api.students.lessonHistoryCompletedDocuments).mockResolvedValue([]);
  vi.mocked(api.students.lessonHistoryFile).mockResolvedValue(new Blob(['%PDF-1.7']));
  vi.stubGlobal('URL', class extends URL {
    static createObjectURL = vi.fn(() => 'blob:history-transcript');
    static revokeObjectURL = vi.fn();
  });

  render(<I18nProvider><MemoryRouter initialEntries={['/students/pupil/history/former-teacher/lesson-1']}><Routes>
    <Route path="/students/:studentId/history/:sourceTeacherId/:eventId" element={<StudentLessonHistoryDetailPage />} />
  </Routes></MemoryRouter></I18nProvider>);

  expect(await screen.findByRole('link', { name: 'Транскрипция: Учитель.pdf' })).toHaveAttribute('href', 'blob:history-transcript');
  expect(screen.getByText('Текст преподавателя')).toBeInTheDocument();
  expect(screen.getByRole('heading', { name: 'Автоматическая транскрипция Soniox' })).toBeInTheDocument();
  expect(screen.getByText('[00:00:02] Student: Автоматическая фраза')).toBeInTheDocument();
  expect(api.students.lessonHistoryDetail).toHaveBeenCalledWith('pupil', 'former-teacher', 'lesson-1');
});
