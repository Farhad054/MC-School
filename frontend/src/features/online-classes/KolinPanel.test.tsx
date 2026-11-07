import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { kolinApi, type KolinTranscriptContext } from '../../api/kolin';
import { I18nProvider } from '../../i18n/I18nContext';
import { KolinPanel } from './KolinPanel';
import { useKolinChat } from './useKolinChat';

vi.mock('../../api/kolin', async importOriginal => ({ ...await importOriginal<typeof import('../../api/kolin')>(),
  kolinApi: { status: vi.fn(), send: vi.fn() } }));
const transcriptContext: KolinTranscriptContext = { state: 'included', segmentCount: 12, truncated: true,
  latestSegmentAt: '2026-11-07T15:45:00Z', fromMs: 120000, toMs: 525000, transcriptionActive: false };
function Harness() {
  const chat = useKolinChat('class-1', 'teacher', true);
  return <I18nProvider><button onClick={chat.toggle}>Kolin</button>
    {chat.open && <KolinPanel chat={chat} context={{ targetType: 'student', studentId: 'alice' }} />}
  </I18nProvider>;
}
beforeEach(() => {
  localStorage.setItem('language', 'RU');
  vi.mocked(kolinApi.status).mockReset().mockResolvedValue({ configured: true });
  vi.mocked(kolinApi.send).mockReset().mockResolvedValue({ type: 'text', text: '1) 1/2 + 1/4\n2) 2/3 + 1/6' });
});

