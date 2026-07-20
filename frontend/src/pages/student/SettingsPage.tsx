import { useState } from 'react';
import { api } from '../../api/client';
import type { Language } from '../../api/types';
import { useAuth } from '../../auth/AuthContext';
import { useI18n } from '../../i18n/I18nContext';
import { toErrorMessage } from '../../lib/errors';

/** Student settings: choose the interface language (PRD settings screen). */
export function SettingsPage() {
  const { t, language, setLanguage } = useI18n();
  const { user, setUser } = useAuth();
  const [saved, setSaved] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const languages: Language[] = ['RU', 'DE'];

  async function choose(next: Language) {
    setError(null);
    setSaved(false);
    // Update the UI immediately, then persist to the backend.
    setLanguage(next);
    try {
      const updated = await api.users.updateLanguage(next);
      if (user) {
        setUser(updated);
      }
      setSaved(true);
    } catch (e) {
      setError(toErrorMessage(e, t));
    }
  }

  return (
    <div className="stack">
      <h1>{t('settings.title')}</h1>
      {error && <div className="banner banner--error">{error}</div>}
      {saved && <div className="banner banner--success">{t('settings.saved')}</div>}

      <div className="panel">
        <div className="field__label">{t('settings.language')}</div>
        <div className="stack">
          {languages.map((lang) => (
            <button
              key={lang}
              type="button"
              className={`btn btn--block ${language === lang ? '' : 'btn--secondary'}`}
              onClick={() => choose(lang)}
            >
              {t(`lang.${lang}`)}
            </button>
          ))}
        </div>
      </div>
    </div>
  );
}
