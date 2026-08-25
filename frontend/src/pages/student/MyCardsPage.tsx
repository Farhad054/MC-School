import { useEffect, useState } from 'react';
import { api } from '../../api/client';
import type { Card, Homework } from '../../api/types';
import { useI18n } from '../../i18n/I18nContext';
import { toErrorMessage } from '../../lib/errors';

/** The student's own cards with their learning status (PRD: "all cards with progress"). */
export function MyCardsPage() {
  const { t } = useI18n();
  const [cards, setCards] = useState<Card[] | null>(null);
  const [homeworks, setHomeworks] = useState<Homework[]>([]);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    Promise.all([api.study.myCards(), api.study.homeworks()])
      .then(([cardPayload, homeworkPayload]) => {
        setCards(cardPayload);
        setHomeworks(homeworkPayload);
      })
      .catch((e) => setError(toErrorMessage(e, t)));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  if (error) {
    return <div className="banner banner--error">{error}</div>;
  }
  if (!cards) {
    return <p className="muted">{t('common.loading')}</p>;
  }

  return (
    <div>
      <h1>{t('nav.myCards')}</h1>
      {homeworks.length > 0 && (
        <>
          <h2>{t('homeworks.title')}</h2>
          <div className="panel stack">
            {homeworks.map((homework) => (
              <div key={homework.id} className="list-row">
                <div className="list-row__title">{homework.startDate}</div>
                <div className="muted">
                  {t('homeworks.total')}: {homework.totalCards} · {t('homeworks.notStarted')}: {homework.notStarted} ·{' '}
                  {t('homeworks.inProgress')}: {homework.inProgress} · {t('homeworks.learned')}: {homework.learned}
                </div>
              </div>
            ))}
          </div>
        </>
      )}
      {cards.length === 0 ? (
        <p className="muted">{t('cards.empty')}</p>
      ) : (
        cards.map((card) => (
          <div key={card.id} className="list-row">
            <div className="list-row__title">{card.question}</div>
            <span className={`pill ${card.status === 'LEARNED' ? 'pill--learned' : 'pill--active'}`}>
              {t(`cards.status.${card.status}`)}
            </span>
          </div>
        ))
      )}
    </div>
  );
}
