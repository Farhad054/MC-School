import { test, expect } from '@playwright/test';
import { mkdir } from 'node:fs/promises';
import { resolve, join } from 'node:path';
import { randomUUID } from 'node:crypto';

const TEACHER = '33333333-3333-4333-8333-333333333333';
const OTHER_TEACHER = '44444444-4444-4444-8444-444444444444';
const SOPHIA = '11111111-1111-4111-8111-111111111111';
const MELISSA = '22222222-2222-4222-8222-222222222222';
const SECOND_CLASS = 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb';
const firstReply = '1) 1/4 + 1/4\n2) 1/3 + 1/6\n3) 2/5 + 1/5';
const nextReply = 'Второе сложнее: 5/12 + 7/18.';
const panel = page => page.getByRole('complementary', { name: 'Kolin', exact: true });
const input = page => panel(page).getByRole('textbox', { name: 'Напишите Kolin…', exact: true });
const toggle = page => page.getByRole('button', { name: 'Kolin', exact: true });
const separator = page => page.getByRole('separator', { name: 'Изменить ширину Kolin', exact: true });
function gate() { let release; const promise = new Promise(resolve => { release = resolve; }); return { promise, release }; }

async function setup(page, role = 'teacher') {
  const state = { teacherId: TEACHER, posts: [], annotationWrites: [], requests: [], errors: [], failOnce: false, failStatusOnce: false, responseGate: null, configured: true };
  const documents = new Map();
  page.on('pageerror', error => state.errors.push(error.message));
  await page.route('**/api/v1/**', async route => {
    const request = route.request(), url = new URL(request.url()), path = url.pathname, method = request.method();
    state.requests.push({ path, method });
    if (path.endsWith('/kolin/status')) {
      if (state.failStatusOnce) { state.failStatusOnce = false; return route.fulfill({ status: 503, json: { message: 'Temporary test error' } }); }
      return route.fulfill({ json: { configured: state.configured } });
    }
    if (path.endsWith('/kolin/messages')) {
      const body = request.postDataJSON();
      state.posts.push({ path, body, contentType: request.headers()['content-type'] });
      if (state.responseGate) await state.responseGate.promise;
      if (state.failOnce) { state.failOnce = false; return route.fulfill({ status: 503, json: { errorCode: 'KOLIN_UNAVAILABLE', message: 'Kolin временно недоступен. Повторите отправку.' } }); }
      return route.fulfill({ json: { type: 'text', text: state.posts.length === 1 ? firstReply : nextReply } });
    }
    if (path.endsWith('/participants') || path.endsWith('/attendance')) return route.fulfill({ json: [
      { userId: state.teacherId, displayName: 'Учитель', classRole: 'HOST', admissionState: 'ADMITTED', connected: true },
      { userId: SOPHIA, displayName: 'София', classRole: 'STUDENT', admissionState: 'ADMITTED', connected: true },
      { userId: MELISSA, displayName: 'Мелисса', classRole: 'STUDENT', admissionState: 'ADMITTED', connected: true },
    ] });
    if (path.endsWith('/personal-workbooks')) return route.fulfill({ json: { enabled: false, students: [] } });
    if (path.endsWith('/answers/metadata')) return route.fulfill({ json: { hasAnswers: false, answersFilename: null, version: 0 } });
    if (path.endsWith('/document/version')) return route.fulfill({ json: { version: 0 } });
    if (path.endsWith('/document')) return route.fulfill({ status: 404, json: { message: 'No workbook in this fixture' } });
    if (path.endsWith('/annotations/documents')) {
      if (method === 'GET') return route.fulfill({ json: [...documents.values()] });
      const targetType = url.searchParams.get('targetType'), targetId = url.searchParams.get('targetId'), pageIndex = Number(url.searchParams.get('pageIndex'));
      let document = [...documents.values()].find(document => document.targetType === targetType && document.targetId === targetId && document.pageIndex === pageIndex);
      if (!document) {
        document = { id: randomUUID(), targetType, targetId, pageIndex, revision: 0, sourceWidth: null, sourceHeight: null, snapshotSavedAt: null };
        documents.set(document.id, document);
      }
      return route.fulfill({ json: document });
    }
    if (path.includes('/annotations/documents/') && path.endsWith('/operations')) {
      if (method === 'GET') return route.fulfill({ json: state.annotationWrites.filter(row => row.path === path).map(row => row.operation) });
      const operation = { ...request.postDataJSON(), id: randomUUID(), sequence: state.annotationWrites.length + 1,
        actorId: state.teacherId, layerOwnerId: state.teacherId, createdAt: new Date().toISOString() };
      state.annotationWrites.push({ path, operation }); return route.fulfill({ json: operation });
    }
    if (path.endsWith('/messages')) return route.fulfill({ json: { messages: [], nextCursor: null, hasMore: false } });
    if (path.endsWith('/join-requests') || path.endsWith('/transcript') || path.endsWith('/recordings')) return route.fulfill({ json: [] });
    if (path.endsWith('/audio-focus')) return route.fulfill({ json: { version: 1, classId: path.split('/')[4], teacherId: state.teacherId, studentId: null, announceAll: false } });
    if (path.endsWith('/homework')) return route.fulfill({ json: { text: '', hasWorkbook: false, workbookFilename: null } });
    return route.fulfill({ status: 404, json: { message: 'Unmocked test endpoint: ' + path } });
  });
  await page.goto(`/e2e/kolin-room.html?role=${role}`);
  await expect(page.locator('.whiteboard__page:visible').first()).toBeVisible();
  await expect.poll(async () => (await page.locator('.whiteboard__page:visible').first().boundingBox())?.height ?? 0).toBeGreaterThan(100);
  return state;
}

