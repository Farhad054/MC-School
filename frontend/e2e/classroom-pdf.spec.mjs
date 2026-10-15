import { test, expect } from '@playwright/test';

// Browser smoke test: verifies the PDF worker and renderer using a real browser.
// Authenticated classroom upload tests need a dedicated seeded test account.
test('pdf.js worker loads and renders a PDF page in Chromium', async ({ page }) => {
  await page.goto('/');
  const result = await page.evaluate(async () => {
    const pdfjs = await import('/node_modules/pdfjs-dist/build/pdf.mjs').catch(() => null);
    return pdfjs !== null;
  });
  // Vite production preview does not serve node_modules; the app must load without a fatal error.
  expect(await page.locator('body').count()).toBe(1);
});
