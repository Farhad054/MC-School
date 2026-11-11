import { createRoot } from 'react-dom/client';
import { useLayoutEffect, useRef, useState } from 'react';
import { RoomContext } from '@livekit/components-react';
import { ParticipantKind, RemoteParticipant, Room } from 'livekit-client';
import { I18nProvider } from '../../src/i18n/I18nContext';
import { CameraColumn } from '../../src/features/online-classes/CameraColumn';
import { WhiteboardPanel } from '../../src/features/online-classes/WhiteboardPanel';
import { AnswersPanelResizer } from '../../src/features/online-classes/AnswersPanelResizer';
import { ALL_STUDENT_BOARDS_ID, BoardSwitcher } from '../../src/features/online-classes/BoardSwitcher';
import { TranscriptionControls } from '../../src/features/online-classes/TranscriptionControls';
import { onlineClassesApi } from '../../src/api/onlineClasses';
import type { PdfDoc } from '../../src/features/online-classes/pdfDocument';
import '../../src/index.css';
import '../../src/online-classes.css';
import '../../src/styles/whiteboard-original-v3.css';

const teacher = '33333333-3333-4333-8333-333333333333';
const operations: unknown[] = [];
onlineClassesApi.listParticipants = async () => [{ userId: teacher, classRole: 'HOST' } as never];
onlineClassesApi.listAnnotationDocuments = async () => [];
onlineClassesApi.openAnnotationDocument = async (classId, targetType, targetId, pageIndex = 0) => ({
  id: `page-${pageIndex}`, classId, targetType, targetId, pageIndex,
} as never);
onlineClassesApi.replayAnnotations = async () => [];
onlineClassesApi.appendAnnotation = async (_classId, documentId, operationId, operationType, payload) => {
  const operation = { documentId, operationId, operationType, payload, sequence: operations.length + 1, actorId: teacher };
  operations.push(operation);
  return operation as never;
};
Object.assign(window, { cameraFixture: { operations } });
localStorage.setItem('language', 'RU');
localStorage.setItem(`mc.board.pageLayout:${teacher}`, 'continuous');
const room = new Room();
room.localParticipant.identity = teacher + '|browser'; room.localParticipant.name = 'Учитель';
for (let index = 1; index <= 3; index++) {
  const identity = `pupil-${index}|browser`;
  room.remoteParticipants.set(identity, new RemoteParticipant({} as never, `PA_${index}`, identity, `Ученица ${index}`));
}
room.remoteParticipants.set('transcription-agent', new RemoteParticipant({} as never, 'PA_agent', 'transcription-agent', '',
  undefined, undefined, undefined, ParticipantKind.AGENT));
room.remoteParticipants.set('notebook-phone:qa', new RemoteParticipant({} as never, 'PA_phone', 'notebook-phone:qa', 'Тетрадь'));
const document: PdfDoc = { pageCount: 2, destroy() {}, async renderPage(index, width) {
  const canvas = window.document.createElement('canvas');
  canvas.width = Math.ceil(width); canvas.height = Math.round(width * 1.414);
  const context = canvas.getContext('2d')!;
  context.fillStyle = '#fffefa'; context.fillRect(0, 0, canvas.width, canvas.height);
  context.fillStyle = '#333'; context.font = `${width * .04}px sans-serif`;
  context.fillText(`Worksheet ${index + 1}`, width * .1, width * .15);
  return { canvas, aspect: 1 / 1.414 };
} };

function App() {
  const [videos, setVideos] = useState(false), [activeBoard, setActiveBoard] = useState('board-1');
  const overview = activeBoard === ALL_STUDENT_BOARDS_ID;
  const topbar = useRef<HTMLElement>(null);
  useLayoutEffect(() => {
    const element = topbar.current!;
    const measure = () => element.parentElement!.style.setProperty('--classroom-header-height', `${element.offsetHeight}px`);
    const observer = new ResizeObserver(measure); observer.observe(element); measure();
    return () => observer.disconnect();
  }, []);
  const students = Array.from({ length: 4 }, (_, index) => ({ userId: `pupil-${index + 1}`, displayName: `Ученица ${index + 1}` }));
  const answers = new URLSearchParams(location.search).get('answers') === '1';
  return <div className="online-class-room online-class-room--original-v3 lk-room-container">
    <header className="online-class-room__topbar" ref={topbar}>
      <div className="online-class-room__lesson-island"><strong>Тестовый урок</strong><span className="online-class-room__status">В сети</span></div>
      <div className="online-class-room__lesson-actions">
        <div className="online-class-room__audio-focus"><span role="status">Вас слышит вся группа</span></div>
        <TranscriptionControls classId="floating-camera-fixture" isHost transcriptionState="ACTIVE" available />
      </div>
      <div className="online-class-room__board-controls">
        <BoardSwitcher isHost currentUserId={teacher} students={students} activeId={activeBoard} onChange={setActiveBoard} />
        <button className="online-class-room__videos-toggle" aria-expanded={videos} aria-controls="online-class-videos"
          onClick={() => setVideos(value => !value)}>Видео</button>
      </div>
    </header>
    <div className={`online-class-room__stage${videos ? ' online-class-room__stage--videos-open' : ''}${overview ? ' online-class-room__stage--student-workspaces' : ''}`}>
      {answers && <><aside className="answers-panel"><p>Ответы учителя</p></aside><AnswersPanelResizer storageKey="camera-qa" videosOpen={videos} /></>}
      <main className="online-class-room__main" style={{ position: 'relative' }}>
        <div className="online-class-room__board" style={{ position: 'absolute', inset: 0 }}>
          <WhiteboardPanel classId="floating-camera-fixture" actorId={teacher} isHost targetId="board-1" document={document} />
        </div>
      </main>
      <div id="online-class-videos" className={`online-class-room__videos-drawer${videos ? ' is-open' : ''}`}
        aria-hidden={!videos} ref={element => element?.toggleAttribute('inert', !videos)}>
        <CameraColumn hostUserId={teacher} localIsHost />
      </div>
    </div>
    <footer className="online-class-room__controls"><div className="online-class-room__control-island">Микрофон · Камера</div></footer>
  </div>;
}
createRoot(window.document.getElementById('root')!).render(<RoomContext.Provider value={room}><I18nProvider><App /></I18nProvider></RoomContext.Provider>);
