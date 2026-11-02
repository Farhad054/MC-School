import { cleanup, render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, expect, it } from 'vitest';
import type { OnlineClass } from '../../api/onlineClasses';
import { LessonEntryAction } from './LessonEntryAction';

afterEach(cleanup);

it.each([true, false])('shows an idle classroom and retains its existing host entry link (host=%s)', viewerIsHost => {
  const room = { id: 'existing-class', status: 'LIVE', mediaState: 'IDLE', joinWindowOpen: true,
    viewerIsHost, viewerHasJoined: true } as OnlineClass;
  render(<MemoryRouter><LessonEntryAction room={room} startsAt="2026-10-24T12:00:00Z"
    language="RU" now={Date.parse('2026-10-24T12:05:00Z')} /></MemoryRouter>);
  expect(screen.getByRole('status')).toHaveTextContent('Комната в ожидании входа учителя');
  expect(screen.queryByText(/Урок продолжается|Урок открыт/)).not.toBeInTheDocument();
  const join = screen.queryByRole('link', { name: /Mindcrafti Online/ });
  if (viewerIsHost) expect(join).toHaveAttribute('href', '/online-classes/existing-class');
  else expect(join).not.toBeInTheDocument();
});

it.each(['2026-10-23T12:00:00Z', '2026-11-02T12:00:00Z'])('keeps the teacher entry link before the lesson date and after its calendar week (%s)', now => {
  const room = { id: 'existing-class', status: 'LIVE', mediaState: 'IDLE', joinWindowOpen: true,
    viewerIsHost: true, viewerHasJoined: true } as OnlineClass;
  render(<MemoryRouter><LessonEntryAction room={room} startsAt="2026-10-24T12:00:00Z"
    language="RU" now={Date.parse(now)} /></MemoryRouter>);
  expect(screen.getByRole('link', { name: /Mindcrafti Online/ })).toHaveAttribute('href', '/online-classes/existing-class');
  expect(screen.queryByText(/день урока|Урок завершён/)).not.toBeInTheDocument();
});
