import { useCallback, useEffect, useState, type FormEvent } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { api } from '../../api/client';
import type { Card, CardSummary, DailyReviewHistoryItem, DailyReviewStatus, Homework } from '../../api/types';
import { useI18n } from '../../i18n/I18nContext';
import { toErrorMessage } from '../../lib/errors';

/** Minimum cards a student needs before any session can start (mirrors the backend). */
const MIN_CARDS_TO_START = 4;

type ReviewHistoryDisplayStatus = DailyReviewStatus | 'EXPECTED';

/** A single student's cards: status summary, review schedule, homework, and pilot controls. */
export function StudentDetailPage() {
  const { studentId = '' } = useParams();
  const navigate = useNavigate();
  const { language, t } = useI18n();
  const [homeworks, setHomeworks] = useState<Homework[]>([]);
  const [cards, setCards] = useState<Card[]>([]);
  const [newHomeworkDate, setNewHomeworkDate] = useState(() => new Date().toISOString().slice(0, 10));
  const [summary, setSummary] = useState<CardSummary | null>(null);
  const [reviewHistory, setReviewHistory] = useState<DailyReviewHistoryItem[]>([]);
  const [openHistoryDate, setOpenHistoryDate] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [pilotMessage, setPilotMessage] = useState<string | null>(null);
  const [pilotBusy, setPilotBusy] = useState(false);
  const [loading, setLoading] = useState(true);

  const reload = useCallback(async () => {
    const [homeworkList, cardList, cardSummary, history] = await Promise.all([
      api.homeworks.listForStudent(studentId),
      api.cards.listForStudent(studentId),
      api.cards.summaryForStudent(studentId),
      api.students.reviewHistory(studentId),
    ]);
    setHomeworks(homeworkList);
    setCards(cardList);
    setSummary(cardSummary);
    setReviewHistory(history);
    setLoading(false);
  }, [studentId]);

  useEffect(() => {
    reload().catch((e) => setError(toErrorMessage(e, t)));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [reload]);

  const futureSchedule = buildFutureReviewSchedule(cards);

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

  function downloadReviewTable(item: DailyReviewHistoryItem) {
    const rows = [
      [historyText(language, 'Вопрос', 'Frage'), historyText(language, 'Ответ ученика', 'Antwort des Schülers'),
        historyText(language, 'Правильный ответ', 'Richtige Antwort'), historyText(language, 'Результат', 'Ergebnis')],
      ...item.answers.map((answer) => [
        answer.question,
        answer.selectedAnswer ?? historyText(language, 'Ответ не сохранён', 'Antwort nicht gespeichert'),
        answer.correctAnswer,
        answer.correct ? historyText(language, 'Правильно', 'Richtig') : historyText(language, 'Неправильно', 'Falsch'),
      ]),
    ];
    const csv = rows.map((row) => row.map(csvCell).join(';')).join('\r\n');
    const blob = new Blob([`\uFEFF${csv}`], { type: 'text/csv;charset=utf-8' });
    const url = URL.createObjectURL(blob);
    const link = document.createElement('a');
    link.href = url;
    link.download = `review-${item.date}.csv`;
    document.body.appendChild(link);
    link.click();
    link.remove();
    URL.revokeObjectURL(url);
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
            {reviewHistory.map((item) => {
              const displayStatus = resolveHistoryStatus(item);
              const isOpen = openHistoryDate === item.date;
              const correctCount = item.answers.filter((answer) => answer.correct).length;
              const wrongCount = item.answers.filter((answer) => !answer.correct).length;
              return (
                <div key={item.date}>
                  <button
                    type="button"
                    className="history-row"
                    onClick={() => setOpenHistoryDate(isOpen ? null : item.date)}
                    style={{ width: '100%', border: 0, background: 'transparent', color: 'inherit', font: 'inherit', cursor: 'pointer' }}
                  >
                    <span>{formatHistoryDate(item.date, language)}</span>
                    <span>
                      {item.answers.length > 0
                        ? `${historyText(language, 'Правильных ответов', 'Richtige Antworten')}: ${correctCount}; ${historyText(language, 'Неправильных ответов', 'Falsche Antworten')}: ${wrongCount}`
                        : `${item.completedCount}/${item.dueCount}`}
                    </span>
                    <span className={`pill ${historyStatusClass(displayStatus)}`}>
                      {t(`reviewHistory.status.${displayStatus}`)}
                    </span>
                  </button>

                  {isOpen && (
                    <div style={{ padding: '10px 16px 16px' }}>
                      {item.answers.length === 0 ? (
                        <p className="muted" style={{ margin: 0 }}>
                          {historyText(language, 'Подробные ответы для этого повторения не сохранены', 'Detaillierte Antworten für diese Wiederholung wurden nicht gespeichert')}
                        </p>
                      ) : (
                        <>
                          <div className="row" style={{ justifyContent: 'space-between', alignItems: 'center', marginBottom: 12 }}>
                            <div className="row">
                              <span>{historyText(language, 'Всего карточек', 'Karten gesamt')}: {item.answers.length}</span>
                              <span>{historyText(language, 'Правильных ответов', 'Richtige Antworten')}: {correctCount}</span>
                              <span>{historyText(language, 'Неправильных ответов', 'Falsche Antworten')}: {wrongCount}</span>
                            </div>
                            <button className="btn btn--secondary" type="button" onClick={() => downloadReviewTable(item)}>
                              {historyText(language, 'Скачать таблицу', 'Tabelle herunterladen')}
                            </button>
                          </div>
                          <div style={{ overflowX: 'auto' }}>
                            <table style={{ width: '100%', borderCollapse: 'collapse', minWidth: 700 }}>
                              <thead>
                                <tr>
                                  <HistoryHeader>{historyText(language, 'Вопрос', 'Frage')}</HistoryHeader>
                                  <HistoryHeader>{historyText(language, 'Ответ ученика', 'Antwort des Schülers')}</HistoryHeader>
                                  <HistoryHeader>{historyText(language, 'Правильный ответ', 'Richtige Antwort')}</HistoryHeader>
                                  <HistoryHeader>{historyText(language, 'Результат', 'Ergebnis')}</HistoryHeader>
                                </tr>
                              </thead>
                              <tbody>
                                {item.answers.map((answer) => (
                                  <tr key={answer.cardId}>
                                    <HistoryCell>{answer.question}</HistoryCell>
                                    <HistoryCell>{answer.selectedAnswer ?? historyText(language, 'Ответ не сохранён', 'Antwort nicht gespeichert')}</HistoryCell>
                                    <HistoryCell>{answer.correctAnswer}</HistoryCell>
                                    <HistoryCell>{answer.correct ? historyText(language, 'Правильно', 'Richtig') : historyText(language, 'Неправильно', 'Falsch')}</HistoryCell>
                                  </tr>
                                ))}
                              </tbody>
                            </table>
                          </div>
                        </>
                      )}
                    </div>
                  )}
                </div>
              );
            })}
          </div>
        )}
      </div>

      {futureSchedule.length > 0 && (
        <>
          <h2>{t('reviewSchedule.title')}</h2>
          <div className="panel">
            <div className="history-list">
              {futureSchedule.map((day) => (
                <details key={day.date}>
                  <summary className="history-row" style={{ cursor: 'pointer', listStyle: 'none' }}>
                    <span>{formatHistoryDate(day.date, language)}</span>
                    <span>{t('reviewSchedule.cardCount', { count: day.cards.length })}</span>
                    <span className="pill pill--pending">{t('reviewSchedule.expected')}</span>
                  </summary>
                  <div className="stack" style={{ padding: '8px 16px 14px' }}>
                    {day.cards.map((card) => (
                      <div key={card.id} className="list-row" style={{ alignItems: 'flex-start' }}>
                        <div>
                          <div className="list-row__title">{card.question}</div>
                          <div className="muted" style={{ fontSize: 13 }}>
                            {t('reviewSchedule.stage', { stage: card.repetitionNumber })}
                          </div>
                        </div>
                      </div>
                    ))}
                  </div>
                </details>
              ))}
            </div>
          </div>
        </>
      )}

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

function buildFutureReviewSchedule(cards: Card[]) {
  const today = localDateString(new Date());
  const grouped = new Map<string, Card[]>();

  cards
    .filter((card) => card.status === 'ACTIVE' && card.dueDate != null && card.dueDate > today)
    .forEach((card) => {
      const date = card.dueDate as string;
      const existing = grouped.get(date) ?? [];
      existing.push(card);
      grouped.set(date, existing);
    });

  return Array.from(grouped.entries())
    .sort(([a], [b]) => a.localeCompare(b))
    .map(([date, scheduledCards]) => ({
      date,
      cards: scheduledCards.sort((a, b) => a.question.localeCompare(b.question)),
    }));
}

function resolveHistoryStatus(item: DailyReviewHistoryItem): ReviewHistoryDisplayStatus {
  const today = localDateString(new Date());

  if (item.date === today && item.dueCount > 0 && item.completedCount === 0) {
    return 'EXPECTED';
  }

  return item.status;
}

function localDateString(date: Date) {
  const year = date.getFullYear();
  const month = String(date.getMonth() + 1).padStart(2, '0');
  const day = String(date.getDate()).padStart(2, '0');
  return `${year}-${month}-${day}`;
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

function historyStatusClass(status: ReviewHistoryDisplayStatus) {
  if (status === 'COMPLETED') {
    return 'pill--learned';
  }
  if (status === 'PARTIAL') {
    return 'pill--active';
  }
  if (status === 'EXPECTED') {
    return 'pill--pending';
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

function HistoryHeader({ children }: { children: string }) {
  return (
    <th style={{ textAlign: 'left', padding: '10px 12px', borderBottom: '1px solid rgba(128,128,128,.3)' }}>
      {children}
    </th>
  );
}

function HistoryCell({ children }: { children: string }) {
  return (
    <td style={{ padding: '10px 12px', borderBottom: '1px solid rgba(128,128,128,.18)', verticalAlign: 'top' }}>
      {children}
    </td>
  );
}

function csvCell(value: string): string {
  return `"${value.replace(/"/g, '""')}"`;
}

function historyText(language: 'DE' | 'RU', ru: string, de: string) {
  return language === 'DE' ? de : ru;
}