async function openKolin(page) {
  await toggle(page).click();
  await expect(panel(page)).toBeVisible();
  await expect(input(page)).toBeEnabled();
}

async function send(page, state, text) {
  const count = state.posts.length;
  await input(page).fill(text);
  await input(page).press('Enter');
  await expect.poll(() => state.posts.length).toBe(count + 1);
  await expect(panel(page).getByText(count === 0 ? firstReply : nextReply, { exact: true }).last()).toBeVisible();
}

function assertCompact(body) {
  expect(Object.keys(body).sort()).toEqual(['context', 'history', 'text']);
  expect(body.history.length).toBeLessThanOrEqual(8);
  expect(body.history.every(message => ['user', 'assistant'].includes(message.role) && typeof message.text === 'string')).toBe(true);
  expect(Object.keys(body.context).every(key => ['targetType', 'studentId', 'currentWorkbookPage'].includes(key))).toBe(true);
  expect(JSON.stringify(body)).not.toMatch(/input_file|input_image|file_data|base64|data:application|transcript|workbookPdf|answersPdf|api[_-]?key|sk-proj|schoolPrompt/i);
  expect(JSON.stringify(body).length).toBeLessThan(20000);
}

async function bounds(page) {
  return { main: await page.locator('.online-class-room__stage > .online-class-room__main').boundingBox(), panel: await panel(page).boundingBox() };
}

async function assertNoOverlap(page) {
  const { main, panel: pane } = await bounds(page);
  expect(main.width).toBeGreaterThan(0); expect(main.height).toBeGreaterThan(0);
  expect(pane.x).toBeGreaterThanOrEqual(0); expect(pane.y).toBeGreaterThanOrEqual(0);
  expect(pane.x + pane.width).toBeLessThanOrEqual(page.viewportSize().width + 1);
  const overlapWidth = Math.max(0, Math.min(main.x + main.width, pane.x + pane.width) - Math.max(main.x, pane.x));
  const overlapHeight = Math.max(0, Math.min(main.y + main.height, pane.y + pane.height) - Math.max(main.y, pane.y));
  expect(overlapWidth * overlapHeight).toBeLessThan(2);
  expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(page.viewportSize().width);
}

