// Test-only: keep the real LiveKit hooks/context, replace external transport.
// Production builds do not use the alias in kolin-start-vite.mjs.
import { useMemo, type PropsWithChildren } from 'react';
import { RoomContext } from '@livekit/components-react-real';
import { ConnectionState, RemoteParticipant, Room } from 'livekit-client';
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
    return result;
  }, [token]);
  return <RoomContext.Provider value={room}><div className={`${className} lk-room-container`} data-lk-theme="default">{children}</div></RoomContext.Provider>;
}
