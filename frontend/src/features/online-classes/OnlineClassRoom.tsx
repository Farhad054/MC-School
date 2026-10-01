import {
  ConnectionStateToast,
  ControlBar,
  FocusLayout,
  FocusLayoutContainer,
  LiveKitRoom,
  RoomAudioRenderer,
  useConnectionState,
  useTracks,
} from '@livekit/components-react';
import '@livekit/components-styles';
import { ConnectionState, Track } from 'livekit-client';
import { useI18n } from '../../i18n/I18nContext';
import type { ClassFeatureState, OnlineClassConnection } from '../../api/onlineClasses';
import { ChatPanel } from './ChatPanel';
import { Suspense, lazy, useEffect, useRef, useState } from 'react';
import { AnswersButton } from './AnswersButton';
import { AnswersPanel } from './AnswersPanel';
import { CameraColumn } from './CameraColumn';
import { CaptionsPanel } from './CaptionsPanel';
import { RecordingControls } from './RecordingControls';
import { TranscriptionControls } from './TranscriptionControls';
import { ParticipantListPanel } from './ParticipantListPanel';
import { WaitingRoomPanel } from './WaitingRoomPanel';
import { useAnswersPanel } from './useAnswersPanel';
import { useHostUserId } from './useHostUserId';
import type { TranslationKey } from '../../i18n/translations';

// Konva is ~300 kB; the board gets its own lazy boundary inside the already-lazy
// class route.
const WhiteboardPanel = lazy(() =>
  import('./WhiteboardPanel').then((m) => ({ default: m.WhiteboardPanel })),
);

/**
 * The shared screen, when someone is presenting. Cameras are not drawn here:
 * they live in the permanent column on the right.
 */
function ScreenShareView() {
  const tracks = useTracks([{ source: Track.Source.ScreenShare, withPlaceholder: false }], {
    onlySubscribed: false,
  });
  const share = tracks[0];
  if (!share) return null;
  return (
    <FocusLayoutContainer>
      <FocusLayout trackRef={share} />
    </FocusLayoutContainer>
  );
}

/** True while anyone is sharing their screen. */
function useScreenShareActive(): boolean {
  const tracks = useTracks([{ source: Track.Source.ScreenShare, withPlaceholder: false }], {
    onlySubscribed: false,
  });
  return tracks.length > 0;
}

function statusKeyFor(state: ConnectionState): TranslationKey {
  switch (state) {
    case ConnectionState.Connected:
      return 'onlineClass.status.connected';
    case ConnectionState.Reconnecting:
      return 'onlineClass.status.reconnecting';
    case ConnectionState.Disconnected:
      return 'onlineClass.status.disconnected';
    default:
      return 'onlineClass.status.connecting';
  }
}

/** Announces connection changes; must be rendered inside the room context. */
function ConnectionStatus() {
  const { t } = useI18n();
  const state = useConnectionState();

  return (
    <div role="status" aria-live="polite" className="online-class-room__status">
      {t(statusKeyFor(state))}
    </div>
  );
}

/**
 * Everything inside the LiveKit room context: the board as the main surface,
 * the permanent camera column on the right, a drawer for chat and people, and
 * a compact control bar.
 */
