import { expect, _electron as electron } from '@playwright/test';
import { execFileSync } from 'node:child_process';
import { createServer } from 'node:http';
import { mkdir, mkdtemp, readFile, rm, writeFile } from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { rendererRequestSchema } from '../apps/desktop/src/request-schema';

// The desktop app's Photos page, ZIP archives opened as folders, and Shared → Links, all behind
// their account flags, against a fixture server. Run after `npm run build -w @harbor/desktop`.
const output = 'test-results/desktop-web-parity';
await mkdir(output, { recursive: true });
const scratch = await mkdtemp(path.join(os.tmpdir(), 'harbor-parity-'));
await mkdir(path.join(scratch, 'zip/docs'), { recursive: true });
await writeFile(path.join(scratch, 'zip/readme.txt'), 'Hello from inside the archive');
await writeFile(path.join(scratch, 'zip/docs/notes.txt'), 'Nested notes');
execFileSync('zip', ['-qr', path.join(scratch, 'Bundle.zip'), '.'], {
  cwd: path.join(scratch, 'zip'),
});
const pixel = Buffer.from(
  'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jRZkAAAAASUVORK5CYII=',
  'base64',
);
execFileSync('ffmpeg', [
  '-loglevel',
  'error',
  '-f',
  'lavfi',
  '-i',
  'testsrc=size=320x240:rate=10:duration=2',
  '-pix_fmt',
  'yuv420p',
  path.join(scratch, 'clip.mp4'),
]);
execFileSync('ffmpeg', [
  '-loglevel',
  'error',
  '-f',
  'lavfi',
  '-i',
  'color=red:size=8x8',
  '-frames:v',
  '1',
  path.join(scratch, 'photo.png'),
]);
const contents: Record<string, Buffer> = {
  bundle: await readFile(path.join(scratch, 'Bundle.zip')),
  photo: await readFile(path.join(scratch, 'photo.png')),
  kept: pixel,
  clip: await readFile(path.join(scratch, 'clip.mp4')),
};
const stamp = '2026-10-20T10:00:00Z';
const file = (id: string, name: string, mimeType: string) => ({
  id,
  name,
  type: 'FILE',
  mimeType,
  sizeBytes: contents[id].length,
  ownerUserId: 'demo',
  parentId: null,
  revision: 1,
  currentVersionId: `${id}-v1`,
  createdAt: stamp,
  updatedAt: stamp,
});
const items = [
  file('bundle', 'Bundle.zip', 'application/zip'),
  file('photo', 'Beach.png', 'image/png'),
  file('kept', 'Kept.png', 'image/png'),
  file('clip', 'Clip.mp4', 'video/mp4'),
];
const savedThumbnails: string[] = [];
const links: any[] = [];
const rejected: string[] = [];
const ranges: string[] = [];
function api(pathname: string, method: string, body: any) {
  if (pathname === '/v1/users/me')
    return {
      user: { id: 'demo', displayName: 'Parity test', username: 'parity', email: 'p@example.test' },
      storage: { usedBytes: 100, quotaBytes: 100_000_000_000, reservedBytes: 0 },
      flags: { photos: true, 'zip-preview': true, 'public-links': true },
    };
  if (pathname === '/v1/auth/session')
    return { device: { id: 'parity-device', devicePublicId: 'parity-device' } };
  if (pathname === '/v1/downloads') {
    const id = body.driveItemId;
    return { downloadUrl: `http://127.0.0.1:9100/content/${id}`, sizeBytes: contents[id].length };
  }
  if (pathname.startsWith('/v1/drive/items/'))
    return { item: items.find((item) => item.id === pathname.split('/').at(-1)) };
  if (pathname === '/v1/photos') return { items: items.slice(1), nextCursor: null };
  if (pathname === '/v1/photos/thumbnails') {
    savedThumbnails.push(...body.items.map((item: { id: string }) => item.id));
    return { saved: body.items.map((item: { id: string }) => item.id) };
  }
  if (pathname === '/v1/photos/links')
    return {
      // A file with a saved thumbnail is shown from it, never from its original.
      items: body.ids.map((id: string) => ({
        id,
        url: `http://127.0.0.1:9100/content/${id === 'kept' ? 'no-original' : id}`,
        sizeBytes: contents[id].length,
        ...(id === 'kept' ? { thumbnailUrl: 'http://127.0.0.1:9100/content/kept' } : {}),
      })),
      expiresAt: new Date(Date.now() + 3600_000).toISOString(),
    };
  if (pathname === '/v1/links/allowance')
    return { blockedReason: null, neverExpires: false, active: links.length, limit: 10 };
  if (pathname === '/v1/links' && method === 'POST') {
    const item = items.find((entry) => entry.id === body.driveItemId)!;
    const link = {
      id: `link-${links.length + 1}`,
      url: `https://harbor0content.online/l/abc${links.length + 1}`,
      driveItemId: item.id,
      itemType: 'FILE',
      itemName: item.name,
      createdAt: new Date().toISOString(),
      expiresAt: new Date(Date.now() + 7 * 86400_000).toISOString(),
      hasPassword: false,
      disabledAt: null,
      e2ee: null,
    };
    links.unshift(link);
    return { link };
  }
  if (pathname === '/v1/links') return { items: links };
  return {
    items: pathname.includes('/children') || pathname === '/v1/drive' ? items : [],
    nextCursor: null,
  };
}
const server = createServer(async (req, res) => {
  const pathname = new URL(req.url!, 'http://127.0.0.1').pathname;
  if (pathname.startsWith('/content/')) {
    const bytes = contents[pathname.split('/').at(-1)!];
    if (!bytes) return res.writeHead(404).end();
    const range = req.headers.range?.match(/^bytes=(\d+)-(\d*)$/);
    res.setHeader('Content-Type', 'application/octet-stream');
    if (!range) return res.writeHead(200).end(bytes);
    ranges.push(pathname);
    const start = Number(range[1]);
    const end = Math.min(Number(range[2] || bytes.length - 1), bytes.length - 1);
    res.setHeader('Content-Range', `bytes ${start}-${end}/${bytes.length}`);
    return res.writeHead(206).end(bytes.subarray(start, end + 1));
  }
  let raw = '';
  for await (const chunk of req) raw += chunk;
  res.setHeader('Content-Type', 'application/json');
  // The stand-in for the main process's request handler below still obeys its allowlist.
  if (req.headers['x-renderer-request']) {
    const allowed = rendererRequestSchema.safeParse({ path: req.url, method: req.method });
    if (!allowed.success) rejected.push(`${req.method} ${req.url}`);
  }
  if (pathname === '/v1/auth/refresh' || pathname === '/v1/auth/login')
    return res.end(
      JSON.stringify({ accessToken: 'parity', refreshToken: 'parity', expiresIn: 3600 }),
    );
  res.end(JSON.stringify(api(pathname, req.method!, raw ? JSON.parse(raw) : undefined)));
});
await new Promise<void>((resolve) => server.listen(0, '127.0.0.1', resolve));
const fixtureOrigin = `http://127.0.0.1:${(server.address() as import('node:net').AddressInfo).port}`;

