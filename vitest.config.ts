import { existsSync, readdirSync, readFileSync } from 'node:fs';
import { defineConfig } from 'vitest/config';

// Tests that run the backend in-process import it from apps/backend, a link to a harbor0-src
// checkout (`npm run backend:link`). Without the link they are skipped.
const linked = existsSync('apps/backend/src/api.ts');
const needsBackend = linked
  ? []
  : ['apps', 'packages'].flatMap((dir) =>
      readdirSync(dir, { recursive: true, encoding: 'utf8' })
        .map((file) => `${dir}/${file}`)
        .filter(
          (file) =>
            /\/test\/.*\.test\.ts$/.test(file) &&
            !file.includes('node_modules') &&
            /from '((\.\.\/)+backend\/|\.\/two-computers')/.test(readFileSync(file, 'utf8')),
        ),
    );
if (needsBackend.length)
  console.warn(
    `Backend not linked: skipping ${needsBackend.length} backend integration test files.`,
  );

export default defineConfig({
  test: {
    include: ['apps/**/test/**/*.test.ts', 'packages/**/test/**/*.test.ts'],
    exclude: ['**/node_modules/**', 'apps/backend/**', ...needsBackend],
    testTimeout: 30000,
  },
  resolve: {
    alias: {
      '@harbor/contracts': new URL('./packages/contracts/src/index.ts', import.meta.url).pathname,
      '@harbor/api-client': new URL('./packages/api-client/src/index.ts', import.meta.url).pathname,
    },
  },
});
