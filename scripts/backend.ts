// The backend (API, domain logic, infrastructure) lives in anirban1809/harbor0-src. The desktop
// integration tests, the e2e tests and a few checks run it in-process or as the local API, so
// they need a checkout of it next to this one.
//   npm run backend:link            link ./backend and apps/backend to the checkout
//   npm run dev:api                 run the backend's local API (npm run dev there)
//   tsx scripts/backend.ts run …    run any of the backend's npm scripts
// The checkout is HARBOR_BACKEND_DIR, or the first of ../harbor0-src and ../files that exists.
import { spawnSync } from 'node:child_process';
import {
  existsSync,
  lstatSync,
  readlinkSync,
  realpathSync,
  symlinkSync,
  unlinkSync,
} from 'node:fs';
import path from 'node:path';
import { backendDir, isBackend } from './backend-dir';

const root = path.resolve(import.meta.dirname, '..');
const link = path.join(root, 'backend');
const appLink = path.join(root, 'apps/backend');

function replaceLink(at: string, target: string) {
  if (existsSync(at) || isSymlink(at)) {
    if (!isSymlink(at)) throw new Error(`${path.relative(root, at)} exists and is not a link.`);
    unlinkSync(at);
  }
  symlinkSync(target, at, 'dir');
}
function isSymlink(at: string) {
  try {
    return lstatSync(at).isSymbolicLink();
  } catch {
    return false;
  }
}

const [command, ...rest] = process.argv.slice(2);
const dir = backendDir();
if (command === 'link') {
  if (!dir)
    throw new Error(
      'No harbor0-src checkout found. Clone it next to this repository or set HARBOR_BACKEND_DIR.',
    );
  const target = realpathSync(dir);
  // In CI the checkout is ./backend itself; elsewhere ./backend links to it.
  if (path.resolve(dir) !== link && !(isSymlink(link) && readlinkSync(link) === target))
    replaceLink(link, target);
  replaceLink(appLink, path.join(target, 'apps/backend'));
  console.log(`Linked backend → ${target}`);
} else if (command === 'run') {
  if (!dir) throw new Error('No harbor0-src checkout found; run `npm run backend:link` first.');
  const result = spawnSync('npm', ['run', ...rest], {
    cwd: dir,
    stdio: 'inherit',
    shell: process.platform === 'win32',
  });
  process.exit(result.status ?? 1);
} else if (command === 'typecheck') {
  // The files that import the backend's sources typecheck only when it is linked.
  if (!isBackend(link)) {
    console.log('Backend not linked; skipped typechecking the backend integration tests.');
  } else {
    const result = spawnSync('npx', ['tsc', '--noEmit', '-p', 'tsconfig.backend.json'], {
      cwd: root,
      stdio: 'inherit',
      shell: process.platform === 'win32',
    });
    process.exit(result.status ?? 1);
  }
} else if (command !== undefined) {
  throw new Error(`Unknown command "${command}": use link, run or typecheck.`);
}
