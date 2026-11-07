import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { createRequire } from 'node:module';
import { build, preview } from 'vite';
import react from '@vitejs/plugin-react';
const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const require = createRequire(import.meta.url);
const realLiveKit = resolve(dirname(require.resolve('@livekit/components-react')), 'index.mjs');
const outDir = resolve(root, 'test-results/kolin-build');
const alias = [
  { find: /^@livekit\/components-react$/, replacement: resolve(root, 'e2e/kolin-livekit-adapter.tsx') },
  { find: /^@livekit\/components-react-real$/, replacement: realLiveKit },
];
await build({ root, configFile: false, plugins: [react()], resolve: { alias },
  define: { 'import.meta.env.VITE_API_BASE_URL': JSON.stringify('/api/v1') },
  build: { outDir, emptyOutDir: false, rollupOptions: { input: resolve(root, 'e2e/kolin-room.html') } } });
const server = await preview({ root, configFile: false, build: { outDir },
  preview: { host: '127.0.0.1', port: 4183, strictPort: true } });
server.printUrls();
