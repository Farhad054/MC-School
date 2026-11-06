import { useI18n } from '../../i18n/I18nContext';
import { SHARED_BOARD_ID, personalBoardId } from './boards';
import type { RosterStudent } from './useStudentRoster';

/**
 * Picks which board is on screen.
 *
 * A student toggles between the shared board and their own. The teacher picks
 * the shared board or any student's personal board; looking at one does not move
 * anyone else, because personal boards take no part in follow mode.
 */
export const ALL_STUDENT_BOARDS_ID = 'all-student-boards';

export function BoardSwitcher({
  isHost,
  currentUserId,
  students,
  activeId,
  onChange,
}: {
  isHost: boolean;
  currentUserId: string;
  students: RosterStudent[];
  activeId: string;
  onChange: (boardId: string) => void;
}) {
  const { t, language } = useI18n();

  if (isHost) {
    return (
      <div className="board-switcher board-switcher--teacher" role="group" aria-label={t('onlineClass.board.switch')}>
        <button type="button" aria-pressed={activeId === SHARED_BOARD_ID}
          className={activeId === SHARED_BOARD_ID ? 'is-active' : undefined}
          onClick={() => onChange(SHARED_BOARD_ID)}>
          {language === 'DE' ? 'Gemeinsame Tafel' : 'Общая группа'}
        </button>
        {students.map((student) => {
          const id = personalBoardId(student.userId);
          return <button key={student.userId} type="button" title={student.displayName}
            aria-label={t('onlineClass.board.studentBoard', { name: student.displayName })}
            aria-pressed={activeId === id} className={activeId === id ? 'is-active' : undefined}
            onClick={() => onChange(id)}>{student.displayName}</button>;
        })}
        <button type="button" className="board-switcher__overview" aria-pressed={activeId === ALL_STUDENT_BOARDS_ID}
          aria-label={language === 'DE' ? 'Alle Schüler (2×2)' : 'Все ученики (2×2)'}
          title={language === 'DE' ? 'Alle Schüler (2×2)' : 'Все ученики (2×2)'}
          onClick={() => onChange(ALL_STUDENT_BOARDS_ID)}>
          <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.6" aria-hidden="true">
            <rect x="3" y="3" width="7" height="7" rx="1.5" /><rect x="14" y="3" width="7" height="7" rx="1.5" />
            <rect x="3" y="14" width="7" height="7" rx="1.5" /><rect x="14" y="14" width="7" height="7" rx="1.5" />
          </svg>
          {language === 'DE' ? 'Alle' : 'Все'}
        </button>
      </div>
    );
  }

  const mine = personalBoardId(currentUserId);
  return (
    <div className="board-switcher" role="group" aria-label={t('onlineClass.board.switch')}>
      {([
        [SHARED_BOARD_ID, 'onlineClass.board.shared'],
        [mine, 'onlineClass.board.mine'],
      ] as const).map(([id, label]) => (
        <button
          key={id}
          type="button"
          aria-pressed={activeId === id}
          className={activeId === id ? 'is-active' : undefined}
          onClick={() => onChange(id)}
        >
          {t(label)}
        </button>
      ))}
    </div>
  );
}