describe('Kolin text panel', () => {
  it('sends Enter, keeps Shift+Enter as a newline and retains history after closing', async () => {
    render(<Harness />);
    await userEvent.click(screen.getByRole('button', { name: 'Kolin' }));
    expect(screen.getByText('AI-помощник преподавателя')).toBeInTheDocument();
    expect(screen.getByText('Спросите меня о текущем уроке или попросите создать задание.')).toBeInTheDocument();
    expect(screen.getByText(/Когда вы задаёте вопрос, я использую сохранённую расшифровку/)).toBeInTheDocument();
    const input = screen.getByRole('textbox', { name: 'Напишите Kolin…' });
    await userEvent.type(input, 'Дай задания');
    await userEvent.keyboard('{Shift>}{Enter}{/Shift}');
    expect(input).toHaveValue('Дай задания\n');
    expect(kolinApi.send).not.toHaveBeenCalled();
    await userEvent.keyboard('{Enter}');
    await waitFor(() => expect(screen.getByRole('log')).toHaveTextContent('1) 1/2 + 1/4'));
    expect(kolinApi.send).toHaveBeenCalledExactlyOnceWith('class-1', 'Дай задания', [], { targetType: 'student', studentId: 'alice' });
    expect(screen.queryByText('Спросите меня о текущем уроке или попросите создать задание.')).not.toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Закрыть Kolin' }));
    expect(screen.queryByRole('complementary', { name: 'Kolin' })).not.toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Kolin' }));
    expect(screen.getByRole('log')).toHaveTextContent('1) 1/2 + 1/4');
    expect(screen.getAllByText('Дай задания')).toHaveLength(1);
  });

  it('shows pending state, blocks repeated sending and renders model content as plain text', async () => {
    let resolve!: (value: { type: 'text'; text: string }) => void;
    vi.mocked(kolinApi.send).mockImplementationOnce(() => new Promise(done => { resolve = done; }));
    const view = render(<Harness />);
    await userEvent.click(screen.getByRole('button', { name: 'Kolin' }));
    await userEvent.type(screen.getByRole('textbox'), 'Ответ?');
    await userEvent.click(screen.getByRole('button', { name: 'Отправить' }));
    expect(screen.getByRole('status')).toHaveTextContent('Kolin думает…');
    expect(screen.getByRole('button', { name: 'Отправить' })).toBeDisabled();
    expect(screen.getByRole('textbox')).toBeDisabled();
    resolve({ type: 'text', text: '<img src=x onerror=alert(1)>\nОтвет: 3/4' });
    await waitFor(() => expect(screen.queryByText('Kolin думает…')).not.toBeInTheDocument());
    expect(view.container.querySelector('img')).toBeNull();
    expect(screen.getByRole('log')).toHaveTextContent('<img src=x onerror=alert(1)>');
    expect(screen.queryByLabelText('Контекст расшифровки')).not.toBeInTheDocument();
  });

  it('shows the bounded transcript window, freshness and stopped state beside its answer', async () => {
    vi.mocked(kolinApi.send).mockResolvedValueOnce({ type: 'text', text: 'Here is the exercise.', transcriptContext });
    render(<Harness />);
    await userEvent.click(screen.getByRole('button', { name: 'Kolin' }));
    await userEvent.type(screen.getByRole('textbox'), 'Дай задание по уроку{Enter}');
    const note = await screen.findByLabelText('Контекст расшифровки');
    expect(note).toHaveAttribute('data-transcript-state', 'included');
    expect(note).toHaveTextContent('Расшифровка: 12 реплик, 02:00-08:45 урока. Фрагмент.');
    expect(note).toHaveTextContent('На момент запроса расшифровка была неактивна.');
    expect(note.querySelector('time')).toHaveAttribute('datetime', transcriptContext.latestSegmentAt);
    expect(note.querySelector('time')).toHaveTextContent(/\d{2}:\d{2}/);
    expect(note.closest('[data-role]')).toHaveAttribute('data-role', 'assistant');
    await userEvent.click(screen.getByRole('button', { name: 'Закрыть Kolin' }));
    await userEvent.click(screen.getByRole('button', { name: 'Kolin' }));
    expect(screen.getByLabelText('Контекст расшифровки')).toHaveTextContent('12 реплик');
    expect(kolinApi.send).toHaveBeenCalledTimes(1);
  });

  it.each([
    ['empty', 'Расшифровка: сохранённых реплик пока нет.'],
    ['unavailable', 'Расшифровку не удалось прочитать. Ответ без неё.'],
  ] as const)('explains %s transcript without hiding the answer', async (state, copy) => {
    vi.mocked(kolinApi.send).mockResolvedValueOnce({ type: 'text', text: 'Still a useful answer.',
      transcriptContext: { ...transcriptContext, state, segmentCount: 0, truncated: false, latestSegmentAt: null,
        fromMs: null, toMs: null, transcriptionActive: true } });
    render(<Harness />);
    await userEvent.click(screen.getByRole('button', { name: 'Kolin' }));
    await userEvent.type(screen.getByRole('textbox'), 'Вопрос{Enter}');
    const note = await screen.findByLabelText('Контекст расшифровки');
    expect(note).toHaveTextContent(copy);
    expect(screen.getByRole('log')).toHaveTextContent('Still a useful answer.');
    expect(note.querySelector('time')).toBeNull();
    expect(note).not.toHaveTextContent('была неактивна');
  });

  it('localizes transcript context in German and handles an unknown freshness time', async () => {
    localStorage.setItem('language', 'DE');
    vi.mocked(kolinApi.send).mockResolvedValueOnce({ type: 'text', text: 'Die Aufgabe.',
      transcriptContext: { ...transcriptContext, latestSegmentAt: null, fromMs: null, truncated: false,
        transcriptionActive: true } });
    render(<Harness />);
    await userEvent.click(screen.getByRole('button', { name: 'Kolin' }));
    expect(screen.getByText(/Bei Ihrer Frage nutze ich das gespeicherte Transkript/)).toBeInTheDocument();
    await userEvent.type(screen.getByRole('textbox'), 'Noch eine Aufgabe{Enter}');
    const note = await screen.findByLabelText('Transkript-Kontext');
    expect(note).toHaveTextContent('Transkript: 12 Beiträge, bis 08:45 im Unterricht.');
    expect(note).toHaveTextContent('Zeit des letzten Beitrags unbekannt.');
    expect(note).not.toHaveTextContent('Ausschnitt');
  });

  it('keeps the draft after an error and exposes one retry action', async () => {
    vi.mocked(kolinApi.send).mockRejectedValueOnce(new Error('private provider details'));
    render(<Harness />);
    await userEvent.click(screen.getByRole('button', { name: 'Kolin' }));
    await userEvent.type(screen.getByRole('textbox'), 'Объясни проще');
    fireEvent.submit(screen.getByRole('button', { name: 'Отправить' }).closest('form')!);
    const alert = await screen.findByRole('alert');
    expect(alert).not.toHaveTextContent('private provider details');
    expect(screen.getByRole('textbox')).toHaveValue('Объясни проще');
    await userEvent.click(screen.getByRole('button', { name: 'Повторить отправку' }));
    await waitFor(() => expect(screen.queryByRole('alert')).not.toBeInTheDocument());
    expect(screen.getAllByText('Объясни проще')).toHaveLength(1);
    expect(kolinApi.send).toHaveBeenCalledTimes(2);
  });
});
