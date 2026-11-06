import { defineConfig } from '@playwright/test';
export default defineConfig({
  testDir: './e2e', testMatch: 'floating-cameras.spec.mjs', timeout: 45000, workers: 1,
  use: { baseURL: 'http://127.0.0.1:4179', browserName: 'chromium',
    launchOptions: { executablePath: process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE || undefined },
    trace: 'retain-on-failure', screenshot: 'only-on-failure' },
  webServer: { command: 'node e2e/start-floating-cameras-vite.mjs', url: 'http://127.0.0.1:4179/e2e/fixtures/floating-cameras.html',
    timeout: 60000, reuseExistingServer: !process.env.CI },
});
