import { test, expect } from '@playwright/test';

// Public smoke test. Full upload/render flow requires seeded classroom credentials.
test('production frontend bundle loads without a fatal browser error', async ({ page }) => {
  const errors = [];
  page.on('pageerror', error => errors.push(error.message));
  const response = await page.goto('/');
  expect(response?.ok()).toBeTruthy();
  await expect(page.locator('body')).toBeVisible();
  expect(errors).toEqual([]);
});
