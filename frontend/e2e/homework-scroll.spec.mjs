import { test, expect } from '@playwright/test';

const homeworkId = 'homework-scroll-fixture';
const student = {
  id: '11111111-1111-4111-8111-111111111111', role: 'STUDENT',
  fullName: 'Browser Student', email: 'student@example.invalid', preferredLanguage: 'DE',
};

async function openHomework(page, { submitted = false, viewport = { width: 1280, height: 800 } } = {}) {
  const captured = { overlays: undefined };
  let isSubmitted = submitted;
  const worksheet = {
    id: homeworkId, hasWorksheet: true, worksheetPageCount: 2,
    worksheetFilename: 'Two pages.pdf', title: 'Two pages',
    startDate: '2026-11-02', dueDate: '2026-11-09',
  };
  await page.addInitScript(user => {
    localStorage.setItem('accessToken', 'browser-fixture-only');
    localStorage.setItem('authUser', JSON.stringify(user));
  }, student);
  await page.route('**/api/v1/**', async route => {
    const path = new URL(route.request().url()).pathname;
    if (path.endsWith('/auth/me')) return route.fulfill({ json: student });
    if (path.endsWith('/study/homeworks')) return route.fulfill({ json: [{
      ...worksheet, submitted: isSubmitted,
      submittedAt: isSubmitted ? '2026-11-02T12:00:00Z' : null,
    }] });
    const pageMatch = path.match(/\/worksheet\/pages\/(\d+)\/data-url$/);
    if (pageMatch) {
      const number = Number(pageMatch[1]) + 1;
      const svg = `<svg xmlns="http://www.w3.org/2000/svg" width="800" height="1120"><rect width="800" height="1120" fill="white"/><text x="60" y="90" font-size="38">Homework page ${number}</text><path d="M60 200H740M60 400H740M60 600H740" stroke="#bbb"/></svg>`;
      return route.fulfill({ contentType: 'text/plain', body: `data:image/svg+xml;base64,${Buffer.from(svg).toString('base64')}` });
    }
    if (path.endsWith(`/study/homeworks/${homeworkId}/submit-pdf`)) {
      captured.overlays = route.request().postDataJSON().overlays;
      isSubmitted = true;
      return route.fulfill({ status: 204 });
    }
    return route.fulfill({ json: [] });
  });
  await page.setViewportSize(viewport);
  await page.goto(`/student/homeworks/${homeworkId}/worksheet`);
  await expect(page.getByRole('region', { name: 'Seite 1', exact: true })).toBeVisible();
  return captured;
}

async function drawOn(page, index) {
  const canvas = page.getByTestId(`homework-ink-${index}`);
  await canvas.scrollIntoViewIfNeeded();
  await expect(canvas).toBeVisible();
  await expect(canvas).toHaveCSS('opacity', '1');
  await expect.poll(() => canvas.evaluate(node => node.width)).toBeGreaterThan(1);
  const box = await canvas.boundingBox();
  const x = box.x + box.width * 0.25;
  // A tall sheet is centered by scrollIntoViewIfNeeded; choose a point in
  // its visible area below the sticky toolbar, rather than above the viewport.
  const y = Math.min(Math.max(box.y + box.height * 0.3, 160), page.viewportSize().height - 120);
  await page.mouse.move(x, y);
  await page.mouse.down();
  await page.mouse.move(x + 35, y + 18, { steps: 5 });
  await page.mouse.move(x + 65, y + 5, { steps: 5 });
  await page.mouse.up();
  return page.getByRole('region', { name: `Seite ${index + 1}`, exact: true }).locator('canvas').first();
}

