import { act, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { forwardRef, useImperativeHandle } from 'react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { onlineClassesApi } from '../../api/onlineClasses';
import { I18nProvider } from '../../i18n/I18nContext';
import {
  ANNOTATION_TOPIC,
  VIEW_TOPIC,
  annotationEvent,
  viewEvent,
  type ClassEvent,
} from './events';

// Konva needs a real canvas, which jsdom does not have. The canvas is replaced
// by a stub that exposes the props the panel gives it; its own behaviour is
// covered by the gesture, hit-test and geometry unit tests.
type CanvasProps = Record<string, any>;
let canvasProps: CanvasProps = {};
vi.mock('./WhiteboardCanvas', () => ({
  WhiteboardCanvas: forwardRef((props: CanvasProps, ref) => {
    canvasProps = props;
    useImperativeHandle(ref, () => ({
      toDataURL: () => 'data:image/png;base64,AAA',
      getSize: () => ({ width: 1000, height: 500 }),
    }));
    return <div data-testid="canvas" data-locked={String(props.navigationLocked)} />;
  }),
}));

const publish = vi.fn().mockResolvedValue(undefined);
const handlers: Record<string, (received: { event: ClassEvent; senderIdentity?: string }) => void> = {};
vi.mock('./useClassEvents', () => ({
  useClassEvents: (_classId: string, topic: string, onEvent: (typeof handlers)[string]) => {
    handlers[topic] = onEvent;
    return { publish };
  },
}));

vi.mock('./usePdf', () => ({ usePdfPage: () => null, usePdf: vi.fn() }));

vi.mock('../../api/onlineClasses', async () => {
  const actual = await vi.importActual<typeof import('../../api/onlineClasses')>('../../api/onlineClasses');
  return {
    ...actual,
    onlineClassesApi: {
      openAnnotationDocument: vi.fn(),
      replayAnnotations: vi.fn(),
      appendAnnotation: vi.fn(),
      listAnnotationDocuments: vi.fn(),
      listParticipants: vi.fn(),
      saveAnnotationSnapshot: vi.fn(),
    },
  };
});

import { WhiteboardPanel } from './WhiteboardPanel';

const api = vi.mocked(onlineClassesApi);

class FakeResizeObserver {
  observe() {}
  disconnect() {}
}

function mount(role: 'host' | 'student', extra: Partial<React.ComponentProps<typeof WhiteboardPanel>> = {}) {
  return render(
    <I18nProvider>
      <WhiteboardPanel
        classId="c1"
        actorId={role === 'host' ? 'teacher' : 'student'}
        isHost={role === 'host'}
        {...extra}
      />
    </I18nProvider>,
  );
}

const hostView = (patch = {}) =>
  viewEvent('c1', {
    boardId: 'board-1', page: 1, pageCount: 3, zoom: 2, panX: -0.5, panY: -0.25, follow: true, ...patch,
  });

describe('WhiteboardPanel', () => {
  let sequence = 0;
  beforeEach(() => {
    vi.clearAllMocks();
    vi.stubGlobal('ResizeObserver', FakeResizeObserver);
    sequence = 0;
    canvasProps = {};
    api.openAnnotationDocument.mockImplementation(async (_c, t, id, page = 0) => ({
      id: `doc-${page}`, targetType: t, targetId: id, pageIndex: page,
      sourceWidth: null, sourceHeight: null, revision: 0, snapshotSavedAt: null,
    }));
    api.replayAnnotations.mockResolvedValue([]);
    api.listAnnotationDocuments.mockResolvedValue([]);
    api.listParticipants.mockResolvedValue([
      { userId: 'teacher', classRole: 'HOST' } as never,
    ]);
    api.appendAnnotation.mockImplementation(
      async (_c, _d, operationId, operationType, payload) =>
        ({
          id: `r${++sequence}`, operationId, sequence, actorId: 'teacher', layerOwnerId: 'teacher',
          operationType, payload, createdAt: '',
        }) as never,
    );
  });

  it('gives a student the board and page switcher but no teacher-only controls', async () => {
    mount('student');
    await screen.findByTestId('canvas');
    expect(screen.queryByRole('button', { name: 'Очистить всё' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Следовать за учителем' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Ответы' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Добавить страницу' })).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Следующая страница' })).toBeInTheDocument();
  });

  it('gives the teacher clear-all, follow and the answers slot, and lets them add a page', async () => {
    const user = userEvent.setup();
    mount('host', { hostActions: <button type="button">Ответы</button> });
    await screen.findByTestId('canvas');
    expect(screen.getByRole('button', { name: 'Очистить всё' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Следовать за учителем' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Ответы' })).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'Добавить страницу' }));
    expect(screen.getAllByRole('status').some((node) => node.textContent === '2 / 2')).toBe(true);
  });

  it('opens a separate annotation document for each page', async () => {
    const user = userEvent.setup();
    mount('host');
    await waitFor(() => expect(api.openAnnotationDocument).toHaveBeenCalledWith('c1', 'WHITEBOARD', 'board-1', 0, undefined, undefined));
    await user.click(screen.getByRole('button', { name: 'Добавить страницу' }));
    await waitFor(() => expect(api.openAnnotationDocument).toHaveBeenCalledWith('c1', 'WHITEBOARD', 'board-1', 1, undefined, undefined));
  });

  it('saves a stroke, then tells the room about it', async () => {
    mount('host');
    await waitFor(() => expect(api.openAnnotationDocument).toHaveBeenCalled());
    await act(async () => {
      canvasProps.onCommit({ kind: 'pen', width: 0.004, points: [[0.1, 0.2]] });
    });
    await waitFor(() => expect(api.appendAnnotation).toHaveBeenCalled());
    await waitFor(() =>
      expect(publish).toHaveBeenCalledWith(
        expect.objectContaining({ type: 'annotation', documentId: 'doc-0', op: 'ADD', sequence: 1 }),
      ),
    );
  });

  it('fetches from the server when told something was saved, and shows what it finds', async () => {
    mount('student');
    await waitFor(() => expect(api.openAnnotationDocument).toHaveBeenCalled());
    await waitFor(() => expect(canvasProps.shapes).toEqual([]));
    api.replayAnnotations.mockResolvedValue([
      {
        id: 'r1', operationId: 'op-1', sequence: 4, actorId: 'teacher', layerOwnerId: 'teacher',
        operationType: 'ADD', payload: JSON.stringify({ kind: 'pen', points: [[0.1, 0.1]] }), createdAt: '',
      },
    ] as never);
    await act(async () => {
      handlers[ANNOTATION_TOPIC]({
        event: annotationEvent('c1', {
          documentId: 'doc-0', operationId: 'op-1', sequence: 4, op: 'ADD', layerOwnerId: 'teacher',
        }),
        senderIdentity: 'teacher|tab',
      });
    });
    await waitFor(() => expect(canvasProps.shapes).toHaveLength(1));
  });

  it('never applies the contents of a packet — a forged "clear all" does nothing', async () => {
    mount('student');
    await waitFor(() => expect(api.openAnnotationDocument).toHaveBeenCalled());
    api.replayAnnotations.mockResolvedValue([
      {
        id: 'r1', operationId: 'op-1', sequence: 1, actorId: 'teacher', layerOwnerId: 'teacher',
        operationType: 'ADD', payload: JSON.stringify({ kind: 'pen', points: [[0.1, 0.1]] }), createdAt: '',
      },
    ] as never);
    await act(async () => {
      handlers[ANNOTATION_TOPIC]({
        event: annotationEvent('c1', {
          documentId: 'doc-0', operationId: 'forged', sequence: 99, op: 'CLEAR_ALL', layerOwnerId: 'teacher',
        }),
        senderIdentity: 'student-2|tab',
      });
    });
    // The real stroke from the server is what is shown; the forged operation is not.
    await waitFor(() => expect(canvasProps.shapes).toHaveLength(1));
  });

  it('locks a student to the teacher when follow is on, and unlocks when it is off', async () => {
    mount('student');
    await screen.findByTestId('canvas');
    await waitFor(() => expect(api.listParticipants).toHaveBeenCalled());
    await act(async () => {
      await Promise.resolve();
    });

    await act(async () => {
      handlers[VIEW_TOPIC]({ event: hostView(), senderIdentity: 'teacher|tab' });
    });
    expect(screen.getByTestId('canvas')).toHaveAttribute('data-locked', 'true');
    expect(screen.getByRole('button', { name: 'Следующая страница' })).toBeDisabled();
    expect(screen.getByText(/Вы следуете за учителем/)).toBeInTheDocument();
    expect(canvasProps.view).toEqual({ zoom: 2, panX: -500, panY: -125 });

    await act(async () => {
      handlers[VIEW_TOPIC]({ event: hostView({ follow: false }), senderIdentity: 'teacher|tab' });
    });
    expect(screen.getByTestId('canvas')).toHaveAttribute('data-locked', 'false');
    expect(screen.getByRole('button', { name: 'Следующая страница' })).toBeEnabled();
  });

  it('does not let another student drag everyone around', async () => {
    mount('student');
    await screen.findByTestId('canvas');
    await act(async () => {
      await Promise.resolve();
    });
    await act(async () => {
      handlers[VIEW_TOPIC]({ event: hostView(), senderIdentity: 'student-2|tab' });
    });
    expect(screen.getByTestId('canvas')).toHaveAttribute('data-locked', 'false');
  });

  it('keeps the viewer’s read-only state when they may not annotate', async () => {
    mount('student', { canAnnotate: false });
    await screen.findByTestId('canvas');
    expect(screen.queryByRole('toolbar')).not.toBeInTheDocument();
    expect(canvasProps.readOnly).toBe(true);
  });

  describe('personal board', () => {
    const personal = { targetId: 'personal-student' };

    it('never announces strokes to the room', async () => {
      mount('student', personal);
      await waitFor(() => expect(api.openAnnotationDocument).toHaveBeenCalledWith('c1', 'WHITEBOARD', 'personal-student', 0, undefined, undefined));
      await act(async () => {
        canvasProps.onCommit({ kind: 'pen', width: 0.004, points: [[0.1, 0.2]] });
      });
      await waitFor(() => expect(api.appendAnnotation).toHaveBeenCalled());
      expect(publish).not.toHaveBeenCalledWith(expect.objectContaining({ type: 'annotation' }));
    });

    it('has no follow control for the teacher, and is not moved by the teacher', async () => {
      const { unmount } = mount('host', personal);
      await screen.findByTestId('canvas');
      expect(screen.queryByRole('button', { name: 'Следовать за учителем' })).not.toBeInTheDocument();
      unmount();

      mount('student', personal);
      await screen.findByTestId('canvas');
      await act(async () => {
        await Promise.resolve();
      });
      await act(async () => {
        handlers[VIEW_TOPIC]({ event: hostView({ boardId: 'personal-student' }), senderIdentity: 'teacher|tab' });
      });
      expect(screen.getByTestId('canvas')).toHaveAttribute('data-locked', 'false');
    });

    it('lets the owner add pages to their own board', async () => {
      mount('student', personal);
      await screen.findByTestId('canvas');
      expect(screen.getByRole('button', { name: 'Добавить страницу' })).toBeInTheDocument();
    });

    it('re-reads the board while it is on screen, and stops when it is not', async () => {
      vi.useFakeTimers({ shouldAdvanceTime: true });
      try {
        const { rerender } = mount('host', personal);
        await act(async () => {
          await vi.advanceTimersByTimeAsync(10);
        });
        api.replayAnnotations.mockClear();
        await act(async () => {
          await vi.advanceTimersByTimeAsync(4100);
        });
        const whileActive = api.replayAnnotations.mock.calls.length;
        expect(whileActive).toBeGreaterThanOrEqual(1);

        rerender(
          <I18nProvider>
            <WhiteboardPanel classId="c1" actorId="teacher" isHost targetId="personal-student" active={false} />
          </I18nProvider>,
        );
        api.replayAnnotations.mockClear();
        await act(async () => {
          await vi.advanceTimersByTimeAsync(6000);
        });
        expect(api.replayAnnotations).not.toHaveBeenCalled();
      } finally {
        vi.useRealTimers();
      }
    });
  });
});
