import { act, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { lessonBoardApi, type BoardOperation, type BoardSnapshot } from '../../api/lessonBoard';
import { LessonBoardPage } from './LessonBoardPage';

const account = vi.hoisted(() => ({ id: 'regular-teacher', role: 'TEACHER' }));
vi.mock('../../auth/AuthContext', () => ({ useAuth: () => ({ user: account }) }));
vi.mock('../../api/lessonBoard', () => ({ lessonBoardApi: { start: vi.fn(), read: vi.fn(), append: vi.fn() } }));
vi.mock('../../features/notebook-camera/NotebookCameraPanel', () => ({
  NotebookCameraButton: ({ currentUserId, isTeacher }: { currentUserId: string; isTeacher: boolean }) =>
    <button data-user={currentUserId} data-teacher={String(isTeacher)}>Камера тетради</button>,
}));

function snapshot(teacher = true): BoardSnapshot {
  return { title: 'Урок нового учителя', eventId: 'local-lesson', teacher, meetUrl: null, operations: [] };
}
function operation(id: number, actorId: string): BoardOperation {
  return { id, actorId, kind: 'STROKE', payload: JSON.stringify({ kind: 'STROKE', points: [{ x: 0.2, y: 0.3 }] }), createdAt: '' };
}
function mount() {
  return render(<MemoryRouter initialEntries={['/lesson-board/local-lesson']}><Routes>
    <Route path="/lesson-board/:eventId" element={<LessonBoardPage />} />
  </Routes></MemoryRouter>);
}
beforeEach(() => {
  vi.clearAllMocks();
  account.id = 'regular-teacher'; account.role = 'TEACHER';
  vi.spyOn(HTMLCanvasElement.prototype, 'getContext').mockReturnValue(null);
  vi.mocked(lessonBoardApi.start).mockResolvedValue(snapshot());
  vi.mocked(lessonBoardApi.read).mockResolvedValue(snapshot(false));
  vi.mocked(lessonBoardApi.append).mockResolvedValue({ id: 3, actorId: account.id, kind: 'CLEAR', payload: '{"kind":"CLEAR"}', createdAt: '' });
});

describe('standalone lesson board role and server access', () => {
  it('starts a non-pilot teacher’s board and enables teacher tools only after server authorization', async () => {
    let finish!: (value: BoardSnapshot) => void;
    vi.mocked(lessonBoardApi.start).mockReturnValue(new Promise(resolve => { finish = resolve; }));
    mount();
    expect(lessonBoardApi.start).toHaveBeenCalledExactlyOnceWith('local-lesson');
    expect(lessonBoardApi.read).not.toHaveBeenCalled();
    expect(screen.queryByRole('button', { name: 'Очистить' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Камера тетради' })).not.toBeInTheDocument();
    await act(async () => { finish(snapshot()); });
    expect(screen.getByRole('button', { name: 'Очистить' })).toBeEnabled();
    expect(screen.getByRole('button', { name: 'Скопировать ссылку ученику' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Камера тетради' })).toHaveAttribute('data-user', 'regular-teacher');
    expect(screen.getByRole('button', { name: 'Камера тетради' })).toHaveAttribute('data-teacher', 'true');
    expect(screen.getByRole('link', { name: '← Назад к урокам' })).toHaveAttribute('href', '/teacher/lessons');
    const confirm = vi.spyOn(window, 'confirm').mockReturnValue(true);
    try {
      await userEvent.click(screen.getByRole('button', { name: 'Очистить' }));
      await waitFor(() => expect(lessonBoardApi.append).toHaveBeenCalledWith('local-lesson', { kind: 'CLEAR' }));
    } finally { confirm.mockRestore(); }
  });

  it('loads the assigned pupil board without teacher actions and undoes only that pupil’s work', async () => {
    account.id = 'regular-student'; account.role = 'STUDENT';
    vi.mocked(lessonBoardApi.read).mockResolvedValue({ ...snapshot(false), operations: [operation(1, account.id), operation(2, 'another-student')] });
    mount();
    expect(await screen.findByRole('heading', { name: 'Урок нового учителя' })).toBeInTheDocument();
    expect(lessonBoardApi.read).toHaveBeenCalledExactlyOnceWith('local-lesson', 0);
    expect(lessonBoardApi.start).not.toHaveBeenCalled();
    expect(screen.queryByRole('button', { name: 'Очистить' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Скопировать ссылку ученику' })).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Камера тетради' })).toHaveAttribute('data-teacher', 'false');
    expect(screen.getByRole('link', { name: '← Назад к урокам' })).toHaveAttribute('href', '/student/lessons');
    await userEvent.click(screen.getByRole('button', { name: 'Отменить' }));
    await waitFor(() => expect(lessonBoardApi.append).toHaveBeenCalledWith('local-lesson', { kind: 'UNDO', targetId: 1 }));
  });

  it('does not grant teacher controls solely from the account role when the snapshot says otherwise', async () => {
    vi.mocked(lessonBoardApi.start).mockResolvedValue(snapshot(false));
    mount();
    await screen.findByRole('heading', { name: 'Урок нового учителя' });
    expect(screen.queryByRole('button', { name: 'Очистить' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Скопировать ссылку ученику' })).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Камера тетради' })).toHaveAttribute('data-teacher', 'false');
  });

  it('keeps tools and notebook camera unavailable when the server denies the lesson', async () => {
    vi.mocked(lessonBoardApi.start).mockRejectedValue(new Error('Доска недоступна'));
    mount();
    expect(await screen.findByRole('alert')).toHaveTextContent('Доска недоступна');
    expect(screen.getByRole('button', { name: 'Ручка' })).toBeDisabled();
    expect(screen.queryByRole('button', { name: 'Очистить' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Камера тетради' })).not.toBeInTheDocument();
    expect(lessonBoardApi.append).not.toHaveBeenCalled();
  });
});
