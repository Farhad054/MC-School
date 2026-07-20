import { useEffect, useState } from 'react';
import { api } from '../../api/client';
import type { Card } from '../../api/types';
import { useI18n } from '../../i18n/I18nContext';
import { toErrorMessage } from '../../lib/errors';

/** The student's own cards with their learning status (PRD: "all cards with progress"). */
export function MyCardsPage() {
  const { t } = useI18n();
  const [cards, setCards] = useState<Card[] | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    api.study
      .myCards()
      .then(setCards)
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
