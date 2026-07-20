import { useCallback, useEffect, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { ApiRequestError, api } from '../../api/client';
import type { AnswerResult, Question } from '../../api/types';
import { useI18n } from '../../i18n/I18nContext';
import { toErrorMessage } from '../../lib/errors';

/**
 * The study screen: a question with four options and a progress bar. Answering
 * reveals correctness immediately; a wrong card returns later in the same session.
 */
export function SessionPage() {
  const { sessionId = '' } = useParams();
  const { t } = useI18n();
  const navigate = useNavigate();
  const [question, setQuestion] = useState<Question | null>(null);
  const [selected, setSelected] = useState<string | null>(null);
  const [result, setResult] = useState<AnswerResult | null>(null);
  const [error, setError] = useState<string | null>(null);

  const goToResult = useCallback(
    () => navigate(`/session/${sessionId}/result`, { replace: true }),
    [navigate, sessionId],
  );

  const loadQuestion = useCallback(async () => {
    setSelected(null);
    setResult(null);
    try {
      setQuestion(await api.study.currentQuestion(sessionId));
    } catch (e) {
      // A finished session has no current question — jump straight to the result.
      if (e instanceof ApiRequestError && e.errorCode === 'SESSION_COMPLETED') {
        goToResult();
        return;
      }
      setError(toErrorMessage(e, t));
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [sessionId, goToResult]);

  useEffect(() => {
    loadQuestion();
  }, [loadQuestion]);

  async function onAnswer(option: string) {
    if (result || !question) {
      return;
    }
    setSelected(option);
    try {
      setResult(await api.study.answer(sessionId, question.cardId, option));
    } catch (e) {
      setError(toErrorMessage(e, t));
      setSelected(null);
    }
  }

  function onNext() {
    if (result?.sessionCompleted) {
      goToResult();
    } else {
      loadQuestion();
    }
  }

  if (error) {
    return <div className="banner banner--error">{error}</div>;
  }
  if (!question) {
    return <p className="muted">{t('common.loading')}</p>;
  }

  const answeredForBar = question.answeredCount + (result?.correct ? 1 : 0);
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
            className={`option ${optionClass(option, selected, result)}`}
            disabled={result !== null}
            onClick={() => onAnswer(option)}
          >
            {option}
          </button>
        ))}
      </div>

      {result && (
        <div className="stack">
          <div className={`banner ${result.correct ? 'banner--success' : 'banner--error'}`}>
            {result.correct ? t('session.correct') : `${t('session.wrong')} ${result.correctAnswer}`}
          </div>
          <button className="btn btn--block" type="button" onClick={onNext}>
            {result.sessionCompleted ? t('session.finish') : t('session.next')}
          </button>
        </div>
      )}
    </div>
  );
}

/** Highlights the correct option green and a wrong choice red once answered. */
function optionClass(option: string, selected: string | null, result: AnswerResult | null): string {
  if (!result) {
    return '';
  }
  if (option === result.correctAnswer) {
    return 'option--correct';
  }
  if (option === selected) {
    return 'option--wrong';
  }
  return '';
}
