import { createContext, useCallback, useContext, useMemo, useState, type ReactNode } from 'react';
import type { Language } from '../api/types';
import { translations, type TranslationKey } from './translations';

interface I18nValue {
  language: Language;
  setLanguage: (language: Language) => void;
  /** Translate a key, optionally interpolating {placeholders}. */
  t: (key: TranslationKey, params?: Record<string, string | number>) => string;
}

const I18nContext = createContext<I18nValue | undefined>(undefined);

const STORAGE_KEY = 'language';

function initialLanguage(): Language {
  const stored = localStorage.getItem(STORAGE_KEY);
  return stored === 'DE' || stored === 'RU' ? stored : 'RU';
}

export function I18nProvider({ children }: { children: ReactNode }) {
  const [language, setLanguageState] = useState<Language>(initialLanguage);

  const setLanguage = useCallback((next: Language) => {
    localStorage.setItem(STORAGE_KEY, next);
    setLanguageState(next);
  }, []);

  const t = useCallback(
    (key: TranslationKey, params?: Record<string, string | number>) => {
      let text = translations[language][key] ?? key;
      if (params) {
        for (const [name, value] of Object.entries(params)) {
          text = text.replace(`{${name}}`, String(value));
        }
      }
      return text;
    },
    [language],
  );

  const value = useMemo(() => ({ language, setLanguage, t }), [language, setLanguage, t]);
  return <I18nContext.Provider value={value}>{children}</I18nContext.Provider>;
}

export function useI18n(): I18nValue {
  const ctx = useContext(I18nContext);
  if (!ctx) {
    throw new Error('useI18n must be used within I18nProvider');
  }
  return ctx;
}
