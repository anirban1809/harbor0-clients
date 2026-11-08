import { expect, it, vi } from 'vitest';
import { readdir, stat, writeFile } from 'node:fs/promises';
import path from 'node:path';
import { SyncEngine } from '../src/sync';
import { tick, twoComputers } from './two-computers';

vi.mock('../src/local-watcher', async () => {
  const { EventEmitter } = await import('node:events');
  return { watchTree: () => Object.assign(new EventEmitter(), { close: async () => {} }) };
});

it('sends a local change while catching up on a long feed, then carries on from where it was', async () => {
  const { computers, folder, cloud } = await twoComputers();
  const [first, second] = computers;
  // A feed for the second computer to catch up on.
  for (let index = 0; index < 6; index++) {
    await writeFile(path.join(first.localPath, `remote-${index}.txt`), `${index}`);
    first.journal.enqueue(first.root.id, `remote-${index}.txt`, 'upsert');
  }
  await tick(first);
  const start = second.journal.get<number>('cursor') ?? 0;
  // Only the feed brings the files over; a full folder check would list them first.
  second.journal.root({ ...second.root, needsReconcile: false });
  // The second computer gets through one change, then time runs on while it is still catching
  // up, and a file is saved on it.
  let clock = Date.now();
  vi.spyOn(Date, 'now').mockImplementation(() => clock);
  const remoteItem = SyncEngine.prototype.remoteItem;
  let calls = 0;
  vi.spyOn(second.engine, 'remoteItem').mockImplementation(async function (
    this: SyncEngine,
    ...args
  ) {
    if (++calls === 1) {
      const local = path.join(second.localPath, 'mine.txt');
      await writeFile(local, 'saved here');
      second.journal.enqueue(second.root.id, 'mine.txt', 'upsert', {
        type: 'FILE',
        sizeBytes: (await stat(local)).size,
        updatedAt: new Date().toISOString(),
      });
      clock += 20_000;
    }
    return remoteItem.apply(this, args);
  });
  // When the local file goes up, how far the feed had got.
  let feedAtUpload: number | undefined;
  const request = second.api.request.bind(second.api);
  vi.spyOn(second.api, 'request').mockImplementation(async (url, init) => {
    if (/^\/v1\/uploads(\/batch)?$/.test(url)) feedAtUpload ??= calls;
    return request(url, init);
  });
  await tick(second);
  // The local file went up after one feed change, before the other five; then the feed finished.
  expect(feedAtUpload).toBe(1);
  expect(calls).toBeGreaterThanOrEqual(6);
  expect((await cloud.list(folder.id)).items.map((item) => item.name)).toContain('mine.txt');
  expect(
    (await readdir(second.localPath)).filter((name) => name.startsWith('remote-')),
  ).toHaveLength(6);
  expect(second.journal.get<number>('cursor')!).toBeGreaterThan(start);
});

it('stops catching up on a long feed for a restore asked for on this computer', async () => {
  const { computers } = await twoComputers();
  const [first, second] = computers;
  for (let index = 0; index < 6; index++) {
    await writeFile(path.join(first.localPath, `remote-${index}.txt`), `${index}`);
    first.journal.enqueue(first.root.id, `remote-${index}.txt`, 'upsert');
  }
  await tick(first);
  second.journal.root({ ...second.root, needsReconcile: false });
  let clock = Date.now();
  vi.spyOn(Date, 'now').mockImplementation(() => clock);
  const engine = second.engine as unknown as { runTick(): Promise<void> };
  const runTick = vi.spyOn(engine, 'runTick');
  const remoteItem = SyncEngine.prototype.remoteItem;
  let calls = 0;
  let passesAtSecondItem: number | undefined;
  vi.spyOn(second.engine, 'remoteItem').mockImplementation(async function (
    this: SyncEngine,
    ...args
  ) {
    if (++calls === 1) {
      second.engine.restoreSoon();
      clock += 20_000;
    }
    if (calls === 2) passesAtSecondItem = runTick.mock.calls.length;
    return remoteItem.apply(this, args);
  });
  await tick(second);
  // A new pass, which looks for restores first, started before the feed went on.
  expect(passesAtSecondItem).toBe(2);
  expect(
    (await readdir(second.localPath)).filter((name) => name.startsWith('remote-')),
  ).toHaveLength(6);
});

it('moves past a feed change for a folder removed from sync elsewhere in the account', async () => {
  const { computers, folder, cloud } = await twoComputers();
  const [first, second] = computers;
  await tick(second);
  // Another top-level folder synced on the first computer, edited, then removed from sync.
  const other = (await cloud.createFolder('Other')).item;
  await cloud.request('/v1/sync/folders', {
    method: 'PUT',
    body: { folderIds: [folder.id, other.id] },
  });
  await cloud.request(`/v1/drive/items/${other.id}`, {
    method: 'PATCH',
    body: { operationId: crypto.randomUUID(), baseRevision: other.revision, name: 'Other renamed' },
  });
  // A change inside it too.
  await cloud.createFolder('Inside', other.id);
  await cloud.request(`/v1/sync/folders/${other.id}`, { method: 'DELETE' });
  await writeFile(path.join(first.localPath, 'after.txt'), 'later');
  first.journal.enqueue(first.root.id, 'after.txt', 'upsert');
  await tick(first);
  const start = second.journal.get<number>('cursor') ?? 0;
  await tick(second);
  // The removed folder is not this computer's concern: the feed goes on and later files arrive.
  expect(second.journal.get<number>('cursor')!).toBeGreaterThan(start);
  expect(await readdir(second.localPath)).toContain('after.txt');
  const cursor = second.journal.get<number>('cursor');
  await tick(second);
  expect(second.journal.get<number>('cursor')).toBe(cursor);
});

it('looks up each folder once while catching up on changes outside its synced folders', async () => {
  const { computers, cloud } = await twoComputers();
  const [, second] = computers;
  await tick(second);
  // A deep folder elsewhere in the account, such as a backup, filling up.
  let parent: string | undefined;
  for (let depth = 0; depth < 6; depth++)
    parent = (await cloud.createFolder(`level-${depth}`, parent)).item.id;
  for (let index = 0; index < 20; index++) await cloud.createFolder(`leaf-${index}`, parent);
  let lookups = 0;
  const request = second.api.request.bind(second.api);
  vi.spyOn(second.api, 'request').mockImplementation(async (url, init) => {
    if (url.startsWith('/v1/drive/items/') && !init?.method) lookups++;
    return request(url, init);
  });
  await tick(second);
  // One lookup per change, and the folders above them once: not the whole chain for each.
  expect(lookups).toBeLessThanOrEqual(26 + 6);
});
