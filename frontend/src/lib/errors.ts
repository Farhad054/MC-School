import { ApiRequestError } from '../api/client';
import type { TranslationKey } from '../i18n/translations';

/**
 * Turns any thrown value into a user-facing message. Known backend error codes
 * (e.g. NOT_ENOUGH_CARDS) map to a localized string; anything else falls back to
 * a generic message so raw server text is never shown.
 */
export function toErrorMessage(
  error: unknown,
  t: (key: TranslationKey, params?: Record<string, string | number>) => string,
): string {
  if (error instanceof ApiRequestError) {
    const key = `error.${error.errorCode}` as TranslationKey;
    const localized = t(key);
    if (localized !== key) {
      return localized;
    }
  }
  return t('error.generic');
}