for (const viewport of [{ width: 1280, height: 900 }, { width: 820, height: 1180 }, { width: 390, height: 844 }]) {
  test(`Kolin occupies a resizable right pane without covering the board at ${viewport.width}px`, async ({ page }, testInfo) => {
    await page.setViewportSize(viewport); const state = await setup(page);
    const all = page.getByRole('button', { name: 'Все ученики (2×2)', exact: true });
    await expect(all).toBeVisible();
    expect(await all.evaluate(node => node.nextElementSibling?.textContent?.trim())).toBe('Kolin');
    const mainBefore = await page.locator('.online-class-room__stage > .online-class-room__main').boundingBox();
    await openKolin(page); await assertNoOverlap(page);
    await expect(panel(page).getByText('AI-помощник преподавателя', { exact: true })).toBeVisible();
    if (viewport.width > 600) {
      const before = await bounds(page);
      expect(before.panel.x).toBeGreaterThanOrEqual(before.main.x + before.main.width - 1);
      expect(before.main.width).toBeLessThan(mainBefore.width - 100);
      const grip = await separator(page).boundingBox();
      await page.mouse.move(grip.x + grip.width / 2, grip.y + grip.height / 2);
      await page.mouse.down(); await page.mouse.move(grip.x - 90, grip.y + grip.height / 2, { steps: 12 }); await page.mouse.up();
      await expect.poll(async () => (await panel(page).boundingBox()).width).toBeGreaterThan(before.panel.width + 60);
      const dragged = await bounds(page); expect(dragged.main.width).toBeLessThan(before.main.width - 60);
      await separator(page).focus(); await separator(page).press('ArrowRight');
      await expect.poll(async () => (await panel(page).boundingBox()).width).toBeLessThan(dragged.panel.width);
      await assertNoOverlap(page);
      const chosen = (await panel(page).boundingBox()).width;
      await panel(page).getByRole('button', { name: 'Закрыть Kolin', exact: true }).click(); await expect(panel(page)).not.toBeVisible();
      await openKolin(page); await expect.poll(async () => (await panel(page).boundingBox()).width).toBeCloseTo(chosen, 0);
    }
    await send(page, state, 'Дай 3 задания на сложение дробей');
    const log = panel(page).getByRole('log', { name: 'Переписка с Kolin' });
    await expect.poll(async () => (await log.boundingBox()).height).toBeGreaterThanOrEqual(64);
    const visibleReplyHeight = await log.evaluate(element => {
      const logBox = element.getBoundingClientRect();
      const replyBox = element.querySelector('[data-role="assistant"]')?.getBoundingClientRect();
      return replyBox ? Math.max(0, Math.min(logBox.bottom, replyBox.bottom) - Math.max(logBox.top, replyBox.top)) : 0;
    });
    expect(visibleReplyHeight).toBeGreaterThanOrEqual(40);
    const videos = page.locator('.online-class-room__videos-toggle');
    await videos.click(); await expect(videos).toHaveAttribute('aria-expanded', 'true');
    const drawer = page.locator('#online-class-videos');
    await expect(drawer).toHaveCSS('transform', 'matrix(1, 0, 0, 1, 0, 0)');
    await expect(drawer.locator('.camera-card')).toHaveCount(3);
    const withCameras = await bounds(page), cameraBox = await drawer.boundingBox();
    const cameraOverlapWidth = Math.max(0, Math.min(cameraBox.x + cameraBox.width, withCameras.panel.x + withCameras.panel.width) - Math.max(cameraBox.x, withCameras.panel.x));
    const cameraOverlapHeight = Math.max(0, Math.min(cameraBox.y + cameraBox.height, withCameras.panel.y + withCameras.panel.height) - Math.max(cameraBox.y, withCameras.panel.y));
    expect(cameraOverlapWidth * cameraOverlapHeight).toBeLessThan(2);
    expect(cameraBox.x).toBeGreaterThanOrEqual(withCameras.main.x);
    expect(cameraBox.x + cameraBox.width).toBeLessThanOrEqual(withCameras.main.x + withCameras.main.width + 1);
    expect(await input(page).evaluate(element => {
      const rect = element.getBoundingClientRect();
      return document.elementFromPoint(rect.x + rect.width / 2, rect.y + rect.height / 2) === element;
    })).toBe(true);
    const dir = process.env.KOLIN_ARTIFACT_DIR ? resolve(process.env.KOLIN_ARTIFACT_DIR) : testInfo.outputPath('screenshots');
    await mkdir(dir, { recursive: true }); await page.screenshot({ path: join(dir, `kolin-${viewport.width}.png`), fullPage: false });
    expect(state.errors).toEqual([]);
  });
}

