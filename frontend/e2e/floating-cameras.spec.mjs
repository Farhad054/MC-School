import { test, expect } from '@playwright/test';

async function openRoom(page, answers = false) {
  const errors = [];
  page.on('pageerror', error => errors.push(error.message));
  await page.goto(`/e2e/fixtures/floating-cameras.html${answers ? '?answers=1' : ''}`);
  await expect(page.locator('.whiteboard__page').first()).toBeVisible();
  await expect.poll(() => page.locator('.whiteboard__page').first().evaluate(node => node.offsetHeight)).toBeGreaterThan(100);
  return errors;
}

async function geometry(page) {
  return page.evaluate(() => {
    const box = selector => {
      const node = document.querySelector(selector), rect = node.getBoundingClientRect();
      return [rect.x, rect.y, rect.width, rect.height];
    };
    const pages = document.querySelector('.whiteboard__pages');
    return { main: box('.online-class-room__main'), page: box('.whiteboard__page'),
      scroll: [pages.scrollLeft, pages.scrollTop], zoom: document.querySelector('.whiteboard__zoom-label').textContent };
  });
}

for (const viewport of [{ width: 1366, height: 900 }, { width: 1024, height: 768 },
  { width: 768, height: 1024 }, { width: 390, height: 844 }, { width: 844, height: 390 }]) {
  test(`floating cameras preserve the document at ${viewport.width}x${viewport.height}`, async ({ page }) => {
    await page.setViewportSize(viewport);
    const errors = await openRoom(page);
    await page.locator('.whiteboard__pages').evaluate(node => { node.scrollTop = 120; });
    const before = await geometry(page);
    const toggle = page.getByRole('button', { name: 'Видео', exact: true });
    await toggle.click();
    await expect(toggle).toHaveAttribute('aria-expanded', 'true');
    await expect(page.locator('.camera-card')).toHaveCount(4);
    await expect(page.locator('.camera-card__name')).toHaveText(['Учитель', 'Ученица 1', 'Ученица 2', 'Ученица 3']);
    const drawer = page.locator('#online-class-videos');
    await expect(drawer).toHaveAttribute('aria-hidden', 'false');
    await expect(drawer).toHaveCSS('transform', 'matrix(1, 0, 0, 1, 0, 0)');
    await expect(drawer).toHaveCSS('position', 'absolute');
    await expect(drawer).toHaveCSS('background-color', 'rgba(0, 0, 0, 0)');
    await expect(page.locator('.camera-card').first()).toHaveCSS('border-radius', '14px');
    await expect.poll(() => geometry(page)).toEqual(before);
    const floating = await drawer.boundingBox();
    expect(floating.x).toBeGreaterThanOrEqual(0);
    expect(floating.x + floating.width).toBeLessThanOrEqual(viewport.width);
    const card = await page.locator('.camera-card').first().boundingBox();
    const toolbar = await page.locator('.whiteboard__topline').boundingBox();
    expect(card.y).toBeGreaterThanOrEqual(toolbar.y + toolbar.height);
    // Empty space between separate cards must still belong to the board.
    const passThrough = await page.evaluate(() => {
      const [first, second] = document.querySelectorAll('.camera-card');
      const a = first.getBoundingClientRect(), b = second.getBoundingClientRect();
      const horizontal = Math.abs(a.top - b.top) < 2;
      const x = horizontal ? (a.right + b.left) / 2 : a.left + a.width / 2;
      const y = horizontal ? a.top + a.height / 2 : (a.bottom + b.top) / 2;
      return !document.elementFromPoint(x, y)?.closest('.online-class-room__videos-drawer');
    });
    expect(passThrough).toBe(true);
    await toggle.click();
    await expect(drawer).toHaveAttribute('aria-hidden', 'true');
    await expect(drawer).toHaveAttribute('inert', '');
    await expect.poll(() => geometry(page)).toEqual(before);
    await page.getByRole('button', { name: 'Все ученики (2×2)', exact: true }).click();
    const overview = await geometry(page);
    await toggle.click();
    await expect.poll(() => geometry(page)).toEqual(overview);
    expect(errors).toEqual([]);
  });
}

