import { useEffect, useRef } from 'react';
import { useI18n } from '../../i18n/I18nContext';
import type { KolinContext } from '../../api/kolin';
import type { useKolinChat } from './useKolinChat';
import './kolin-panel.css';

export function KolinPanel({ chat, context }: { chat: ReturnType<typeof useKolinChat>; context: KolinContext }) {
  const { language } = useI18n();
  const de = language === 'DE';
  const log = useRef<HTMLOListElement>(null);
  const nearBottom = useRef(true);
  const input = useRef<HTMLTextAreaElement>(null);
  useEffect(() => {
    if (chat.open && nearBottom.current && log.current) log.current.scrollTop = log.current.scrollHeight;
  }, [chat.messages.length, chat.pending, chat.open]);
  useEffect(() => { if (chat.open) input.current?.focus(); }, [chat.open]);
  const send = () => void chat.send(context);

  return <aside id="kolin-panel" className="answers-panel kolin-panel" aria-label="Kolin">
    <header className="answers-panel__heading">
      <div><strong className="kolin-panel__title">Kolin</strong>
        <p className="kolin-panel__subtitle">{de ? 'KI-Assistent für Lehrkräfte' : 'AI-помощник преподавателя'}</p></div>
      <button type="button" className="answers-panel__close" onClick={chat.close}
        aria-label={de ? 'Kolin schließen' : 'Закрыть Kolin'}>×</button>
    </header>
    <ol ref={log} className="kolin-panel__messages" role="log" aria-label={de ? 'Chat mit Kolin' : 'Переписка с Kolin'}
      onScroll={() => { const element = log.current; if (element) nearBottom.current = element.scrollHeight - element.scrollTop - element.clientHeight < 80; }}>
      {!chat.messages.length && <li className="kolin-panel__welcome">{de
        ? 'Fragen Sie mich zum aktuellen Unterricht oder bitten Sie um eine Aufgabe.'
        : 'Спросите меня о текущем уроке или попросите создать задание.'}</li>}
      {chat.messages.map(message => <li key={message.id} className={`kolin-panel__message kolin-panel__message--${message.role}`}
        data-role={message.role} data-message-type={message.type}>
        <span className="kolin-panel__speaker">{message.role === 'assistant' ? 'Kolin' : de ? 'Sie' : 'Вы'}</span>
        <p>{message.text}</p>
      </li>)}
    </ol>
    {chat.pending && <p className="kolin-panel__status" role="status">{de ? 'Kolin denkt nach…' : 'Kolin думает…'}</p>}
    {chat.availability === 'checking' && <p className="kolin-panel__status" role="status">{de ? 'Kolin wird verbunden…' : 'Подключаем Kolin…'}</p>}
    {chat.availability === 'unconfigured' && <p className="kolin-panel__status" role="status">{de ? 'Kolin ist noch nicht verfügbar.' : 'Kolin пока недоступен.'}</p>}
    {chat.error && <div className="kolin-panel__error" role="alert">
      <p>{de ? 'Die Antwort konnte nicht geladen werden. Bitte erneut versuchen.' : 'Не удалось получить ответ. Повторите отправку.'}</p>
      <button type="button" onClick={() => void chat.retry()} disabled={chat.pending || chat.availability === 'checking'}>
        {de ? 'Erneut senden' : 'Повторить отправку'}
      </button>
    </div>}
    <form className="kolin-panel__form" onSubmit={event => { event.preventDefault(); send(); }}>
      <textarea ref={input} value={chat.draft} onChange={event => chat.setDraft(event.target.value)} rows={2} maxLength={2000}
        aria-label={de ? 'An Kolin schreiben…' : 'Напишите Kolin…'} placeholder={de ? 'An Kolin schreiben…' : 'Напишите Kolin…'}
        disabled={chat.pending} onKeyDown={event => {
          if (event.key === 'Enter' && !event.shiftKey && !event.nativeEvent.isComposing) { event.preventDefault(); send(); }
          event.stopPropagation();
        }} />
      <button type="submit" disabled={chat.pending || chat.availability !== 'ready' || !chat.draft.trim()}>{de ? 'Senden' : 'Отправить'}</button>
    </form>
  </aside>;
}
