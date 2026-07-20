import type { Language } from '../api/types';
import { useI18n } from '../i18n/I18nContext';

/** Small DE/RU switcher. On login/activation it only changes the UI language locally. */
export function LanguageToggle() {
  const { language, setLanguage, t } = useI18n();
  const languages: Language[] = ['RU', 'DE'];

  return (
    <div className="row" style={{ justifyContent: 'center', gap: 6 }}>
      {languages.map((lang) => (
        <button
          key={lang}
          type="button"
          className={`btn ${language === lang ? 'btn--secondary' : 'btn--ghost'}`}
          style={{ flex: '0 0 auto', padding: '6px 12px' }}
          onClick={() => setLanguage(lang)}
        >
          {t(`lang.${lang}`)}
        </button>
      ))}
    </div>
  );
}
