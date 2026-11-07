import { defineConfig } from '@playwright/test';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
const frontendRoot = resolve(dirname(fileURLToPath(import.meta.url)), '..');
export default defineConfig({
  testDir: '.', testMatch: 'kolin.spec.mjs', workers: 1, timeout: 60000,
  outputDir: resolve(frontendRoot, 'test-results/kolin'),
  use: { baseURL: 'http://127.0.0.1:4183', browserName: 'chromium',
    launchOptions: { executablePath: process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE || undefined },
    trace: 'retain-on-failure', screenshot: 'only-on-failure' },
  webServer: { command: 'node e2e/kolin-start-vite.mjs', url: 'http://127.0.0.1:4183/e2e/kolin-room.html',
    cwd: frontendRoot,
    reuseExistingServer: !process.env.CI, timeout: 120000 },
});
