// Test-only: keep the real LiveKit hooks/context, replace external transport.
// Production builds do not use the alias in kolin-start-vite.mjs.
import { useMemo, type PropsWithChildren } from 'react';
import { RoomContext } from '@livekit/components-react-real';
import { ConnectionState, RemoteParticipant, Room, RoomEvent } from 'livekit-client';
import { encodeEvent, viewEvent, VIEW_TOPIC } from '../src/features/online-classes/events';
export * from '@livekit/components-react-real';

export function LiveKitRoom({ children, token, className }: PropsWithChildren<{ token: string; className: string }>) {
  const room = useMemo(() => {
    const result = new Room();
    result.localParticipant.identity = token.replace(/^fixture:/, '') + '|test';
    result.localParticipant.name = 'Учитель';
    result.localParticipant.publishData = async () => {};
    result.state = ConnectionState.Connected;
    for (const [id, name] of [
      ['11111111-1111-4111-8111-111111111111', 'София'],
      ['22222222-2222-4222-8222-222222222222', 'Мелисса'],
    ]) result.remoteParticipants.set(id + '|test', new RemoteParticipant({} as never, 'PA_' + id, id + '|test', name));
    Object.assign(window, { kolinPupilView: (classId: string, pupilId: string, boardId: string, page: number) => {
      result.emit(RoomEvent.DataReceived, encodeEvent(viewEvent(classId,
        { boardId, page, pageCount: 3, zoom: 1, panX: 0, panY: 0, follow: false })),
        result.remoteParticipants.get(pupilId + '|test'), undefined, VIEW_TOPIC);
    } });
    return result;
  }, [token]);
  return <RoomContext.Provider value={room}><div className={`${className} lk-room-container`} data-lk-theme="default">{children}</div></RoomContext.Provider>;
}
