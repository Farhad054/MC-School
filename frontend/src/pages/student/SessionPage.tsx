import { useCallback, useEffect, useRef, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { ApiRequestError, api } from '../../api/client';
import type { Question } from '../../api/types';
import { useI18n } from '../../i18n/I18nContext';
import { toErrorMessage } from '../../lib/errors';

/**
 * The study screen: choosing an option submits immediately, briefly shows
 * correct/wrong feedback, then advances automatically. Wrong cards still return
 * later in the session.
 */
export function SessionPage() {
  const { sessionId = '' } = useParams();
  const { t } = useI18n();
  const navigate = useNavigate();
  const [question, setQuestion] = useState<Question | null>(null);
  const [selected, setSelected] = useState<string | null>(null);
  const [feedback, setFeedback] = useState<{
    correct: boolean;
    correctAnswer: string;
    selectedAnswer: string;
  } | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const advanceTimer = useRef<number | null>(null);

  const goToResult = useCallback(
    () => navigate(`/session/${sessionId}/result`, { replace: true }),
    [navigate, sessionId],
  );

  const loadQuestion = useCallback(async () => {
    setSelected(null);
    setFeedback(null);
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

  useEffect(() => () => {
    if (advanceTimer.current !== null) {
      window.clearTimeout(advanceTimer.current);
    }
  }, []);

  async function onAnswer(option: string) {
    if (submitting || feedback || !question) {
      return;
    }
    setSelected(option);
    setSubmitting(true);
    try {
      const answer = await api.study.answer(sessionId, question.cardId, option);
      setFeedback({
        correct: answer.correct,
        correctAnswer: answer.correctAnswer,
        selectedAnswer: option,
      });
      const delay = answer.correct ? 1000 : 1400;
      advanceTimer.current = window.setTimeout(() => {
        advanceTimer.current = null;
        if (answer.sessionCompleted) {
          goToResult();
        } else {
          void loadQuestion();
        }
      }, delay);
    } catch (e) {
      setError(toErrorMessage(e, t));
      setSelected(null);
      setFeedback(null);
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
  const locked = submitting || feedback !== null;

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
            className={optionClassName(option, selected, feedback)}
            disabled={locked}
            onClick={() => onAnswer(option)}
          >
            {option}
          </button>
        ))}
      </div>

      {feedback && (
        <div className={`answer-feedback ${feedback.correct ? 'answer-feedback--correct' : 'answer-feedback--wrong'}`}>
          {feedback.correct ? t('session.feedbackCorrect') : t('session.feedbackWrong')}
        </div>
      )}
    </div>
  );
}

function optionClassName(
  option: string,
  selected: string | null,
  feedback: { correct: boolean; correctAnswer: string; selectedAnswer: string } | null,
) {
  const classes = ['option'];
  if (!feedback && option === selected) {
    classes.push('option--selected');
  }
  if (feedback && option === feedback.correctAnswer) {
    classes.push('option--correct');
  }
  if (feedback && !feedback.correct && option === feedback.selectedAnswer) {
    classes.push('option--wrong');
  }
  return classes.join(' ');
}
