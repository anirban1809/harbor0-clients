import { mkdtemp, readFile, writeFile } from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { describe, expect, it, vi } from 'vitest';
import { ApiError } from '@harbor/api-client';
import { emptyMigrationProgress, type Migration } from '@harbor/contracts';
import type { DavDrive, DavEntry } from '../src/drive-dav';
import {
  MigrationRunner,
  googleDrive,
  planCopy,
  type SourceDrive,
  type SourceEntry,
} from '../src/migrations';

const file = (id: string, name: string, parentId: string | null, size: number | null = 3) =>
  ({
    id,
    name,
    parentId,
    folder: false,
    size,
    modifiedAt: '2024-01-02T00:00:00.000Z',
    createdAt: id,
  }) satisfies SourceEntry;
const folder = (id: string, name: string, parentId: string | null) =>
  ({
    id,
    name,
    parentId,
    folder: true,
    size: null,
    modifiedAt: null,
    createdAt: id,
  }) satisfies SourceEntry;

/** My Drive in memory: names, and the contents of each uploaded file. */
function memoryDrive() {
  const items = new Map<string, DavEntry & { content?: string }>();
  let next = 0;
  const entry = (parentId: string | null, name: string, isFolder: boolean, size = 0) => {
    const item = {
      id: `item-${++next}`,
      parentId,
      name,
      folder: isFolder,
      size,
      mimeType: '',
      created: '',
      modified: '',
      etag: '',
      encrypted: false,
    };
    items.set(item.id, item);
    return item;
  };
  const drive: DavDrive = {
    async list(folderId) {
      return [...items.values()].filter((i) => i.parentId === folderId);
    },
    async open() {
      throw new Error('unused');
    },
    write: vi.fn(async (parentId, name, source) => {
      const content = await readFile(source, 'utf8');
      const item = entry(parentId, name, false, content.length);
      return Object.assign(item, { content });
    }),
    async mkdir(parentId, name) {
      return entry(parentId, name, true);
    },
    async move() {},
    async remove() {},
  };
  const tree = (parentId: string | null, prefix = ''): string[] =>
    [...items.values()]
      .filter((i) => i.parentId === parentId)
      .flatMap((i) => [prefix + i.name, ...(i.folder ? tree(i.id, `${prefix}${i.name}/`) : [])])
      .sort();
  return { drive, items, tree };
}

function source(entries: SourceEntry[], fail: Record<string, Error> = {}): SourceDrive {
  return {
    async list() {
      return entries;
    },
    async download(entry, to) {
      if (fail[entry.id]) throw fail[entry.id];
      const body = 'x'.repeat(entry.size ?? 5);
      await writeFile(to, body);
      return body.length;
    },
  };
}

function migration(over: Partial<Migration> = {}): Migration {
  return {
    id: 'm1',
    provider: 'GOOGLE_DRIVE',
    connectionId: 'c1',
    deviceId: 'mac',
    devicePublicId: 'mac-install',
    deviceName: 'Mac',
    status: 'QUEUED',
    folderId: null,
    folderName: 'Google Drive',
    progress: emptyMigrationProgress(),
    error: null,
    createdAt: '',
    updatedAt: '',
    startedAt: null,
    finishedAt: null,
    reportedAt: null,
    ...over,
  };
}

async function runOnce(
  entries: SourceEntry[],
  options: {
    memory?: ReturnType<typeof memoryDrive>;
    serverStatus?: () => Migration['status'];
    fail?: Record<string, Error>;
    current?: Migration;
  } = {},
) {
  const memory = options.memory ?? memoryDrive();
  const reports: {
    status: string;
    progress: Migration['progress'];
    folderId?: string;
    error?: string;
  }[] = [];
  const current = options.current ?? migration();
  const notify = vi.fn();
  const runner = new MigrationRunner({
    async request(endpoint: string, init?: { body?: unknown }): Promise<any> {
      if (endpoint === '/v1/migrations') return { migrations: [current] };
      if (endpoint.endsWith('/access'))
        return {
          provider: 'GOOGLE_DRIVE',
          token: 't',
          expiresAt: new Date(Date.now() + 3600_000).toISOString(),
        };
      if (endpoint.endsWith('/report')) {
        const body = structuredClone(init!.body) as (typeof reports)[number];
        reports.push(body);
        return { migration: { ...current, status: options.serverStatus?.() ?? body.status } };
      }
      if (endpoint.startsWith('/v1/drive/items/')) {
        const item = memory.items.get(decodeURIComponent(endpoint.split('/').pop()!));
        if (!item) throw new ApiError('NOT_FOUND', 'Not found', 404);
        return { item: { type: item.folder ? 'FOLDER' : 'FILE', deletedAt: null } };
      }
      throw new Error(`Unexpected ${endpoint}`);
    },
    drive: memory.drive,
    device: () => ({ id: 'mac-new-sign-in', publicId: 'mac-install' }),
    enabled: async () => true,
    hold: () => null,
    tempDir: await mkdtemp(path.join(os.tmpdir(), 'harbor-migration-')),
    notify,
    sources: () => source(entries, options.fail),
  });
  await runner.check();
  await vi.waitFor(() => expect(runner.running).toBeNull(), { timeout: 5000 });
  return { memory, reports, notify, last: reports.at(-1)! };
}

