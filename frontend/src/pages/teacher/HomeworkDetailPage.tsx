import { useCallback, useEffect, useMemo, useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { api } from '../../api/client';
import type { Card, Homework } from '../../api/types';
import { useI18n } from '../../i18n/I18nContext';
import { toErrorMessage } from '../../lib/errors';
import { CardCreator } from './CardCreator';
import { CardRow } from './CardRow';

/** One homework folder: its stats, cards, and card-management tools. */
export function HomeworkDetailPage() {
  const { studentId = '', homeworkId = '' } = useParams();
  const { language, t } = useI18n();
  const [homeworks, setHomeworks] = useState<Homework[]>([]);
  const [cards, setCards] = useState<Card[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const homework = useMemo(
    () => homeworks.find((item) => item.id === homeworkId) ?? null,
    [homeworks, homeworkId],
  );

  const reload = useCallback(async () => {
    const [homeworkList, cardList] = await Promise.all([
      api.homeworks.listForStudent(studentId),
      api.cards.listForHomework(homeworkId),
    ]);
    setHomeworks(homeworkList);
    setCards(cardList);
    setLoading(false);
  }, [studentId, homeworkId]);

  useEffect(() => {
    reload().catch((e) => {
      setError(toErrorMessage(e, t));
      setLoading(false);
    });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [reload]);

  return (
    <div>
      <p>
        <Link to={`/students/${studentId}`} className="muted">
          ← {t('homeworks.backToStudent')}
        </Link>
      </p>

      {error && <div className="banner banner--error">{error}</div>}

      <h1>{homework ? formatHomeworkDate(homework.startDate, language) : t('homeworks.title')}</h1>

      {homework && (
        <div className="panel row center">
          <span className={`pill ${homeworkStatusClass(homework.status)}`}>
            {t(`homeworks.status.${homework.status}`)}
          </span>
          <SummaryStat label={t('homeworks.total')} value={homework.totalCards} />
          <SummaryStat label={t('homeworks.notStarted')} value={homework.notStarted} />
          <SummaryStat label={t('homeworks.inProgress')} value={homework.inProgress} />
          <SummaryStat label={t('homeworks.learned')} value={homework.learned} />
        </div>
      )}

      <h2>{t('cards.add')}</h2>
      <CardCreator homeworkId={homeworkId} onChanged={reload} />

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

function formatHomeworkDate(date: string, language: 'DE' | 'RU') {
  return new Intl.DateTimeFormat(language === 'DE' ? 'de-DE' : 'ru-RU', {
    day: '2-digit',
    month: '2-digit',
    year: 'numeric',
  }).format(new Date(`${date}T00:00:00`));
}

function SummaryStat({ label, value }: { label: string; value: number }) {
  return (
    <div>
      <div style={{ fontSize: 28, fontWeight: 700 }}>{value}</div>
      <div className="muted" style={{ fontSize: 13 }}>{label}</div>
    </div>
  );
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
