import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { ApiRequestError } from '../../api/client';
import { onlineClassesApi } from '../../api/onlineClasses';
import { useI18n } from '../../i18n/I18nContext';

/**
 * Teacher entry point on the lesson detail page.
 *
 * <p>Materializes the durable class for this calendar occurrence, starts it, and
 * opens the room. Extracted into its own component so `LessonDetailPage` gains
 * one element rather than a block of online-class logic.
 *
 * <p>Renders nothing when the feature is unavailable, so the existing Google
 * Meet action stays the only option during rollout.
 */
export function StartOnlineClassButton({ eventId }: { eventId: string }) {
  const { t, language } = useI18n();
  const navigate = useNavigate();
  const [busy, setBusy] = useState(false);
  const [policyMessage, setPolicyMessage] = useState<string | null>(null);
  const [ended, setEnded] = useState(false);
  const [unavailable, setUnavailable] = useState(false);
  const [failed, setFailed] = useState(false);
  const [failureKind, setFailureKind] = useState<'generic' | 'credentials' | 'unreachable'>('generic');

  if (unavailable) {
    return (
      <p className="online-class-unavailable" role="status">
        {t('onlineClass.unavailable')}
      </p>
    );
  }

  const start = async () => {
    if (busy) return;
    setBusy(true);
    setFailed(false);
    setPolicyMessage(null);
    setFailureKind('generic');
    let classId: string | null = null;
    try {
      const onlineClass = await onlineClassesApi.materializeFromCalendar(eventId);
      classId = onlineClass.id;
      if (onlineClass.status === 'ENDED' || onlineClass.status === 'CANCELLED') {
        setEnded(true);
        setPolicyMessage(language === 'DE' ? 'Der Unterricht ist beendet. Erstellen Sie einen neuen Unterricht für eine weitere Sitzung.'
          : 'Урок завершён. Для нового занятия создайте новый урок.');
        return;
      }
      await onlineClassesApi.start(onlineClass.id);
      navigate(`/online-classes/${onlineClass.id}`);
    } catch (error) {
      // A disabled or misconfigured feature is reported precisely rather than
      // as a generic failure, and never exposes which setting is missing.
      if (error instanceof ApiRequestError && error.status === 409 && /lesson day|has ended|ten minutes|reconnect window/i.test(error.message)) {
        setPolicyMessage(language === 'DE' ? 'Beitritt nur am Unterrichtstag (Berlin), nach dem Öffnen. Ein beendeter Unterricht bleibt geschlossen.'
          : 'Вход возможен только в день урока по Берлину после открытия учителем. Завершённый урок остаётся закрытым.');
      } else if (error instanceof ApiRequestError && error.status === 404) {
        setUnavailable(true);
      } else {
        // Room-start conflicts often wrap a rejected LiveKit RoomService API
        // request. Query a host-only endpoint for an operator-safe diagnosis
        // rather than displaying the provider response or any secret.
        if (error instanceof ApiRequestError && error.status === 409 && classId) {
          try {
            const diagnosis = await onlineClassesApi.mediaDiagnostics(classId);
            if (diagnosis.diagnosticStatus === 'AUTH_REJECTED') {
              setFailureKind('credentials');
            } else if (diagnosis.diagnosticStatus === 'UNREACHABLE') {
              setFailureKind('unreachable');
            }
          } catch {
            // Older backend or temporary API failure: keep a generic message.
          }
        }
        setFailed(true);
      }
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="online-class-start">
      <button type="button" className="btn btn--primary" onClick={() => void start()} disabled={busy || ended}>
        {ended ? (language === 'DE' ? 'Unterricht beendet' : 'Урок завершён') : t('onlineClass.start')}
      </button>
      {policyMessage && <p role="status">{policyMessage}</p>}
      {failed && (
        <p role="alert" className="online-class-error">
          {failureKind === 'credentials'
            ? t('onlineClass.startFailedCredentials')
            : failureKind === 'unreachable'
              ? t('onlineClass.startFailedNetwork')
              : t('onlineClass.startFailed')}
        </p>
      )}
    </div>
  );
}
