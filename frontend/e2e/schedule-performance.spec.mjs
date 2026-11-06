import { test, expect } from '@playwright/test';
import { mkdir, writeFile } from 'node:fs/promises';
import { join } from 'node:path';

const teacher = { id: '33333333-3333-4333-8333-333333333333', role: 'TEACHER', fullName: 'Schedule QA', preferredLanguage: 'RU' };
const student = { id: '11111111-1111-4111-8111-111111111111', fullName: 'Test pupil', status: 'ACTIVE' };
const group = { id: '22222222-2222-4222-8222-222222222222', name: 'Test group', students: [student] };
function gate() { let release; const promise = new Promise(resolve => { release = resolve; }); return { promise, release }; }

async function setup(page, { refreshGate, optionsGate, homeworkGate }) {
  const requests = [];
  const date = new Intl.DateTimeFormat('en-CA', { timeZone: 'Europe/Berlin' }).format(new Date());
  const startsAt = `${date}T12:00:00Z`;
  const lesson = { eventId: 'local-speed-fixture', bindingKey: 'local-speed-fixture', title: 'Fast saved lesson',
    groupId: group.id, groupName: group.name, participantStudentIds: [student.id],
    startsAt, endsAt: `${date}T13:00:00Z`, meetUrl: null, calendarUrl: null };
  await page.addInitScript(user => {
    localStorage.setItem('language', 'RU'); localStorage.setItem('accessToken', 'fixture-only'); localStorage.setItem('authUser', JSON.stringify(user));
  }, teacher);
  await page.route('**/api/v1/**', async route => {
    const url = new URL(route.request().url()), path = url.pathname;
    requests.push(path + url.search);
    if (path.endsWith('/auth/me')) return route.fulfill({ json: teacher });
    if (path.endsWith('/lessons/groups')) {
      if (url.searchParams.get('refresh') === 'false') return route.fulfill({ json: [lesson] });
      await refreshGate.promise;
      return route.fulfill({ json: [{ ...lesson, title: 'Fresh calendar lesson' }] });
    }
    if (path.endsWith('/students')) { if (optionsGate) await optionsGate.promise; return route.fulfill({ json: [student] }); }
    if (path.endsWith('/groups')) return route.fulfill({ json: [group] });
    if (path.endsWith('/google-calendar/connection')) return route.fulfill({ json: { connected: true, authorizationUrl: null } });
    if (path.endsWith('/homeworks')) { if (homeworkGate) await homeworkGate.promise; return route.fulfill({ json: [] }); }
    if (path.endsWith('/cards/summary')) return route.fulfill({ json: { dueNow: 0, awaitingRepetition: 0 } });
    if (path.includes('/lesson-preparations/')) return route.fulfill({ json: { hasWorkbook: false, hasAnswers: false } });
    return route.fulfill({ json: [] });
  });
  return requests;
}

test('saved lessons appear while Google refresh and optional data are still blocked', async ({ page }, testInfo) => {
  const refreshGate = gate(), optionsGate = gate();
  try {
    const requests = await setup(page, { refreshGate, optionsGate });
    const started = Date.now();
    await page.goto('/teacher/lessons');
    await expect(page.getByRole('link', { name: 'Открыть урок: Fast saved lesson', exact: true })).toBeVisible();
    const firstLessonMs = Date.now() - started;
    await expect(page.getByText('Обновляем расписание…')).toBeVisible();
    expect(requests.filter(path => /homeworks|review-history|cards\/summary/.test(path))).toEqual([]);
    optionsGate.release(); refreshGate.release();
    await expect(page.getByRole('link', { name: 'Открыть урок: Fresh calendar lesson', exact: true })).toBeVisible();
    const report = { firstLessonMs, visibleBeforeGoogleAndOptionsFinished: true, initialStudentDetailRequests: 0 };
    await testInfo.attach('schedule-timing', { body: JSON.stringify(report), contentType: 'application/json' });
    if (process.env.SCHEDULE_QA_OUTPUT_DIR) {
      await mkdir(process.env.SCHEDULE_QA_OUTPUT_DIR, { recursive: true });
      await writeFile(join(process.env.SCHEDULE_QA_OUTPUT_DIR, 'browser-timing.json'), JSON.stringify(report, null, 2));
    }
  } finally { optionsGate.release(); refreshGate.release(); }
});

test('slow pupil homework loads only after opening the group and does not block the timetable', async ({ page }) => {
  const refreshGate = gate(), homeworkGate = gate(); refreshGate.release();
  try {
    const requests = await setup(page, { refreshGate, homeworkGate });
    await page.goto('/teacher/lessons');
    await expect(page.getByRole('link', { name: 'Открыть урок: Fresh calendar lesson', exact: true })).toBeVisible();
    expect(requests.filter(path => path.endsWith('/homeworks'))).toHaveLength(0);
    await page.getByRole('button', { name: 'Настройки урока: Fresh calendar lesson', exact: true }).click();
    await expect(page.getByText('Загружаем сведения об учениках…')).toBeVisible();
    await expect(page.getByRole('link', { name: 'Открыть урок: Fresh calendar lesson', exact: true })).toBeVisible();
    homeworkGate.release();
    await expect(page.getByText('Кратко перед уроком', { exact: true })).toBeVisible();
    expect(requests.filter(path => path.endsWith('/homeworks'))).toHaveLength(1);
  } finally { homeworkGate.release(); refreshGate.release(); }
});
