import { existsSync } from 'node:fs';
import path from 'node:path';

const root = path.resolve(import.meta.dirname, '..');
export const isBackend = (dir: string) => existsSync(path.join(dir, 'apps/backend/src/api.ts'));

/** The harbor0-src checkout: HARBOR_BACKEND_DIR, ./backend, ../harbor0-src or ../files. */
export function backendDir(): string | undefined {
  const candidates = process.env.HARBOR_BACKEND_DIR
    ? [path.resolve(process.env.HARBOR_BACKEND_DIR)]
    : [
        path.join(root, 'backend'),
        path.resolve(root, '../harbor0-src'),
        path.resolve(root, '../files'),
      ];
  return candidates.find(isBackend);
}