function RoomBody({
  classId,
  currentUserId,
  eventId,
  connection,
  recordingState,
  transcriptionState,
  studentAnnotationAllowed,
  onLeave,
  onEndForAll,
  onStateChanged,
}: {
  classId: string;
  currentUserId: string;
  eventId?: string;
  connection: OnlineClassConnection;
  recordingState: ClassFeatureState;
  transcriptionState: ClassFeatureState;
  studentAnnotationAllowed: boolean;
  onLeave: () => void;
  onEndForAll?: () => void;
  onStateChanged?: () => void;
}) {
  const { t } = useI18n();
  const [panelsOpen, setPanelsOpen] = useState(false);
  const [waiting, setWaiting] = useState(0);
  const [mainView, setMainView] = useState<'board' | 'screen'>('board');
  const sharing = useScreenShareActive();
  const wasSharing = useRef(false);
  const hostUserId = useHostUserId(classId);
  const answers = useAnswersPanel(eventId, connection.host);

  // A new screen share takes the main area; the user can switch back to the
  // board at any time, and the board keeps its marks while it is not shown.
  useEffect(() => {
    if (sharing && !wasSharing.current) setMainView('screen');
    if (!sharing) setMainView('board');
    wasSharing.current = sharing;
  }, [sharing]);

  const showScreen = sharing && mainView === 'screen';

  return (
    <>
      <div className="online-class-room__topbar">
        <ConnectionStatus />
        <RecordingControls
          classId={classId}
          isHost={connection.host}
          recordingState={recordingState}
          onChanged={onStateChanged}
        />
        <TranscriptionControls
          classId={classId}
          isHost={connection.host}
          transcriptionState={transcriptionState}
          onChanged={onStateChanged}
        />
      </div>

      <div className="online-class-room__stage">
        <div className="online-class-room__main">
          {/* The board stays mounted while a screen is shown, so switching back
              returns to the same page, zoom, tool and marks. */}
          <div className="online-class-room__board" hidden={showScreen}>
            <Suspense fallback={null}>
              <WhiteboardPanel
                classId={classId}
                actorId={currentUserId}
                isHost={connection.host}
                canAnnotate={connection.host || studentAnnotationAllowed}
                hostActions={
                  connection.host ? (
                    <AnswersButton status={answers.status} open={answers.open} onToggle={answers.toggle} />
                  ) : undefined
                }
                overlay={
                  connection.host && answers.open && answers.status === 'available' ? (
                    <AnswersPanel
                      pdf={answers.pdf}
                      view={answers.view}
                      getView={answers.getView}
                      onViewChange={answers.setView}
                      onScroll={answers.rememberScroll}
                      onClose={answers.close}
                    />
                  ) : undefined
                }
              />
            </Suspense>
          </div>
          {showScreen && (
            <div className="online-class-room__screen">
              <ScreenShareView />
            </div>
          )}

          {/* Kept mounted (just hidden) so chat history, the roster and the
              waiting-room poll are not reset by closing the drawer. */}
          <aside
            id="online-class-drawer"
            className="online-class-room__drawer"
            hidden={!panelsOpen}
            aria-label={t('onlineClass.room.panels')}
          >
            {/* The waiting room is host-only; the server rejects it for students
                regardless of what is rendered here. */}
            {connection.host && <WaitingRoomPanel classId={classId} onPendingChange={setWaiting} />}
            <ParticipantListPanel classId={classId} isHost={connection.host} />
            <ChatPanel classId={classId} currentUserId={currentUserId} isHost={connection.host} />
          </aside>
        </div>

        <CameraColumn hostUserId={hostUserId} localIsHost={connection.host} />
      </div>

      <CaptionsPanel enabled={transcriptionState === 'ACTIVE'} />
      <RoomAudioRenderer />
      <ConnectionStateToast />

      <div className="online-class-room__controls">
        <ControlBar variation="verbose" />
        <button
          type="button"
          aria-pressed={panelsOpen}
          aria-controls="online-class-drawer"
          onClick={() => setPanelsOpen((open) => !open)}
        >
          {t('onlineClass.room.panels')}
          {waiting > 0 && <span className="online-class-room__badge"> ({waiting})</span>}
        </button>
        {sharing && (
          <button type="button" onClick={() => setMainView(showScreen ? 'board' : 'screen')}>
            {t(showScreen ? 'onlineClass.room.showBoard' : 'onlineClass.room.showScreen')}
          </button>
        )}
        <button type="button" className="online-class-room__leave" onClick={onLeave}>
          {t('onlineClass.leave')}
        </button>
        {onEndForAll && (
          <button
            type="button"
            className="online-class-room__end"
            onClick={() => {
              if (window.confirm(t('onlineClass.endConfirm'))) {
                onEndForAll();
              }
            }}
          >
            {t('onlineClass.end')}
          </button>
        )}
      </div>
    </>
  );
}

/**
 * Room shell. Built on the official LiveKit React components rather than a
 * hand-rolled WebRTC layer; MC-School owns the surrounding authorization,
 * localization and chrome.
 */
export function OnlineClassRoom({
  classId,
  currentUserId,
  eventId,
  connection,
  recordingState,
  transcriptionState,
  studentAnnotationAllowed = false,
  onLeave,
  onEndForAll,
  onStateChanged,
}: {
  classId: string;
  currentUserId: string;
  /** Calendar event of the lesson; used to find the teacher's bound answers file. */
  eventId?: string;
  connection: OnlineClassConnection;
  recordingState: ClassFeatureState;
  transcriptionState: ClassFeatureState;
  /** Teacher-governed: students annotate only when the class allows it. */
  studentAnnotationAllowed?: boolean;
  onLeave: () => void;
  onEndForAll?: () => void;
  onStateChanged?: () => void;
}) {
  return (
    <LiveKitRoom
      serverUrl={connection.serverUrl}
      token={connection.token}
      connect
      video={false}
      audio={false}
      // Adaptive streaming and dynacast keep classroom video stable on weak
      // school networks instead of chasing maximum resolution.
      options={{ adaptiveStream: true, dynacast: true }}
      onDisconnected={onLeave}
      data-lk-theme="default"
      className="online-class-room"
    >
      <RoomBody
        classId={classId}
        currentUserId={currentUserId}
        eventId={eventId}
        connection={connection}
        recordingState={recordingState}
        transcriptionState={transcriptionState}
        studentAnnotationAllowed={studentAnnotationAllowed}
        onLeave={onLeave}
        onEndForAll={onEndForAll}
        onStateChanged={onStateChanged}
      />
    </LiveKitRoom>
  );
}