let desktop: Awaited<ReturnType<typeof electron.launch>> | undefined;
const profile = await mkdtemp(path.join(os.tmpdir(), 'harbor-parity-profile-'));
try {
  desktop = await electron.launch({
    args: ['apps/desktop', `--user-data-dir=${profile}`],
    env: { ...process.env, HARBOR_DEV_AUTH: 'true', HARBOR_API_URL: fixtureOrigin },
  });
  const page = await desktop.firstWindow();
  await page.route('http://127.0.0.1:9100/content/**', async (route) => {
    const response = await fetch(
      route.request().url().replace('http://127.0.0.1:9100', fixtureOrigin),
      { headers: route.request().headers() },
    );
    await route.fulfill({
      status: response.status,
      headers: Object.fromEntries(response.headers),
      body: Buffer.from(await response.arrayBuffer()),
    });
  });
  await expect(page.getByRole('heading', { name: 'Sign in to harbor0' })).toBeVisible();
  await desktop.evaluate(
    ({ ipcMain }, { fixtureOrigin }) => {
      const originalFetch = globalThis.fetch;
      // Storage links point at the fixture server; the main process reads archive ranges there.
      globalThis.fetch = (input, init) =>
        originalFetch(
          typeof input === 'string'
            ? input.replace('http://127.0.0.1:9100/content/', fixtureOrigin + '/content/')
            : input,
          init,
        );
      ipcMain.removeHandler('harbor:status');
      ipcMain.handle('harbor:status', () => ({
        ok: true,
        data: { signedIn: true, accountId: 'demo', roots: [], jobs: [], sync: {} },
      }));
      ipcMain.removeHandler('harbor:request');
      ipcMain.handle('harbor:request', async (_event, input) => {
        const response = await originalFetch(fixtureOrigin + input.path, {
          method: input.method ?? 'GET',
          headers: { 'x-renderer-request': '1' },
          ...(input.body ? { body: JSON.stringify(input.body) } : {}),
        });
        return { ok: true, data: await response.json() };
      });
    },
    { fixtureOrigin },
  );
  await page.reload();
  await page.evaluate(() =>
    globalThis.window.harbor.login({ email: 'p@example.test', password: 'parity-fixture' }),
  );
  await expect(page.getByRole('heading', { name: 'My Drive', exact: true })).toBeVisible();

  // ZIP archives open as read-only folders, read through the main process a range at a time.
  await page.getByRole('button', { name: 'Bundle.zip', exact: true }).click();
  await expect(page.getByText('Read-only archive')).toBeVisible();
  await expect(page.getByRole('button', { name: 'readme.txt', exact: true })).toBeVisible();
  await page.getByRole('button', { name: 'docs', exact: true }).click();
  await expect(page.getByRole('button', { name: 'notes.txt', exact: true })).toBeVisible();
  await page.getByRole('button', { name: 'notes.txt', exact: true }).click();
  await expect(page.getByLabel('Text preview')).toHaveText('Nested notes');
  await page.screenshot({ path: `${output}/zip.png` });
  await page.keyboard.press('Escape');
  await page.locator('.drive-breadcrumbs').getByRole('button', { name: 'Bundle.zip' }).click();
  await expect(page.getByRole('button', { name: 'readme.txt', exact: true })).toBeVisible();
  // Copying out unpacks in the window, saves each file to disk, then uploads it like a dropped one.
  await desktop.evaluate(({ ipcMain }) => {
    const fs = process.getBuiltinModule('node:fs');
    ipcMain.removeHandler('harbor:upload');
    ipcMain.handle('harbor:upload', (_event, input) => {
      (globalThis as any).staged = input.dropped.map((entry: any) => ({
        folders: entry.folders,
        name: entry.path.split(/[\\/]/).at(-1),
        text: fs.readFileSync(entry.path, 'utf8'),
      }));
      return { ok: true, data: { items: [] } };
    });
  });
  await page.getByRole('button', { name: 'Copy all to My Drive' }).click();
  await expect
    .poll(() => desktop!.evaluate(() => (globalThis as any).staged))
    .toEqual(
      expect.arrayContaining([
        { folders: [], name: 'readme.txt', text: 'Hello from inside the archive' },
        { folders: ['docs'], name: 'notes.txt', text: 'Nested notes' },
      ]),
    );
  await page.locator('.drive-breadcrumbs').getByRole('button', { name: 'My Drive' }).click();
  await expect(page.getByRole('button', { name: 'Beach.png', exact: true })).toBeVisible();
  expect(ranges.some((entry) => entry.endsWith('/bundle'))).toBe(true);
  console.log(
    'PASS: ZIP archive browsing, nested folders, entry preview, copy out and breadcrumbs',
  );

  // Public links: made from the drive menu, then listed under Shared → Links.
  await page.getByRole('button', { name: 'Actions for Beach.png', exact: true }).click();
  await page.getByRole('menuitem', { name: 'Get public link' }).click();
  const dialog = page.getByRole('dialog');
  await expect(dialog.getByRole('heading', { name: 'Share with a link' })).toBeVisible();
  await dialog.getByRole('button', { name: /Create link|Make link|Create public link/ }).click();
  await expect(dialog.getByRole('textbox', { name: 'Public link' })).toHaveValue(
    'harbor0content.online/l/abc1',
  );
  await page.screenshot({ path: `${output}/link-dialog.png` });
  await page.keyboard.press('Escape');
  await page.getByRole('button', { name: 'Shared', exact: true }).click();
  await page.getByRole('tab', { name: 'Links' }).click();
  await expect(page.getByText('Public links')).toBeVisible();
  await expect(page.locator('.public-link-row')).toContainText('Beach.png');
  await page.screenshot({ path: `${output}/links.png` });
  console.log('PASS: Get public link in the drive menu, creating a link, Shared → Links');

  // Photos, from the sidebar, with thumbnails from signed links.
  await page.getByRole('button', { name: 'Photos', exact: true }).click();
  await expect(page.getByRole('heading', { name: 'Photos', exact: true })).toBeVisible();
  await expect(page.getByText('2 photos and 1 video')).toBeVisible();
  const drawn = (kind: string, name: string) =>
    page
      .locator(`.photo-tile[data-kind="${kind}"][title^="${name}"] img`)
      .evaluate((el: HTMLImageElement) => el.naturalWidth);
  await expect.poll(() => drawn('photo', 'Beach.png')).toBe(8);
  await expect.poll(() => drawn('photo', 'Kept.png')).toBe(1);
  // A video's frame, drawn from ranges of the file through the main process.
  await expect.poll(() => drawn('video', 'Clip.mp4'), { timeout: 15_000 }).toBeGreaterThan(1);
  // Drawn once here, then saved for every app; the one that had a thumbnail isn't saved again.
  await expect.poll(() => savedThumbnails.sort(), { timeout: 5_000 }).toEqual(['clip', 'photo']);
  // Encrypting a thumbnail needs the account's keys, not a different kind of bytes.
  expect(
    await page.evaluate(() =>
      globalThis.window.harbor
        .sealThumbnail({ bytes: new Uint8Array(4) })
        .catch((error: Error) => error.message),
    ),
  ).toContain('Unlock your files');
  const tile = await page.locator('.photo-tile').first().boundingBox();
  expect(tile!.width).toBeGreaterThan(80);
  expect(tile!.height).toBeGreaterThan(80);
  await page.screenshot({ path: `${output}/photos.png` });
  console.log('PASS: Photos page with thumbnails');
  expect(rejected).toEqual([]);
} finally {
  await desktop?.close();
  server.closeAllConnections();
  await new Promise<void>((resolve) => server.close(() => resolve()));
  await rm(profile, { recursive: true, force: true });
  await rm(scratch, { recursive: true, force: true });
}
