import { useState, type FormEvent } from 'react';
import { api } from '../../api/client';
import type { Card } from '../../api/types';
import { useI18n } from '../../i18n/I18nContext';
import { toErrorMessage } from '../../lib/errors';

/** One card in the teacher's list, with inline edit and delete. */
export function CardRow({ card, onChanged }: { card: Card; onChanged: () => void }) {
  const { t } = useI18n();
  const [editing, setEditing] = useState(false);
  const [question, setQuestion] = useState(card.question);
  const [answer, setAnswer] = useState(card.correctAnswer);
  const [error, setError] = useState<string | null>(null);

  async function onSave(event: FormEvent) {
    event.preventDefault();
    setError(null);
    try {
      await api.cards.update(card.id, question.trim(), answer.trim());
      setEditing(false);
      onChanged();
    } catch (e) {
      setError(toErrorMessage(e, t));
    }
  }

  async function onDelete() {
    if (!window.confirm(t('cards.deleteConfirm'))) {
      return;
    }
    setError(null);
    try {
      await api.cards.remove(card.id);
      onChanged();
    } catch (e) {
      setError(toErrorMessage(e, t));
    }
  }

  if (editing) {
    return (
      <form className="panel" onSubmit={onSave}>
        {error && <div className="banner banner--error">{error}</div>}
        <label className="field">
          <span className="field__label">{t('cards.question')}</span>
          <input className="input" value={question} onChange={(e) => setQuestion(e.target.value)} required />
        </label>
        <label className="field">
          <span className="field__label">{t('cards.answer')}</span>
          <input className="input" value={answer} onChange={(e) => setAnswer(e.target.value)} required />
        </label>
        <div className="row">
          <button className="btn" type="submit">{t('common.save')}</button>
          <button className="btn btn--ghost" type="button" onClick={() => setEditing(false)}>
            {t('common.cancel')}
          </button>
        </div>
      </form>
    );
  }

  return (
    <div className="list-row">
      <div>
        <div className="list-row__title">{card.question}</div>
        <div className="muted">{card.correctAnswer}</div>
      </div>
      <div className="row" style={{ alignItems: 'center', flex: '0 0 auto' }}>
        <span className={`pill ${card.status === 'LEARNED' ? 'pill--learned' : 'pill--active'}`}>
          {t(`cards.status.${card.status}`)}
        </span>
        <button className="btn btn--ghost" type="button" onClick={() => setEditing(true)}>
          {t('common.edit')}
        </button>
        <button className="btn btn--danger" type="button" onClick={onDelete}>
          {t('common.delete')}
        </button>
      </div>
      {error && <div className="banner banner--error">{error}</div>}
    </div>
  );
}