test('children scroll between worksheet pages and submit ink from both pages', async ({ page }) => {
  const captured = await openHomework(page);
  const first = page.getByRole('region', { name: 'Seite 1', exact: true });
  const second = page.getByRole('region', { name: 'Seite 2', exact: true });
  await expect(first).toBeAttached();
  await expect(second).toBeAttached();
  const firstBox = await first.boundingBox();
  const secondBox = await second.boundingBox();
  expect(secondBox.y).toBeGreaterThanOrEqual(firstBox.y + firstBox.height);

  const beforeScroll = await page.evaluate(() => window.scrollY);
  await page.mouse.move(1100, 650);
  await page.mouse.wheel(0, 1200);
  await expect.poll(() => page.evaluate(() => window.scrollY)).toBeGreaterThan(beforeScroll + 100);
  await second.scrollIntoViewIfNeeded();
  await expect(second).toBeInViewport();
  await expect(first).toBeAttached();

  const firstCanvas = await drawOn(page, 0);
  await expect(first.getByRole('button', { name: 'Rückgängig', exact: true })).toBeEnabled();
  const firstInk = await firstCanvas.evaluate(canvas => canvas.toDataURL());
  const secondCanvas = await drawOn(page, 1);
  await expect(second.getByRole('button', { name: 'Rückgängig', exact: true })).toBeEnabled();
  expect(await firstCanvas.evaluate(canvas => canvas.toDataURL())).toBe(firstInk);
  const secondInk = await secondCanvas.evaluate(canvas => canvas.toDataURL());
  await second.getByRole('button', { name: 'Rückgängig', exact: true }).click();
  await expect(second.getByRole('button', { name: 'Rückgängig', exact: true })).toBeDisabled();
  await expect.poll(() => secondCanvas.evaluate(canvas => canvas.toDataURL())).not.toBe(secondInk);
  expect(await firstCanvas.evaluate(canvas => canvas.toDataURL())).toBe(firstInk);
  await drawOn(page, 1);
  await expect(secondCanvas).toBeAttached();

  page.once('dialog', dialog => dialog.accept());
  await page.getByRole('button', { name: 'Hausaufgabe abgeben', exact: true }).click();
  await expect.poll(() => captured.overlays?.map(overlay => overlay.pageIndex)).toEqual([0, 1]);
  expect(captured.overlays.every(overlay => overlay.imageBase64.startsWith('data:image/png;base64,'))).toBe(true);
  await expect(page.getByText('Hausaufgabe wurde abgegeben.', { exact: true })).toBeVisible();
});

test('submitted homework also keeps every page in the vertical read-only flow', async ({ page }) => {
  await openHomework(page, { submitted: true });
  const second = page.getByRole('region', { name: 'Seite 2', exact: true });
  await second.scrollIntoViewIfNeeded();
  await expect(second.getByRole('img')).toBeVisible();
  await expect(page.getByRole('region', { name: 'Seite 1', exact: true })).toBeAttached();
  await expect(page.getByTestId('homework-ink-0')).toHaveCount(0);
  await expect(page.getByTestId('homework-ink-1')).toHaveCount(0);
});

test.describe('Android finger scrolling', () => {
  test.use({ isMobile: true, hasTouch: true,
    userAgent: 'Mozilla/5.0 (Linux; Android 13; Browser Fixture) AppleWebKit/537.36 Chrome/130.0.0.0 Mobile Safari/537.36' });

  test('one finger scrolls over a worksheet without drawing on it', async ({ page }) => {
    await openHomework(page, { viewport: { width: 412, height: 800 } });
    const canvas = page.getByTestId('homework-ink-0');
    await canvas.scrollIntoViewIfNeeded();
    await expect(canvas).toHaveCSS('opacity', '1');
    const box = await canvas.boundingBox();
    const startY = Math.min(box.y + box.height * 0.85, 700);
    const endY = Math.max(box.y + box.height * 0.1, 140);
    const x = box.x + box.width * 0.6;
    const before = await page.evaluate(() => window.scrollY);
    const input = await page.context().newCDPSession(page);
    await input.send('Input.dispatchTouchEvent', { type: 'touchStart', touchPoints: [{ x, y: startY, id: 1 }] });
    for (let step = 1; step <= 10; step++) {
      await input.send('Input.dispatchTouchEvent', { type: 'touchMove', touchPoints: [{
        x, y: startY + (endY - startY) * step / 10, id: 1,
      }] });
      await page.waitForTimeout(20);
    }
    await input.send('Input.dispatchTouchEvent', { type: 'touchEnd', touchPoints: [] });
    await expect.poll(() => page.evaluate(() => window.scrollY)).toBeGreaterThan(before + 80);
    await expect(page.getByTitle('Rückgängig', { exact: true })).toBeDisabled();
    const hasInk = await page.getByRole('region', { name: 'Seite 1', exact: true }).locator('canvas').first()
      .evaluate(node => node.getContext('2d').getImageData(0, 0, node.width, node.height).data
        .some((value, index) => index % 4 === 3 && value > 0));
    expect(hasInk).toBe(false);
    await expect(page.getByRole('region', { name: 'Seite 2', exact: true })).toBeAttached();
    await input.detach();
  });
});
