import { useEffect, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { api } from '../../api/client';
import type { SessionResult } from '../../api/types';
import { useI18n } from '../../i18n/I18nContext';
import { toErrorMessage } from '../../lib/errors';

/** Shown after a session completes: first-try score and the next review date. */
export function ResultPage() {
  const { sessionId = '' } = useParams();
  const { t } = useI18n();
  const navigate = useNavigate();
  const [result, setResult] = useState<SessionResult | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    api.study
      .result(sessionId)
      .then(setResult)
      .catch((e) => setError(toErrorMessage(e, t)));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [sessionId]);

  if (error) {
    return <div className="banner banner--error">{error}</div>;
  }
  if (!result) {
    return <p className="muted">{t('common.loading')}</p>;
  }

  return (
    <div className="stack result">
      <h1>{t('result.title')}</h1>
      <div className="panel">
        <div className="muted">{t('result.correctFirstTry')}</div>
        <div className="result__stat">
          {result.correctFirstTry} / {result.totalCards}
        </div>
      </div>

      <div className="panel">
        <div className="muted">{t('result.nextReview')}</div>
        <div style={{ fontSize: 20, fontWeight: 600, marginTop: 6 }}>
          {result.type === 'PRACTICE'
            ? t('result.practiceNote')
            : result.nextReviewDate
              ? formatDate(result.nextReviewDate)
              : t('result.noNextReview')}
        </div>
      </div>

      <button className="btn btn--block" type="button" onClick={() => navigate('/today')}>
        {t('result.done')}
      </button>
    </div>
  );
}

function formatDate(iso: string): string {
  return new Date(iso).toLocaleDateString();
}