test('answer panel width and drawing survive showing and hiding camera islands', async ({ page }) => {
  await page.setViewportSize({ width: 1366, height: 900 });
  const errors = await openRoom(page, true);
  const answers = await page.locator('.answers-panel').boundingBox();
  const before = await geometry(page);
  await page.getByRole('button', { name: 'Видео', exact: true }).click();
  await expect.poll(() => page.locator('.answers-panel').boundingBox()).toEqual(answers);
  await expect.poll(() => geometry(page)).toEqual(before);
  const surface = await page.locator('.whiteboard__surface').first().boundingBox();
  await page.mouse.move(surface.x + 90, surface.y + 180);
  await page.mouse.down();
  await page.mouse.move(surface.x + 150, surface.y + 220, { steps: 8 });
  await page.mouse.up();
  await expect.poll(() => page.evaluate(() => window.cameraFixture.operations.length)).toBeGreaterThan(0);
  const count = await page.evaluate(() => window.cameraFixture.operations.length);
  await page.getByRole('button', { name: 'Видео', exact: true }).click();
  await expect.poll(() => page.evaluate(() => window.cameraFixture.operations.length)).toBe(count);
  expect(errors).toEqual([]);
});

for (const viewport of [{ width: 1366, height: 900 }, { width: 1024, height: 768 },
  { width: 768, height: 1024 }, { width: 390, height: 844 }]) {
  test(`teacher uses named board buttons without hiding Video or transcription at ${viewport.width}px`, async ({ page }) => {
    await page.setViewportSize(viewport);
    const errors = await openRoom(page);
    const switcher = page.getByRole('group', { name: 'Доска', exact: true });
    await expect(switcher).toBeVisible();
    await expect(page.getByRole('combobox')).toHaveCount(0);
    await expect(switcher.getByRole('button')).toHaveText(['Общая группа', 'Ученица 1', 'Ученица 2', 'Ученица 3', 'Ученица 4', 'Все']);
    const video = page.getByRole('button', { name: 'Видео', exact: true });
    const videoBox = await video.boundingBox();
    expect(videoBox.x + videoBox.width).toBeLessThanOrEqual(viewport.width);
    expect(await video.evaluate(node => {
      const box = node.getBoundingClientRect();
      return document.elementFromPoint(box.x + box.width / 2, box.y + box.height / 2)?.closest('button') === node;
    })).toBe(true);
    const transcription = page.locator('.transcription [role="status"]');
    await expect(transcription).toBeVisible();
    await expect(transcription).toContainText('Транскрипция включена');
    const topbar = await page.locator('.online-class-room__topbar').boundingBox();
    const tools = await page.locator('.whiteboard__topline').boundingBox();
    expect(tools.y).toBeGreaterThanOrEqual(topbar.y + topbar.height);
    await switcher.getByRole('button', { name: 'Доска ученика: Ученица 4', exact: true }).click();
    await expect(switcher.getByRole('button', { name: 'Доска ученика: Ученица 4', exact: true })).toHaveAttribute('aria-pressed', 'true');
    await switcher.getByRole('button', { name: 'Все ученики (2×2)', exact: true }).click();
    await expect(switcher.getByRole('button', { name: 'Все ученики (2×2)', exact: true })).toHaveAttribute('aria-pressed', 'true');
    await switcher.getByRole('button', { name: 'Общая группа', exact: true }).click();
    await expect(switcher.getByRole('button', { name: 'Общая группа', exact: true })).toHaveAttribute('aria-pressed', 'true');
    await video.click();
    await expect(video).toHaveAttribute('aria-expanded', 'true');
    expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBe(viewport.width);
    expect(errors).toEqual([]);
  });
}
