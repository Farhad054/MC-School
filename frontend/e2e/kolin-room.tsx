import { createRoot } from 'react-dom/client';
import { useLayoutEffect, useState } from 'react';
import Konva from 'konva';
import { I18nProvider } from '../src/i18n/I18nContext';
import { OnlineClassRoom } from '../src/features/online-classes/OnlineClassRoom';
import '../src/index.css';
import '../src/online-classes.css';

const teacher = '33333333-3333-4333-8333-333333333333';
const student = '11111111-1111-4111-8111-111111111111';
const firstClass = 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa';
localStorage.setItem('language', 'RU');
localStorage.setItem('accessToken', 'fixture-user-session');

function App() {
  const [session, setSession] = useState({ classId: firstClass, teacherId: teacher,
    isHost: new URLSearchParams(location.search).get('role') !== 'student' });
  useLayoutEffect(() => {
    Object.assign(window, { kolinFixture: {
      setSession: (patch: Partial<typeof session>) => setSession(current => ({ ...current, ...patch })),
      visibleInkCount: () => Konva.stages.filter(stage => stage.container().getClientRects().length > 0)
        .reduce((sum, stage) => sum + (stage.getChildren()[1]?.getChildren().length ?? 0), 0),
    } });
  }, []);
  const userId = session.isHost ? session.teacherId : student;
  return <OnlineClassRoom classId={session.classId} currentUserId={userId}
    eventId="kolin-browser-lesson" lessonTitle="Математика: сложение дробей"
    connection={{ serverUrl: 'ws://fixture.invalid', token: 'fixture:' + userId,
      identity: userId + '|test', roomName: 'test-only', expiresAt: '2099-01-01T00:00:00Z',
      host: session.isHost, recordingActive: false, transcriptionActive: false }}
    recordingState="INACTIVE" transcriptionState="INACTIVE" recordingAvailable={false} transcriptionAvailable={false}
    initialMedia={{ cameraEnabled: false, microphoneEnabled: false }}
    onLeave={() => {}} onConnectionLost={() => {}} />;
}
createRoot(document.getElementById('root')!).render(<I18nProvider><App /></I18nProvider>);