test('teacher can follow up, switch pupils, reopen and use bounded text-only context', async ({ page }) => {
  const state = await setup(page); await openKolin(page);
  await page.getByRole('button', { name: 'Доска ученика: София', exact: true }).click();
  await send(page, state, 'Дай 3 задания на сложение дробей');
  expect(state.posts[0].body.context).toMatchObject({ targetType: 'student', studentId: SOPHIA });
  await panel(page).getByRole('button', { name: 'Закрыть Kolin', exact: true }).click(); await openKolin(page);
  await expect(panel(page).getByText(firstReply, { exact: true })).toBeVisible();
  await page.getByRole('button', { name: 'Доска ученика: Мелисса', exact: true }).click();
  await send(page, state, 'Сделай второе сложнее');
  expect(state.posts[1].body.history).toEqual([{ role: 'user', text: 'Дай 3 задания на сложение дробей' }, { role: 'assistant', text: firstReply }]);
  expect(state.posts[1].body.context).toMatchObject({ targetType: 'student', studentId: MELISSA });
  for (let index = 0; index < 5; index++) await send(page, state, `Ещё вариант ${index + 1}`);
  expect(state.posts.at(-1).body.history).toHaveLength(8);
  expect(state.posts.at(-1).body.history.map(message => message.text)).not.toContain('Дай 3 задания на сложение дробей');
  for (const post of state.posts) { assertCompact(post.body); expect(post.contentType).toContain('application/json'); }
  expect(state.requests.filter(request => request.method === 'POST' && /\/messages$/.test(request.path) && !request.path.includes('/kolin/'))).toEqual([]);
  expect(state.errors).toEqual([]);
});

test('Enter sends, Shift+Enter adds a line and a failed request can be retried once', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  const state = await setup(page); await openKolin(page);
  await input(page).fill('Объясни проще'); await input(page).press('Shift+Enter'); await input(page).pressSequentially('На примере дробей');
  await expect(input(page)).toHaveValue('Объясни проще\nНа примере дробей'); expect(state.posts).toHaveLength(0);
  state.failOnce = true; await input(page).press('Enter');
  const retry = panel(page).getByRole('button', { name: 'Повторить отправку', exact: true }); await expect(retry).toBeVisible();
  await expect.poll(async () => (await panel(page).getByRole('log').boundingBox()).height).toBeGreaterThanOrEqual(64);
  const errorPanel = await panel(page).boundingBox(), retryBox = await retry.boundingBox(), composer = await input(page).boundingBox();
  expect(retryBox.y + retryBox.height).toBeLessThanOrEqual(errorPanel.y + errorPanel.height);
  expect(composer.y + composer.height).toBeLessThanOrEqual(errorPanel.y + errorPanel.height);
  expect(state.posts).toHaveLength(1); const failedRequest = state.posts[0].body;
  await retry.click(); await expect.poll(() => state.posts.length).toBe(2);
  await expect(panel(page).getByText(nextReply, { exact: true })).toBeVisible();
  expect(state.posts[1].body).toEqual(failedRequest);
  await expect(panel(page).getByText('Объясни проще\nНа примере дробей', { exact: true })).toHaveCount(1);
  expect(state.errors).toEqual([]);
});

test('existing board ink survives opening and resizing Kolin without new annotation writes', async ({ page }) => {
  const state = await setup(page);
  const surface = page.locator('.whiteboard__page[aria-current="page"] .whiteboard__surface:visible');
  const box = await surface.boundingBox(), x = box.x + box.width * .35, y = box.y + box.height * .35;
  await page.mouse.move(x, y); await page.mouse.down(); await page.mouse.move(x + 55, y + 25, { steps: 8 }); await page.mouse.up();
  await expect.poll(() => state.annotationWrites.length).toBe(1);
  await expect.poll(() => page.evaluate(() => window.kolinFixture.visibleInkCount())).toBe(1);
  const saved = JSON.stringify(state.annotationWrites);
  await openKolin(page); await separator(page).focus(); await separator(page).press('ArrowLeft');
  await send(page, state, 'Объясни эту тему');
  await panel(page).getByRole('button', { name: 'Закрыть Kolin', exact: true }).click();
  await expect.poll(() => page.evaluate(() => window.kolinFixture.visibleInkCount())).toBe(1);
  expect(JSON.stringify(state.annotationWrites)).toBe(saved); expect(state.errors).toEqual([]);
});

