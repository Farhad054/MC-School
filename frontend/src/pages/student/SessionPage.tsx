import { useCallback, useEffect, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { ApiRequestError, api } from '../../api/client';
import type { Question } from '../../api/types';
import { useI18n } from '../../i18n/I18nContext';
import { toErrorMessage } from '../../lib/errors';

/**
 * The study screen: choosing an option submits immediately and moves on without
 * revealing per-question feedback. Wrong cards still return later in the session.
 */
export function SessionPage() {
  const { sessionId = '' } = useParams();
  const { t } = useI18n();
  const navigate = useNavigate();
  const [question, setQuestion] = useState<Question | null>(null);
  const [selected, setSelected] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const goToResult = useCallback(
    () => navigate(`/session/${sessionId}/result`, { replace: true }),
    [navigate, sessionId],
  );

  const loadQuestion = useCallback(async () => {
    setSelected(null);
    try {
      setQuestion(await api.study.currentQuestion(sessionId));
      setError(null);
    } catch (e) {
      // A finished session has no current question — jump straight to the result.
      if (e instanceof ApiRequestError && e.errorCode === 'SESSION_COMPLETED') {
        goToResult();
        return;
      }
      setError(toErrorMessage(e, t));
    } finally {
      setSubmitting(false);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [sessionId, goToResult]);

  useEffect(() => {
    loadQuestion();
  }, [loadQuestion]);

  async function onAnswer(option: string) {
    if (submitting || !question) {
      return;
    }
    setSelected(option);
    setSubmitting(true);
    try {
      const answer = await api.study.answer(sessionId, question.cardId, option);
      if (answer.sessionCompleted) {
        goToResult();
      } else {
        await loadQuestion();
      }
    } catch (e) {
      setError(toErrorMessage(e, t));
      setSelected(null);
      setSubmitting(false);
    }
  }

  if (error) {
    return <div className="banner banner--error">{error}</div>;
  }
  if (!question) {
    return <p className="muted">{t('common.loading')}</p>;
  }

  const answeredForBar = question.answeredCount;
  const progress = Math.round((answeredForBar / question.totalCards) * 100);

  return (
    <div className="stack">
      <div>
        <div className="progressbar">
          <div className="progressbar__fill" style={{ width: `${progress}%` }} />
        </div>
        <div className="muted center" style={{ fontSize: 14 }}>
          {t('session.progress', { done: answeredForBar, total: question.totalCards })}
        </div>
      </div>

      <div className="question">{question.question}</div>

      <div>
        {question.options.map((option) => (
          <button
            key={option}
            type="button"
            className={`option ${option === selected ? 'option--selected' : ''}`}
            disabled={submitting}
            onClick={() => onAnswer(option)}
          >
            {option}
          </button>
        ))}
      </div>
    </div>
  );
}
