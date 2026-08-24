import { useCallback, useEffect, useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { api } from '../../api/client';
import type { Card, CardSummary, DailyReviewHistoryItem, DailyReviewStatus } from '../../api/types';
import { useI18n } from '../../i18n/I18nContext';
import { toErrorMessage } from '../../lib/errors';
import { CardCreator } from './CardCreator';
import { CardRow } from './CardRow';

/** Minimum cards a student needs before any session can start (mirrors the backend). */
const MIN_CARDS_TO_START = 4;

/** A single student's cards: status summary, add-cards panel, and the editable card list. */
export function StudentDetailPage() {
  const { studentId = '' } = useParams();
  const { language, t } = useI18n();
  const [cards, setCards] = useState<Card[]>([]);
  const [summary, setSummary] = useState<CardSummary | null>(null);
  const [reviewHistory, setReviewHistory] = useState<DailyReviewHistoryItem[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);

  const reload = useCallback(async () => {
    const [cardList, cardSummary, history] = await Promise.all([
      api.cards.listForStudent(studentId),
      api.cards.summaryForStudent(studentId),
      api.students.reviewHistory(studentId),
    ]);
    setCards(cardList);
    setSummary(cardSummary);
    setReviewHistory(history);
    setLoading(false);
  }, [studentId]);

  useEffect(() => {
    reload().catch((e) => setError(toErrorMessage(e, t)));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [reload]);

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

      <h2>{t('cards.add')}</h2>
      <CardCreator studentId={studentId} onChanged={reload} />

      <h2>{t('cards.title')}</h2>
      {loading ? (
        <p className="muted">{t('common.loading')}</p>
      ) : cards.length === 0 ? (
        <p className="muted">{t('cards.empty')}</p>
      ) : (
        cards.map((card) => <CardRow key={card.id} card={card} onChanged={reload} />)
      )}
    </div>
  );
}

function formatHistoryDate(date: string, language: 'DE' | 'RU') {
  return new Intl.DateTimeFormat(language === 'DE' ? 'de-DE' : 'ru-RU', {
    day: '2-digit',
    month: '2-digit',
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

function SummaryStat({ label, value }: { label: string; value: number }) {
  return (
    <div>
      <div style={{ fontSize: 28, fontWeight: 700 }}>{value}</div>
      <div className="muted" style={{ fontSize: 13 }}>{label}</div>
    </div>
  );
}