describe('planCopy', () => {
  it('cleans names, numbers clashes oldest first, and puts orphans at the top', () => {
    const plan = planCopy([
      folder('a', 'Work', null),
      file('b', 'notes.txt', 'a'),
      file('c', 'notes.txt', 'a'),
      file('d', 'NOTES.txt', 'a'),
      file('e', 'q1/q2.csv', null),
      file('f', 'shared.pdf', 'not-mine'),
    ]);
    expect(plan.children.get('a')!.map((c) => c.name)).toEqual([
      'notes.txt',
      'notes (2).txt',
      'NOTES (3).txt',
    ]);
    expect(plan.children.get(null)!.map((c) => c.name)).toEqual([
      'Work',
      'q1_q2.csv',
      'shared.pdf',
    ]);
    expect(plan.files).toHaveLength(5);
  });
});

describe('MigrationRunner', () => {
  const entries = [
    folder('f1', 'Photos', null),
    folder('f2', '2024', 'f1'),
    file('a', 'beach.jpg', 'f2', 10),
    file('b', 'readme.txt', null, 4),
    { ...file('c', 'Survey', null, null), unsupported: 'Forms can’t be exported.' },
  ];

  it('copies the whole tree into a new folder on the computer it was meant for', async () => {
    const { memory, last, notify } = await runOnce(entries);
    expect(memory.tree(null)).toEqual([
      'Google Drive',
      'Google Drive/Photos',
      'Google Drive/Photos/2024',
      'Google Drive/Photos/2024/beach.jpg',
      'Google Drive/readme.txt',
    ]);
    expect(last).toMatchObject({ status: 'COMPLETED', folderId: expect.any(String) });
    expect(last.progress).toMatchObject({
      filesTotal: 2,
      filesDone: 2,
      bytesDone: 14,
      bytesTotal: 14,
      filesSkipped: 1,
      filesFailed: 0,
    });
    expect(notify).toHaveBeenCalledWith('Google Drive copied', expect.stringContaining('2 files'));
    // The original modification time goes up with the file.
    expect(vi.mocked(memory.drive.write).mock.calls[0][4]).toEqual(
      new Date('2024-01-02T00:00:00.000Z'),
    );
  });

  it('skips files an earlier run copied, into the same folder', async () => {
    const first = await runOnce(entries);
    const folderId = first.last.folderId!;
    vi.mocked(first.memory.drive.write).mockClear();
    const again = await runOnce([...entries, file('d', 'new.txt', null, 2)], {
      memory: first.memory,
      current: migration({ folderId }),
    });
    expect(vi.mocked(first.memory.drive.write).mock.calls.map((c) => c[1])).toEqual(['new.txt']);
    expect(again.last.folderId).toBe(folderId);
    expect(again.last.progress).toMatchObject({ filesDone: 3, filesTotal: 3 });
  });

  it('picks a new folder name when one is taken', async () => {
    const memory = memoryDrive();
    await memory.drive.mkdir(null, 'Google Drive');
    await runOnce(entries, { memory });
    expect(memory.tree(null)).toContain('Google Drive (2)/readme.txt');
  });

  it('lists files that fail and carries on', async () => {
    const { last } = await runOnce(entries, {
      fail: { a: new Error('Its owner turned off downloading in Google Drive.') },
    });
    expect(last.status).toBe('COMPLETED');
    expect(last.progress).toMatchObject({ filesDone: 1, filesFailed: 1 });
    expect(last.progress.failures).toEqual([
      {
        path: 'Photos/2024/beach.jpg',
        reason: 'Its owner turned off downloading in Google Drive.',
      },
    ]);
  });

  it('stops when the storage is full', async () => {
    const memory = memoryDrive();
    vi.mocked(memory.drive.write).mockRejectedValue(
      new ApiError('STORAGE_QUOTA_EXCEEDED', 'Full', 409),
    );
    const { last } = await runOnce(entries, { memory });
    expect(last).toMatchObject({
      status: 'FAILED',
      error: expect.stringContaining('storage is full'),
    });
  });

  it('stops without finishing when paused on the web', async () => {
    let reports = 0;
    const { reports: sent, memory } = await runOnce(entries, {
      serverStatus: () => (++reports > 1 ? 'PAUSED' : 'RUNNING'),
    });
    expect(sent.some((r) => r.status === 'COMPLETED')).toBe(false);
    expect(memory.tree(null).filter((n) => n.endsWith('.jpg') || n.endsWith('.txt'))).toEqual([]);
  });
});

describe('googleDrive', () => {
  it('exports Google Docs as Office files and skips what it cannot export', async () => {
    const fetchImpl = vi.fn(async (url: string) => {
      if (url.includes('/files/root')) return Response.json({ id: 'ROOT' });
      return Response.json({
        files: [
          {
            id: '1',
            name: 'Plan',
            mimeType: 'application/vnd.google-apps.document',
            parents: ['ROOT'],
            exportLinks: {
              'application/vnd.openxmlformats-officedocument.wordprocessingml.document':
                'https://docs.example/export/1',
            },
          },
          {
            id: '2',
            name: 'Form',
            mimeType: 'application/vnd.google-apps.form',
            parents: ['ROOT'],
          },
          { id: '3', name: 'a.png', mimeType: 'image/png', parents: ['F'], size: '7' },
        ],
      });
    }) as unknown as typeof fetch;
    const entries = await googleDrive(async () => 't', fetchImpl).list(
      new AbortController().signal,
    );
    expect(
      entries.map(({ name, parentId, size, unsupported }) => ({
        name,
        parentId,
        size,
        unsupported: !!unsupported,
      })),
    ).toEqual([
      { name: 'Plan.docx', parentId: null, size: null, unsupported: false },
      { name: 'Form', parentId: null, size: null, unsupported: true },
      { name: 'a.png', parentId: 'F', size: 7, unsupported: false },
    ]);
  });
});
