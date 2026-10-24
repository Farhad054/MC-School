import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { useI18n } from '../../i18n/I18nContext';
import { onlineClassesApi, type OnlineClass } from '../../api/onlineClasses';
import { LessonEntryAction } from '../../features/online-classes/LessonEntryAction';
import { useLessonEntryClock } from '../../features/online-classes/useLessonEntryClock';

/**
 * Entry point listing the caller's upcoming and live classes. The backend
 * filters by ownership/binding, so a student only ever receives their own.
 */
export function UpcomingOnlineClassesPage() {
  const { t, language } = useI18n();
  const [classes, setClasses] = useState<OnlineClass[] | null>(null);
  const [failed, setFailed] = useState(false);
  const now = useLessonEntryClock(classes?.[0]?.serverNow);

  useEffect(() => {
    let active = true;
    let pending = false;
    const refresh = async () => {
      if (pending) return;
      pending = true;
      try {
        const loaded = await onlineClassesApi.listUpcoming();
        if (active) { setClasses(loaded); setFailed(false); }
      } catch { if (active) setFailed(true); }
      finally { pending = false; }
    };
    void refresh();
    const timer = window.setInterval(() => void refresh(), 10_000);
    return () => { active = false; window.clearInterval(timer); };
  }, []);

  if (failed) return <p role="alert">{t('onlineClass.unavailable')}</p>;
  if (!classes) return <p>{t('common.loading')}</p>;

  const locale = language === 'DE' ? 'de-DE' : 'ru-RU';

  return (
    <div className="online-classes">
      <h1>{t('onlineClass.upcoming')}</h1>
      {classes.length === 0 ? (
        <p>{t('onlineClass.none')}</p>
      ) : (
        <ul>
          {classes.map((item) => (
            <li key={item.id}>
              <Link to={`/online-classes/${item.id}`}>
                {item.title} - {new Date(item.scheduledStartAt).toLocaleString(locale, { timeZone: 'Europe/Berlin' })}
              </Link>
              <LessonEntryAction room={item} startsAt={item.scheduledStartAt} language={language} now={now} />
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}
