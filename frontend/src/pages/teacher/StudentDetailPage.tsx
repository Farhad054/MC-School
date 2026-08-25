import { useCallback, useEffect, useState, type FormEvent } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { api } from '../../api/client';
import type { CardSummary, DailyReviewHistoryItem, DailyReviewStatus, Homework } from '../../api/types';
import { useI18n } from '../../i18n/I18nContext';
import { toErrorMessage } from '../../lib/errors';

/** Minimum cards a student needs before any session can start (mirrors the backend). */
const MIN_CARDS_TO_START = 4;

/** A single student's cards: status summary, add-cards panel, and the editable card list. */
export function StudentDetailPage() {
  const { studentId = '' } = useParams();
  const navigate = useNavigate();
  const { language, t } = useI18n();
  const [homeworks, setHomeworks] = useState<Homework[]>([]);
  const [newHomeworkDate, setNewHomeworkDate] = useState(() => new Date().toISOString().slice(0, 10));
  const [summary, setSummary] = useState<CardSummary | null>(null);
  const [reviewHistory, setReviewHistory] = useState<DailyReviewHistoryItem[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [pilotMessage, setPilotMessage] = useState<string | null>(null);
  const [pilotBusy, setPilotBusy] = useState(false);
  const [loading, setLoading] = useState(true);

  const reload = useCallback(async () => {
    const [homeworkList, cardSummary, history] = await Promise.all([
      api.homeworks.listForStudent(studentId),
      api.cards.summaryForStudent(studentId),
      api.students.reviewHistory(studentId),
    ]);
    setHomeworks(homeworkList);
    setSummary(cardSummary);
    setReviewHistory(history);
    setLoading(false);
  }, [studentId]);

  useEffect(() => {
    reload().catch((e) => setError(toErrorMessage(e, t)));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [reload]);

  async function createHomework(event: FormEvent) {
    event.preventDefault();
    setError(null);
    try {
      const homework = await api.homeworks.create(studentId, newHomeworkDate);
      navigate(`/teacher/students/${studentId}/homeworks/${homework.id}`);
    } catch (e) {
      setError(toErrorMessage(e, t));
    }
  }

  async function makeOneCardDueToday() {
    setPilotBusy(true);
    setPilotMessage(null);
    setError(null);
    try {
      const card = await api.students.makeOneCardDueToday(studentId);
      setPilotMessage(t('pilot.cardDueResult', { question: card.question, date: card.dueDate }));
      await reload();
    } catch (e) {
      setError(toErrorMessage(e, t));
    } finally {
      setPilotBusy(false);
    }
  }

  async function sendTestReviewReminder() {
    setPilotBusy(true);
    setPilotMessage(null);
    setError(null);
    try {
      const result = await api.students.testReviewReminder(studentId);
      setPilotMessage(result.reminderAttempted
        ? t('pilot.reminderSent', { count: result.dueCount })
        : t('pilot.reminderSkipped'));
      await reload();
    } catch (e) {
      setError(toErrorMessage(e, t));
    } finally {
      setPilotBusy(false);
    }
  }

  return (
    <div>
      <p>
        <Link to="/students" className="muted">← {t('common.back')}</Link>
      </p>
      <h1>{t('cards.title')}</h1>
      {error && <div className="banner banner--error">{error}</div>}

      {summary && (
        <>
          <div className="panel row center">
            <SummaryStat label={t('cards.summary.total')} value={summary.total} />
            <SummaryStat label={t('cards.summary.dueNow')} value={summary.dueNow} />
            <SummaryStat label={t('cards.summary.awaiting')} value={summary.awaitingRepetition} />
            <SummaryStat label={t('cards.summary.learned')} value={summary.learned} />
          </div>
          {summary.total < MIN_CARDS_TO_START && (
            <div className="banner banner--info">{t('cards.tooFew', { min: MIN_CARDS_TO_START })}</div>
          )}
        </>
      )}

      <h2>{t('reviewHistory.title')}</h2>
      <div className="panel">
        {reviewHistory.length === 0 ? (
          <p className="muted">{t('reviewHistory.empty')}</p>
        ) : (
          <div className="history-list">
            {reviewHistory.map((item) => (
              <div key={item.date} className="history-row">
                <span>{formatHistoryDate(item.date, language)}</span>
                <span>{item.completedCount}/{item.dueCount}</span>
                <span className={`pill ${statusClass(item.status)}`}>
                  {t(`reviewHistory.status.${item.status}`)}
                </span>
              </div>
            ))}
          </div>
        )}
      </div>

      <h2>{t('pilot.title')}</h2>
      <div className="panel stack">
        {pilotMessage && <div className="banner banner--success">{pilotMessage}</div>}
        <div className="row">
          <button className="btn btn--secondary" type="button" onClick={makeOneCardDueToday} disabled={pilotBusy}>
            {t('pilot.makeDueToday')}
          </button>
          <button className="btn" type="button" onClick={sendTestReviewReminder} disabled={pilotBusy}>
            {t('pilot.sendReminder')}
          </button>
        </div>
      </div>

      <h2>{t('homeworks.create')}</h2>
      <div className="panel stack">
        <form className="row" onSubmit={createHomework}>
          <label className="field" style={{ margin: 0 }}>
            <span className="field__label">{t('homeworks.startDate')}</span>
            <input
              className="input"
              type="date"
              value={newHomeworkDate}
              onChange={(e) => setNewHomeworkDate(e.target.value)}
              required
            />
          </label>
          <button className="btn" type="submit">{t('homeworks.create')}</button>
        </form>
      </div>

      <h2>{t('homeworks.title')}</h2>
      <div className="panel stack">
        {loading ? (
          <p className="muted">{t('common.loading')}</p>
        ) : homeworks.length === 0 ? (
          <p className="muted">{t('homeworks.empty')}</p>
        ) : (
          homeworks.map((homework) => (
            <Link
              key={homework.id}
              className="list-row"
              to={`/teacher/students/${studentId}/homeworks/${homework.id}`}
              style={{ textDecoration: 'none' }}
            >
              <div>
                <div className="list-row__title">{formatHomeworkDate(homework.startDate, language)}</div>
                <div className="muted">
                  {t('homeworks.total')}: {homework.totalCards} · {t('homeworks.notStarted')}: {homework.notStarted} ·{' '}
                  {t('homeworks.inProgress')}: {homework.inProgress} · {t('homeworks.learned')}: {homework.learned}
                </div>
              </div>
              <span className={`pill ${homeworkStatusClass(homework.status)}`}>
                {t(`homeworks.status.${homework.status}`)}
              </span>
            </Link>
          ))
        )}
      </div>
    </div>
  );
}

function formatHistoryDate(date: string, language: 'DE' | 'RU') {
  return new Intl.DateTimeFormat(language === 'DE' ? 'de-DE' : 'ru-RU', {
    day: '2-digit',
    month: '2-digit',
  }).format(new Date(`${date}T00:00:00`));
}

function formatHomeworkDate(date: string, language: 'DE' | 'RU') {
  return new Intl.DateTimeFormat(language === 'DE' ? 'de-DE' : 'ru-RU', {
    day: '2-digit',
    month: '2-digit',
    year: 'numeric',
  }).format(new Date(`${date}T00:00:00`));
}

function statusClass(status: DailyReviewStatus) {
  if (status === 'COMPLETED') {
    return 'pill--learned';
  }
  if (status === 'PARTIAL') {
    return 'pill--active';
  }
  return 'pill--wrong';
}

function homeworkStatusClass(status: Homework['status']) {
  if (status === 'COMPLETED') {
    return 'pill--learned';
  }
  if (status === 'ACTIVE') {
    return 'pill--active';
  }
  return 'pill--pending';
}

function SummaryStat({ label, value }: { label: string; value: number }) {
  return (
    <div>
      <div style={{ fontSize: 28, fontWeight: 700 }}>{value}</div>
      <div className="muted" style={{ fontSize: 13 }}>{label}</div>
    </div>
  );
}