test('late reply and previous history do not cross class or teacher scope', async ({ page }) => {
  const state = await setup(page); await openKolin(page); state.responseGate = gate();
  await input(page).fill('Секрет первого урока'); await input(page).press('Enter');
  await expect.poll(() => state.posts.length).toBe(1); await expect(panel(page).getByText('Kolin думает…', { exact: true })).toBeVisible();
  await expect(input(page)).toBeDisabled();
  await expect(panel(page).getByRole('button', { name: 'Отправить', exact: true })).toBeDisabled();
  await page.evaluate(classId => window.kolinFixture.setSession({ classId }), SECOND_CLASS);
  state.responseGate.release(); state.responseGate = null;
  if (!(await panel(page).isVisible())) await openKolin(page);
  await expect(panel(page).getByText('Секрет первого урока', { exact: true })).toHaveCount(0);
  await expect(panel(page).getByText(firstReply, { exact: true })).toHaveCount(0);
  await send(page, state, 'Новый урок'); expect(state.posts.at(-1).body.history).toEqual([]);
  state.teacherId = OTHER_TEACHER; await page.evaluate(teacherId => window.kolinFixture.setSession({ teacherId }), OTHER_TEACHER);
  if (!(await panel(page).isVisible())) await openKolin(page);
  await expect(panel(page).getByText('Новый урок', { exact: true })).toHaveCount(0);
  await send(page, state, 'Другой преподаватель'); expect(state.posts.at(-1).body.history).toEqual([]);
  expect(state.errors).toEqual([]);
});

test('Answers continues to open and resize independently of Kolin', async ({ page }) => {
  await page.setViewportSize({ width: 1280, height: 900 }); const state = await setup(page);
  const answersToggle = page.getByRole('button', { name: 'Ответы', exact: true }).first(); await expect(answersToggle).toBeEnabled();
  await answersToggle.click(); const answers = page.locator('#answers-panel'); await expect(answers).toBeVisible();
  const original = await answers.boundingBox(); const grip = page.getByRole('separator', { name: 'Изменить ширину решений', exact: true });
  await grip.focus(); await grip.press('ArrowRight'); await expect.poll(async () => (await answers.boundingBox()).width).toBeGreaterThan(original.width);
  const chosen = (await answers.boundingBox()).width;
  await openKolin(page); await expect(answers).not.toBeVisible();
  await answersToggle.click(); await expect(answers).toBeVisible(); await expect(panel(page)).not.toBeVisible();
  await expect.poll(async () => (await answers.boundingBox()).width).toBeCloseTo(chosen, 0);
  expect(state.errors).toEqual([]);
});

test('student has no Kolin button, pane or API traffic', async ({ page }) => {
  const state = await setup(page, 'student');
  await expect(toggle(page)).toHaveCount(0); await expect(page.locator('#kolin-panel')).toHaveCount(0);
  await page.getByRole('button', { name: 'Моя доска', exact: true }).click();
  await expect(page.locator('.whiteboard__page:visible').first()).toBeVisible();
  expect(state.requests.filter(request => request.path.includes('/kolin/'))).toEqual([]);
  expect(state.errors).toEqual([]);
});

test('unconfigured Kolin is readable and disabled without sending a message', async ({ page }) => {
  const state = await setup(page); state.configured = false;
  expect(state.requests.filter(request => request.path.includes('/kolin/'))).toEqual([]);
  await openKolin(page);
  await expect(panel(page).getByText('Kolin пока недоступен.', { exact: true })).toBeVisible();
  await input(page).fill('Дай задание'); await input(page).press('Enter');
  await expect(panel(page).getByRole('button', { name: 'Отправить', exact: true })).toBeDisabled();
  expect(state.posts).toEqual([]); expect(state.errors).toEqual([]);
});

test('temporary configuration-check failure can be retried without losing the draft', async ({ page }) => {
  const state = await setup(page); state.failStatusOnce = true;
  await openKolin(page); await input(page).fill('Дай задание');
  await panel(page).getByRole('button', { name: 'Повторить отправку', exact: true }).click();
  await expect(panel(page).getByRole('button', { name: 'Отправить', exact: true })).toBeEnabled();
  await expect(input(page)).toHaveValue('Дай задание');
  await input(page).press('Enter'); await expect.poll(() => state.posts.length).toBe(1);
  await expect(panel(page).getByText(firstReply, { exact: true })).toBeVisible();
  expect(state.errors).toEqual([]);
});
