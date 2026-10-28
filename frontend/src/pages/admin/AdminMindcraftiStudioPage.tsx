import { useEffect, useRef, useState } from 'react';
import studioHtml from './mindcrafti-studio.html?raw';

const STORAGE_KEY = 'mindcrafti.admin-studio.project.v1';

export function AdminMindcraftiStudioPage() {
  const frame = useRef<HTMLIFrameElement>(null);
  const [saveState, setSaveState] = useState('Ожидаем редактор…');

  useEffect(() => {
    function handleMessage(event: MessageEvent) {
      if (event.source !== frame.current?.contentWindow || !event.data || typeof event.data !== 'object') return;
      if (event.data.type === 'mindcrafti-studio:ready') {
        const saved = localStorage.getItem(STORAGE_KEY);
        frame.current?.contentWindow?.postMessage({ type: 'mindcrafti-studio:hydrate', payload: saved }, '*');
        setSaveState(saved ? 'Проект восстановлен' : 'Редактор готов');
      }
      if (event.data.type === 'mindcrafti-studio:save' && typeof event.data.payload === 'string') {
        try {
          localStorage.setItem(STORAGE_KEY, event.data.payload);
          setSaveState('Автосохранение в этом браузере');
        } catch {
          setSaveState('Память браузера заполнена: скачайте проект JSON');
        }
      }
    }
    window.addEventListener('message', handleMessage);
    return () => window.removeEventListener('message', handleMessage);
  }, []);

  return (
    <div className="stories-workspace">
      <iframe
        ref={frame}
        title="Mindcrafti Stories Studio"
        srcDoc={studioHtml}
        sandbox="allow-scripts allow-downloads"
      />
      <div className="stories-workspace__status">
        <span role="status">{saveState}</span>
        <span>Перенос между устройствами: «Проект JSON». Публикация в Instagram не запускается автоматически.</span>
      </div>
    </div>
  );
}
