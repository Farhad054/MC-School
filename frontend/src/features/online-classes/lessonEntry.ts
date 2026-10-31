import type { OnlineClass } from '../../api/onlineClasses';

export type LessonEntryState = NonNullable<OnlineClass['entryState']>;

export function berlinLessonDate(value: string | number): string {
  const date = new Date(value);
  if (!Number.isFinite(date.getTime())) return '';
  const parts = new Intl.DateTimeFormat('en-CA', {
    timeZone: 'Europe/Berlin', year: 'numeric', month: '2-digit', day: '2-digit',
  }).formatToParts(date);
  const part = (type: string) => parts.find(value => value.type === type)?.value ?? '';
  return `${part('year')}-${part('month')}-${part('day')}`;
}

/** UI can narrow a stale server grant, never invent permission to connect. */
export function lessonEntry(room: OnlineClass | undefined | null, scheduledStart: string, now = Date.now()) {
  const date = room?.entryDate ?? berlinLessonDate(scheduledStart);
  // Calendar arithmetic handles DST. A Saturday/Sunday lesson expires on the
  // immediately following Monday at 00:00 Europe/Berlin.
  const stamp = new Date(date + 'T12:00:00Z');
  const day = stamp.getUTCDay();
  const daysUntilMonday = (8 - day) % 7 || 7;
  stamp.setUTCDate(stamp.getUTCDate() + daysUntilMonday);
  const weekEnd = Number.isFinite(stamp.getTime()) ? stamp.toISOString().slice(0, 10) : '';
  let state: LessonEntryState;
  if (room?.status === 'ENDED' || room?.status === 'CANCELLED' || room?.entryState === 'ENDED'
      || (room && (room.status === 'LIVE' || room.status === 'LOBBY_OPEN')
          && weekEnd !== '' && berlinLessonDate(now) >= weekEnd)) {
    state = 'ENDED';
  } else {
    const today = berlinLessonDate(now);
    const openRoom = room?.status === 'LIVE' || room?.status === 'LOBBY_OPEN';
    const returning = Boolean(room?.viewerHasJoined || room?.entryState === 'RECONNECT');
    if (!date || today < date || (today > date && !(openRoom && returning && room?.joinWindowOpen))) {
      state = 'DAY_CLOSED';
    } else if (room?.entryState === 'CLOSED') {
      state = 'CLOSED';
    } else if (!room || !openRoom) {
      state = 'WAITING_FOR_TEACHER';
    } else if (!room.joinWindowOpen) {
      state = today === date ? 'CLOSED' : 'DAY_CLOSED';
    } else {
      state = today === date ? 'OPEN' : 'RECONNECT';
    }
  }
  return { state, date, canJoin: state === 'OPEN' || state === 'RECONNECT' };
}

export function entryText(state: LessonEntryState, language: string): string {
  const labels: Record<LessonEntryState, [string, string]> = {
    WAITING_FOR_TEACHER: ['Ожидаем учителя: учитель ещё не открыл урок.', 'Wir warten auf die Lehrkraft. Der Unterricht ist noch nicht geöffnet.'],
    OPEN: ['Урок открыт', 'Unterricht geöffnet'],
    RECONNECT: ['Урок продолжается: можно вернуться', 'Der Unterricht läuft weiter: Wiederbeitritt möglich'],
    DAY_CLOSED: ['Вход доступен только в день урока по Берлину.', 'Beitritt nur am Unterrichtstag (Berliner Zeit).'],
    CLOSED: ['Вход в комнату закрыт.', 'Der Raum ist für den Beitritt geschlossen.'],
    ENDED: ['Урок завершён', 'Unterricht beendet'],
  };
  return labels[state][language === 'DE' ? 1 : 0];
}
