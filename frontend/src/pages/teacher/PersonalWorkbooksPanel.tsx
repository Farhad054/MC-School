import { useEffect, useRef, useState } from 'react';
import { lessonPreparationApi, type PersonalWorkbooks } from '../../api/lessonPreparation';
import { useI18n } from '../../i18n/I18nContext';

/** The server decides which teacher has access to the personal worksheet pilot. */
export function PersonalWorkbooksPanel({ eventId }: { eventId: string }) {
  const { language } = useI18n();
  const de = language === 'DE';
  const [data, setData] = useState<{ eventId: string; value: PersonalWorkbooks } | null>(null);
  const [busy, setBusy] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const currentEvent = useRef(eventId);
  currentEvent.current = eventId;
  const generation = useRef(0);
  const busyRef = useRef<string | null>(null);

  useEffect(() => {
    generation.current++;
    let cancelled = false;
    setError(null);
    setNotice(null);
    setBusy(null);
    busyRef.current = null;
    void lessonPreparationApi.personalWorkbooks(eventId).then(value => {
      if (!cancelled) setData({ eventId, value });
    }).catch(() => { /* An unavailable pilot must not block normal lesson preparation. */ });
    return () => { cancelled = true; generation.current++; };
  }, [eventId]);

  const value = data?.eventId === eventId ? data.value : null;
  if (!value?.enabled) return null;

  async function upload(studentId: string, file: File, kind: 'workbook' | 'answers' = 'workbook') {
    if (busyRef.current) return;
    const epoch = generation.current;
    const current = () => currentEvent.current === eventId && generation.current === epoch;
    setError(null);
    setNotice(null);
    if (!file.name.toLowerCase().endsWith('.pdf') || !file.size || file.size > 25 * 1024 * 1024) {
      setError(de ? 'Bitte eine PDF-Datei zwischen 1 Byte und 25 MB auswählen.' : 'Выберите непустой PDF размером до 25 МБ.');
      return;
    }
    busyRef.current = `${studentId}:${kind}`;
    setBusy(busyRef.current);
    try {
      if (kind === 'answers') {
        const saved = await lessonPreparationApi.uploadPersonalAnswers(eventId, studentId, file);
        if (!current()) return;
        if (!saved.hasAnswers || saved.studentId !== studentId) throw new Error(de ? 'Die Speicherung wurde nicht bestätigt.' : 'Сервер не подтвердил сохранение решений.');
        setData(previous => previous?.eventId === eventId ? {
          eventId, value: { ...previous.value, students: previous.value.students.map(row => row.studentId === studentId
            ? { ...row, hasAnswers: saved.hasAnswers, answersFilename: saved.answersFilename, answersVersion: saved.version } : row) },
        } : previous);
        setNotice(de ? 'Persönliche Lösungen gespeichert. Nur für die Lehrkraft sichtbar.' : 'Персональные решения сохранены. Они видны только преподавателю.');
        return;
      }
      const saved = await lessonPreparationApi.uploadPersonalWorkbook(eventId, studentId, file);
      if (!current()) return;
      if (!saved.hasWorkbook || saved.studentId !== studentId) throw new Error(de ? 'Die Speicherung wurde nicht bestätigt.' : 'Сервер не подтвердил сохранение листа.');
      setData(current => current?.eventId === eventId ? {
        eventId, value: { ...current.value, students: current.value.students.map(row => row.studentId === studentId ? {
          ...row, ...saved, hasAnswers: saved.hasAnswers ?? row.hasAnswers,
          answersFilename: saved.answersFilename === undefined ? row.answersFilename : saved.answersFilename,
          answersVersion: saved.answersVersion ?? row.answersVersion,
        } : row) },
      } : current);
      setNotice(de ? `Arbeitsblatt für ${saved.studentName} gespeichert.` : `Лист для ${saved.studentName} сохранён.`);
      window.dispatchEvent(new Event('mc-workbook-updated'));
    } catch (failure) {
      if (current()) setError(failure instanceof Error ? failure.message : (de ? 'Upload fehlgeschlagen.' : 'Не удалось загрузить PDF.'));
    } finally {
      if (current()) { busyRef.current = null; setBusy(null); }
    }
  }

  async function open(studentId: string, kind: 'workbook' | 'answers' = 'workbook') {
    const epoch = generation.current;
    setError(null);
    // Open the tab during the click so that browsers do not block the PDF after fetching.
    const tab = window.open('about:blank', '_blank');
    if (tab) tab.opener = null;
    try {
      const url = kind === 'answers' ? await lessonPreparationApi.personalAnswersUrl(eventId, studentId)
        : await lessonPreparationApi.personalWorkbookUrl(eventId, studentId);
      if (currentEvent.current !== eventId || generation.current !== epoch || !tab) {
        URL.revokeObjectURL(url);
        tab?.close();
        if (!tab) throw new Error(de ? 'Bitte Pop-ups für die PDF-Vorschau erlauben.' : 'Разрешите открытие новой вкладки для просмотра PDF.');
        return;
      }
      tab.location.href = url;
      window.setTimeout(() => URL.revokeObjectURL(url), 60000);
    } catch (failure) {
      tab?.close();
      if (currentEvent.current === eventId && generation.current === epoch) setError(failure instanceof Error ? failure.message : (de ? 'PDF konnte nicht geöffnet werden.' : 'Не удалось открыть PDF.'));
    }
  }

  return <section className="workspace-personal" aria-label={de ? 'Persönliche Arbeitshefte' : 'Личные рабочие тетради'}>
    {!value.students.length && <p className="muted">{de ? 'Diesem Unterricht sind noch keine Schüler zugeordnet.' : 'К этому уроку ещё не привязаны ученики.'}</p>}
    <div className="workspace-pupils">
      {value.students.map(student => <section key={student.studentId} className="workspace-pupil-card" aria-label={student.studentName}><h3>{student.studentName}</h3><div className="workspace-pupil-halves">{(['workbook','answers'] as const).map(kind=><div key={kind}><strong>{kind==='workbook' ? (de?'Arbeitsheft':'Рабочая тетрадь') : (de?'Lösungen':'Решения')}</strong><p className="muted">{(kind==='workbook'?student.filename:student.answersFilename) || (de?'Noch keine Datei':'Файл пока не добавлен')}</p><div className="workspace-actions">{(kind==='workbook'?student.hasWorkbook:student.hasAnswers) && <button type="button" className="btn btn--secondary" disabled={busy!==null} onClick={()=>void open(student.studentId,kind)}>{de?'Öffnen':'Открыть'}</button>}<label className="btn btn--secondary">{de?'PDF hinzufügen':'Добавить PDF'}<input type="file" accept="application/pdf,.pdf" hidden disabled={busy!==null} aria-label={`${kind==='workbook'?(de?'Arbeitsblatt für':'Лист для'):(de?'Lösungen für':'Решения для')} ${student.studentName}`} onChange={event=>{const file=event.target.files?.[0];event.target.value='';if(file)void upload(student.studentId,file,kind);}} /></label></div></div>)}</div></section>)}
    </div>
    {notice && <p role="status">{notice}</p>}
    {error && <p role="alert" style={{ color: '#b91c1c' }}>{error}</p>}
  </section>;
}
