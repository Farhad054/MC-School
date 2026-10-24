import { cleanup, render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { onlineClassesApi, type OnlineClass } from '../../api/onlineClasses';
import { I18nProvider } from '../../i18n/I18nContext';
import { UpcomingOnlineClassesPage } from './UpcomingOnlineClassesPage';
vi.mock('../../api/onlineClasses', () => ({ onlineClassesApi: { listUpcoming: vi.fn() } }));
afterEach(cleanup);
describe('teacher room entry', () => {
  it.each([true, false])('keeps the existing room and never suggests restarting an expired occurrence (open=%s)', async open => {
    localStorage.setItem('language', 'RU');
    vi.mocked(onlineClassesApi.listUpcoming).mockResolvedValue([{
      id: 'class-1', eventId: 'event-1', title: 'Математика', viewerIsHost: true,
      joinWindowOpen: open, viewerHasJoined: true, entryState: open ? 'RECONNECT' : 'CLOSED',
      serverNow: '2026-10-24T16:00:00Z', status: 'LIVE', scheduledStartAt: '2026-10-23T10:00:00Z',
    } as OnlineClass]);
    render(<MemoryRouter><I18nProvider><UpcomingOnlineClassesPage /></I18nProvider></MemoryRouter>);
    expect(await screen.findByRole('link', { name: /Математика/ })).toHaveAttribute('href',
      '/online-classes/class-1');
    expect(screen.queryByText('Запустите новую сессию из урока')).not.toBeInTheDocument();
    expect(screen.queryByRole('link', { name: /Mindcrafti Online/ }) !== null).toBe(open);
  });
});
