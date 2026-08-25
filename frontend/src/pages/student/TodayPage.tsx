import { useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { api } from '../../api/client';
import type { Homework, SessionType, Today } from '../../api/types';
import { useI18n } from '../../i18n/I18nContext';
import { toErrorMessage } from '../../lib/errors';

/** Student home ("today's tasks"): start the mandatory session, practice, or resume. */
export function TodayPage() {
  const { t } = useI18n();
  const navigate = useNavigate();
  const [today, setToday] = useState<Today | null>(null);
  const [homeworks, setHomeworks] = useState<Homework[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    Promise.all([api.study.today(), api.study.homeworks()])
      .then(([todayPayload, homeworkPayload]) => {
        setToday(todayPayload);
        setHomeworks(homeworkPayload);
      })
      .catch((e) => setError(toErrorMessage(e, t)));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  async function start(type: SessionType) {
    setError(null);
    setBusy(true);
    try {
      const session = await api.study.startSession(type);
      navigate(`/session/${session.id}`);
    } catch (e) {
      setError(toErrorMessage(e, t));
      setBusy(false);
    }
  }

  if (!today) {
    return <p className="muted">{error ?? t('common.loading')}</p>;
  }

  const notEnoughCards = today.totalCards < today.minCardsToStart;

  return (
    <div className="stack">
      <h1>{t('today.title')}</h1>
      {error && <div className="banner banner--error">{error}</div>}

      <div className="panel center">
        <div className="result__stat">{today.dueCardCount}</div>
        <div className="muted">{t('today.due')}</div>
      </div>

      {today.inProgressSessionId && (
        <button
          className="btn btn--block"
          type="button"
          onClick={() => navigate(`/session/${today.inProgressSessionId}`)}
        >
          {t('today.resume')}
        </button>
      )}

      {notEnoughCards ? (
        <div className="banner banner--info">
          {t('today.needMoreCards', { min: today.minCardsToStart })}
        </div>
      ) : (
        <>
          {today.canStartScheduled ? (
            <button className="btn btn--block" type="button" disabled={busy} onClick={() => start('SCHEDULED')}>
              {t('today.start')}
            </button>
          ) : (
            !today.inProgressSessionId && <div className="banner banner--success">{t('today.nothingDue')}</div>
          )}
          {today.canPractice && (
            <button
              className="btn btn--secondary btn--block"
              type="button"
              disabled={busy}
              onClick={() => start('PRACTICE')}
            >
              {t('today.practice')}
            </button>
          )}
        </>
      )}

      <p className="muted center">
        {t('today.learned')}: {today.learnedCount}
      </p>

      {homeworks.length > 0 && (
        <>
          <h2>{t('homeworks.title')}</h2>
          <div className="panel stack">
            {homeworks.map((homework) => (
              <div key={homework.id} className="list-row">
                <div>
                  <div className="list-row__title">{homework.startDate}</div>
                  <div className="muted">
                    {t('homeworks.total')}: {homework.totalCards} · {t('homeworks.notStarted')}: {homework.notStarted} ·{' '}
                    {t('homeworks.inProgress')}: {homework.inProgress} · {t('homeworks.learned')}: {homework.learned}
                  </div>
                </div>
              </div>
            ))}
          </div>
        </>
      )}
    </div>
  );
}
