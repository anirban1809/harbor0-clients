import { createHash } from 'node:crypto';
import { describe, expect, it, vi } from 'vitest';
import type { ApiClient } from '@harbor/api-client';
import { HEADER_SIZE, accountKeys } from '../../../packages/api-client/src/e2ee';
import { uploadEncrypted, type EncryptedResume } from '../lib/e2ee-files';
import { openCipher } from '../lib/e2ee-worker';
import { bytesLeft, speedMeter, timeLeft, type MigrationProgress } from '../lib/e2ee-migration';

const PART = 3 * 1024 * 1024;
const bytes = (length: number) => {
  const b = new Uint8Array(length);
  for (let i = 0; i < length; i++) b[i] = (i * 7) % 251;
  return b;
};

/** Upload routes over an in-memory store of parts. */
function fakeApi() {
  const stored = new Map<number, Uint8Array>();
  const completed: { hash?: string } = {};
  const api = {
    createUpload: async () => ({ upload: { id: 'up-1', partSizeBytes: PART } }),
    request: async () => ({
      upload: { state: 'UPLOADING' },
      parts: [...stored.keys()].map((n) => ({ partNumber: n, etag: `etag-${n}` })),
    }),
    parts: async (_: string, [n]: number[]) => ({
      parts: [{ partNumber: n, uploadUrl: `part-${n}` }],
    }),
    complete: async (_: string, __: unknown, hash: string) => {
      completed.hash = hash;
      return { item: { id: 'item-1' } };
    },
  } as unknown as ApiClient;
  const put =
    (stop?: { at: number; controller: AbortController }) => async (url: string, data: Blob) => {
      const n = Number(url.slice(5));
      if (stop && n >= stop.at) {
        stop.controller.abort();
        throw new Error('The tab closed.');
      }
      stored.set(n, new Uint8Array(await data.arrayBuffer()));
      return `etag-${n}`;
    };
  const object = () => {
    const parts = [...stored.entries()].sort((a, b) => a[0] - b[0]).map(([, b]) => b);
    const all = new Uint8Array(parts.reduce((n, b) => n + b.length, 0));
    let at = 0;
    for (const b of parts) all.set(b, (at += b.length) - b.length);
    return all;
  };
  return { api, put, object, completed };
}

describe('resuming an encrypted upload', () => {
  it('starts after the last part whose hash it saved, and the hash still covers every part', async () => {
    const keys = await accountKeys(crypto.getRandomValues(new Uint8Array(32)));
    const data = bytes(10 * 1024 * 1024);
    const reads: number[] = [];
    const source = {
      size: data.length,
      read: async (b: number, e: number) => (reads.push(b), data.slice(b, e)),
    };
    const { api, put, object, completed } = fakeApi();
    let saved: EncryptedResume | undefined;
    const base = {
      api,
      keys,
      source,
      name: 'film.mkv',
      parentId: null,
      onCreated: (r: EncryptedResume) => (saved = r),
      onProgress: () => {},
    };

    const first = new AbortController();
    await expect(
      uploadEncrypted({ ...base, signal: first.signal, put: put({ at: 3, controller: first }) }),
    ).rejects.toThrow();
    expect(saved?.hashed?.parts).toBe(2);

    reads.length = 0;
    await uploadEncrypted({
      ...base,
      resume: saved,
      signal: new AbortController().signal,
      put: put(),
    });
    // Parts 1 and 2 hold the first plaintext chunk; it is neither read nor sealed again.
    expect(Math.min(...reads)).toBeGreaterThan(0);

    const stored = object();
    expect(completed.hash).toBe(createHash('sha256').update(stored).digest('hex'));
    const cipher = await openCipher(keys, stored.slice(0, HEADER_SIZE));
    const r = cipher.cipherRange(data.length, 0, data.length);
    const plain = await cipher.decryptRange(
      data.length,
      0,
      data.length,
      stored.slice(r.start, r.end),
    );
    expect(Buffer.from(plain).equals(Buffer.from(data))).toBe(true);
  }, 30_000);

  it('hashes from the first part when the saved parts are gone from storage', async () => {
    const keys = await accountKeys(crypto.getRandomValues(new Uint8Array(32)));
    const data = bytes(7 * 1024 * 1024);
    const { api, put, object, completed } = fakeApi();
    let saved: EncryptedResume | undefined;
    const base = {
      api,
      keys,
      source: { size: data.length, read: async (b: number, e: number) => data.slice(b, e) },
      name: 'a.bin',
      parentId: null,
      onCreated: (r: EncryptedResume) => (saved = r),
      onProgress: () => {},
    };
    const first = new AbortController();
    await expect(
      uploadEncrypted({ ...base, signal: first.signal, put: put({ at: 3, controller: first }) }),
    ).rejects.toThrow();
    // A checkpoint claiming more parts than storage has is ignored.
    await uploadEncrypted({
      ...base,
      resume: { ...saved!, hashed: { parts: 3, state: saved!.hashed!.state } },
      signal: new AbortController().signal,
      put: put(),
    });
    expect(completed.hash).toBe(createHash('sha256').update(object()).digest('hex'));
  }, 30_000);
});

describe('migration time left', () => {
  const progress = (over: Partial<MigrationProgress>): MigrationProgress => ({
    direction: 'encrypt',
    running: true,
    totalFiles: 3,
    doneFiles: 1,
    totalBytes: 8e9,
    doneBytes: 1e9,
    skippedFiles: 0,
    complete: false,
    ...over,
  });

  it('counts the current file and reads the speed as time', () => {
    expect(bytesLeft(progress({ currentBytes: 1e9 }))).toBe(6e9);
    expect(timeLeft(progress({ currentBytes: 1e9, bytesPerSecond: 3.6e6 }))).toBe('about 28 min');
    expect(timeLeft(progress({ bytesPerSecond: 1e6 }))).toBe('about 1 h 55 min');
    expect(timeLeft(progress({ bytesPerSecond: 7e6 / 6 }))).toBe('about 1 h 40 min');
    expect(timeLeft(progress({ doneBytes: 7.99e9, bytesPerSecond: 1e6 }))).toBe('about a minute');
    expect(timeLeft(progress({}))).toBeNull();
  });

  it('measures only bytes moved here, not parts a resumed file stored before', () => {
    vi.useFakeTimers();
    try {
      const meter = speedMeter();
      meter.file();
      meter.sample(4e9); // a resumed file's first report
      vi.advanceTimersByTime(10_000);
      meter.sample(4e9 + 36e6);
      expect(meter.rate()).toBeUndefined(); // too soon to tell
      vi.advanceTimersByTime(10_000);
      meter.sample(4e9 + 72e6);
      expect(meter.rate()).toBeCloseTo(3.6e6);
      meter.file();
      meter.sample(0);
      expect(meter.rate()).toBeCloseTo(3.6e6);
    } finally {
      vi.useRealTimers();
    }
  });
});
