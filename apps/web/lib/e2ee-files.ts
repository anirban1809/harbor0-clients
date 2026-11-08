import { ApiError, type ApiClient } from '@harbor/api-client';
import type { CompletedPart, DriveItem } from '@harbor/contracts';
import {
  CHUNK_SIZE,
  HEADER_SIZE,
  b64url,
  encryptName,
  encryptedSize,
  isEncryptedName,
  fromB64url,
  plainSize,
  type AccountKeys,
} from '../../../packages/api-client/src/e2ee';
import { TEXT_PREVIEW_BYTES, type TextPreview } from '../../../packages/api-client/src/preview';
import { requireKeys } from './e2ee-vault';
import { newCipher, openCipher, plainHasher, type Cipher } from './e2ee-worker';
import { guessMime } from './e2ee-transport';
import { streamUrl } from './media-stream';

/** Plaintext bytes [begin, end) of a stored file. */
export type PlainReader = {
  size: number;
  read: (begin: number, end: number) => Promise<Uint8Array>;
};

/**
 * How long a download link is used before a new one is signed. Links last 5 minutes (the API's
 * storage presign); age is timed on this clock, so a skewed system clock can't expire them early.
 */
export const LINK_REUSE_MS = 4 * 60_000;

/** Byte ranges of a download link, signing a new link before the old one expires. */
export function rangeFetcher(first: string, renew: () => Promise<string>) {
  let url = first;
  let signedAt = Date.now();
  // Parallel reads that find the link old share one renewal.
  let renewing: Promise<void> | null = null;
  const refresh = () =>
    (renewing ??= renew()
      .then((next) => {
        url = next;
        signedAt = Date.now();
      })
      .finally(() => {
        renewing = null;
      }));
  return async (begin: number, end: number, signal?: AbortSignal) => {
    if (end <= begin) return new Uint8Array();
    for (let attempt = 0; ; attempt++) {
      if (Date.now() - signedAt >= LINK_REUSE_MS) await refresh();
      let response: Response;
      try {
        response = await fetch(url, {
          headers: { Range: `bytes=${begin}-${end - 1}` },
          signal,
          credentials: 'omit',
          referrerPolicy: 'no-referrer',
        });
      } catch (e) {
        // Storage refuses an expired link without CORS headers, so the browser reports it as a
        // network error rather than a 403: sign a new link and try once more.
        if (signal?.aborted || attempt > 0) throw e;
        await refresh();
        continue;
      }
      if ((response.status === 403 || response.status === 400) && attempt === 0) {
        await refresh();
        continue;
      }
      if (!response.ok) throw new Error(`The download failed (${response.status}). Try again.`);
      const bytes = new Uint8Array(await response.arrayBuffer());
      // Storage that ignores Range sends the whole file.
      return response.status === 200 ? bytes.subarray(begin, end) : bytes;
    }
  };
}

/** Opens an encrypted file version for reading, decrypting only the ranges asked for. */
export async function openEncrypted(
  api: ApiClient,
  input: { driveItemId: string; versionId?: string; preview?: boolean },
  keys: AccountKeys = requireKeys(),
): Promise<PlainReader> {
  const link = await api.download(input);
  const fetchRange = rangeFetcher(
    link.downloadUrl,
    async () => (await api.download(input)).downloadUrl,
  );
  const cipher = await openCipher(keys, await fetchRange(0, HEADER_SIZE));
  const size = plainSize(link.sizeBytes);
  return {
    size,
    read: async (begin, end) => {
      end = Math.min(end, size);
      if (end <= begin) return new Uint8Array();
      const r = cipher.cipherRange(size, begin, end);
      return cipher.decryptRange(size, begin, end, await fetchRange(r.start, r.end));
    },
  };
}

/**
 * An encrypted file read through a download link already in hand, decrypting only the chunks a
 * read touches and keeping the last few: for readers that ask for many small ranges, such as a
 * video's index when a frame of it is drawn.
 */
export async function openEncryptedLink(
  url: string,
  storedSize: number,
  renew: () => Promise<string>,
  signal?: AbortSignal,
  keys: AccountKeys = requireKeys(),
): Promise<PlainReader> {
  const fetchRange = rangeFetcher(url, renew);
  const cipher = await openCipher(keys, await fetchRange(0, HEADER_SIZE, signal));
  const size = plainSize(storedSize);
  const chunks = new Map<number, Promise<Uint8Array>>();
  const chunk = (index: number) => {
    let found = chunks.get(index);
    if (!found) {
      const begin = index * CHUNK_SIZE;
      const end = Math.min(size, begin + CHUNK_SIZE);
      const r = cipher.cipherRange(size, begin, end);
      found = fetchRange(r.start, r.end, signal).then((stored) =>
        cipher.decryptRange(size, begin, end, stored),
      );
      found.catch(() => chunks.delete(index));
      chunks.set(index, found);
      // The first and last chunks hold most indexes; keep them and the latest two.
      for (const key of chunks.keys())
        if (chunks.size > 4 && key !== index && key !== 0) chunks.delete(key);
    } else {
      chunks.delete(index);
      chunks.set(index, found);
    }
    return found;
  };
  return {
    size,
    read: async (begin, end) => {
      end = Math.min(end, size);
      if (end <= begin) return new Uint8Array();
      const first = Math.floor(begin / CHUNK_SIZE);
      const last = Math.floor((end - 1) / CHUNK_SIZE);
      const parts = await Promise.all(
        Array.from({ length: last - first + 1 }, (_, n) => chunk(first + n)),
      );
      const out = new Uint8Array(end - begin);
      let at = 0;
      parts.forEach((plain, n) => {
        const offset = n === 0 ? begin - first * CHUNK_SIZE : 0;
        const piece = plain.subarray(offset, Math.min(plain.length, offset + (end - begin - at)));
        out.set(piece, at);
        at += piece.length;
      });
      return out;
    },
  };
}

/** Reads a whole file in large windows, for downloads and previews. */
export async function readAll(
  reader: PlainReader,
  onProgress?: (loaded: number) => void,
  signal?: AbortSignal,
  write?: (bytes: Uint8Array) => Promise<void>,
) {
  const window = 4 * CHUNK_SIZE;
  const parts: Uint8Array<ArrayBuffer>[] = [];
  for (let at = 0; at < reader.size || (at === 0 && !parts.length); at += window) {
    signal?.throwIfAborted();
    const bytes = (await reader.read(at, Math.min(reader.size, at + window))).slice();
    if (write) await write(bytes);
    else parts.push(bytes);
    onProgress?.(Math.min(reader.size, at + window));
    if (reader.size === 0) break;
  }
  return parts;
}

export const isEncrypted = (item: Pick<DriveItem, 'encrypted'>) => item.encrypted === true;

/**
 * A decrypted preview: the first megabyte of a text file, a PDF as decrypted ranges that
 * pdf.js reads page by page, video and audio streamed (lib/media-stream.ts), and other files
 * whole, as an object URL.
 */
export async function encryptedPreview(api: ApiClient, item: DriveItem, kind: string | null) {
  const reader = await openEncrypted(api, { driveItemId: item.id, preview: true });
  if (kind === 'text') {
    const bytes = await reader.read(0, Math.min(reader.size, TEXT_PREVIEW_BYTES));
    const text = new TextDecoder().decode(bytes);
    if (text.includes('\0'))
      throw new Error('This file contains binary data and cannot be shown as text.');
    return { text, truncated: reader.size > TEXT_PREVIEW_BYTES } satisfies TextPreview;
  }
  if (kind === 'pdf') return { read: reader.read, size: reader.size };
  const type = item.mimeType ?? guessMime(item.name);
  // Video and audio play as they are read; without a media worker they are decrypted whole.
  if (kind === 'video' || kind === 'audio') {
    const url = await streamUrl(reader, type);
    if (url) return { url };
  }
  const blob = new Blob(await readAll(reader), { type });
  return { url: URL.createObjectURL(blob) };
}

// Larger downloads stream straight to disk where the browser can; others are built in memory.
const STREAM_ABOVE = 256 * 1024 * 1024;
type SavePicker = (options: { suggestedName: string }) => Promise<{
  createWritable: () => Promise<{
    write: (b: Uint8Array) => Promise<void>;
    close: () => Promise<void>;
  }>;
}>;

/** Downloads and decrypts a file in the browser, then saves it under its real name. */
export async function saveEncrypted(
  api: ApiClient,
  item: Pick<DriveItem, 'id' | 'name' | 'mimeType'>,
  versionId?: string,
  onProgress?: (loaded: number, total: number) => void,
) {
  const reader = await openEncrypted(api, { driveItemId: item.id, versionId });
  const picker = (window as unknown as { showSaveFilePicker?: SavePicker }).showSaveFilePicker;
  if (picker && reader.size > STREAM_ABOVE) {
    let handle;
    try {
      handle = await picker({ suggestedName: item.name });
    } catch {
      return false;
    }
    const out = await handle.createWritable();
    await readAll(
      reader,
      (n) => onProgress?.(n, reader.size),
      undefined,
      (b) => out.write(b),
    );
    await out.close();
    return true;
  }
  const blob = new Blob(await readAll(reader, (n) => onProgress?.(n, reader.size)), {
    type: item.mimeType ?? guessMime(item.name),
  });
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = item.name;
  document.body.append(a);
  a.click();
  a.remove();
  setTimeout(() => URL.revokeObjectURL(url), 60_000);
  return true;
}

export type EncryptedUploadInput = {
  api: ApiClient;
  keys: AccountKeys;
  source: PlainReader;
  name: string;
  parentId: string | null;
  /**
   * A new version of this item instead of a new file. Re-encrypting or decrypting keeps the
   * time its content last changed, which Photos sorts by.
   */
  replace?: Pick<DriveItem, 'id' | 'revision'> & { modifiedAt?: string | null };
  signal: AbortSignal;
  onProgress: (storedLoaded: number, storedTotal: number) => void;
  /**
   * Saves what resuming after a reload needs: called once the upload exists, then again as
   * parts are stored, with the hash so far.
   */
  onCreated?: (resume: EncryptedResume) => void;
  resume?: EncryptedResume;
  /** Upload the bytes as they are, unencrypted: decrypting while encryption is turned off. */
  plain?: boolean;
  /** Stored-byte PUT of one part; returns its ETag. */
  put: (
    url: string,
    data: Blob,
    signal: AbortSignal,
    onProgress: (n: number) => void,
  ) => Promise<string>;
};
export type EncryptedResume = {
  id: string;
  partSizeBytes: number;
  header: string;
  /** The stored bytes' SHA-256 through part `parts`, every one of them stored. */
  hashed?: { parts: number; state: string };
};

/**
 * Uploads a file encrypted. Parts are encrypted in order so the stored bytes' SHA-256 is known
 * at the end without reading the file twice; up to two parts upload while the next is sealed.
 * The hash is saved as parts are stored, so a resumed upload starts after the last part it
 * saved. Encryption is deterministic for a header, so parts stored after that are re-sealed
 * only to hash them.
 */
export async function uploadEncrypted(input: EncryptedUploadInput): Promise<DriveItem> {
  const { api, keys, source, signal, plain } = input;
  const total = plain ? source.size : encryptedSize(source.size);
  let resume = input.resume;
  let cipher: Cipher | undefined;
  if (resume) {
    try {
      const status = await api.request(`/v1/uploads/${resume.id}`);
      if (status.upload.state === 'COMPLETED') return status.upload.item;
      if (!['UPLOADING', 'COMPLETING'].includes(status.upload.state)) resume = undefined;
    } catch {
      resume = undefined;
    }
  }
  // A resumed upload asks which parts storage already has; a new one has none.
  const resumed = !!resume;
  if (resume) cipher = plain ? undefined : await openCipher(keys, fromB64url(resume.header));
  else {
    cipher = plain ? undefined : await newCipher(keys);
    const created = await api.createUpload({
      operationId: crypto.randomUUID(),
      parentId: input.parentId,
      name: plain || isEncryptedName(input.name) ? input.name : await encryptName(keys, input.name),
      sizeBytes: total,
      mimeType: plain ? guessMime(input.name) : 'application/octet-stream',
      encrypted: !plain,
      ...(input.replace
        ? { driveItemId: input.replace.id, baseRevision: input.replace.revision }
        : {}),
      ...(input.replace?.modifiedAt ? { modifiedAt: input.replace.modifiedAt } : {}),
    });
    resume = {
      id: created.upload.id,
      partSizeBytes: created.upload.partSizeBytes,
      header: cipher ? b64url(cipher.header) : '',
    };
    input.onCreated?.(resume);
  }
  const session = resume;
  const status = resumed ? await api.request(`/v1/uploads/${session.id}`) : { parts: [] };
  const done = new Map<number, string>(
    (status.parts ?? []).map((p: CompletedPart) => [p.partNumber, p.etag]),
  );
  const count = Math.max(1, Math.ceil(total / session.partSizeBytes));
  // Encrypted parts are hashed in the worker as they are sealed; plain ones are sent there.
  const hasher = cipher ?? (await plainHasher());
  // Parts stored and hashed before are neither read nor sealed again.
  let first = 1;
  const hashed = resumed ? session.hashed : undefined;
  if (hashed && hashed.parts <= count && everyPart(hashed.parts, (n) => done.has(n))) {
    try {
      await hasher.loadHash(hashed.state);
      first = hashed.parts + 1;
    } catch {
      // A state this build can't read: hash from the first part.
    }
  }
  // The hash after each part sealed here, kept until every part through it is stored.
  const states = new Map<number, string>();
  let saved = first - 1;
  const checkpoint = () => {
    let through = saved;
    while (done.has(through + 1)) through++;
    while (through > saved && !states.has(through)) through--;
    if (through <= saved) return;
    saved = through;
    input.onCreated?.({ ...session, hashed: { parts: through, state: states.get(through)! } });
    for (const n of states.keys()) if (n <= through) states.delete(n);
  };
  const inflight = new Map<number, number>();
  const partBytes = (n: number) =>
    Math.min(session.partSizeBytes, total - (n - 1) * session.partSizeBytes);
  const report = () => {
    let loaded = 0;
    for (const n of done.keys()) loaded += partBytes(n);
    for (const b of inflight.values()) loaded += b;
    input.onProgress(Math.min(total, loaded), total);
  };
  const running = new Set<Promise<void>>();
  let failure: unknown;
  for (let n = first; n <= count; n++) {
    signal.throwIfAborted();
    if (failure) throw failure;
    const start = (n - 1) * session.partSizeBytes;
    const bytes = cipher
      ? await cipher.encryptedRange(source.size, start, start + partBytes(n), source.read, true)
      : await hasher.hash(await source.read(start, start + partBytes(n)));
    states.set(n, await hasher.saveHash());
    if (done.has(n)) {
      checkpoint();
      continue;
    }
    while (running.size >= 2) await Promise.race(running);
    if (failure) throw failure;
    const job = (async () => {
      for (let attempt = 0; ; attempt++) {
        try {
          const { parts } = await api.parts(session.id, [n]);
          const etag = await input.put(
            parts[0].uploadUrl,
            new Blob([bytes.slice()]),
            signal,
            (b) => {
              inflight.set(n, b);
              report();
            },
          );
          inflight.delete(n);
          done.set(n, etag);
          checkpoint();
          report();
          return;
        } catch (e) {
          if (signal.aborted || attempt === 2 || (e instanceof ApiError && e.status < 500)) throw e;
          await new Promise((r) => setTimeout(r, 500 * 2 ** attempt));
        }
      }
    })().catch((e) => {
      failure ??= e;
    });
    running.add(job);
    void job.finally(() => running.delete(job));
  }
  await Promise.all(running);
  if (failure) throw failure;
  const parts = [...done.entries()]
    .sort((a, b) => a[0] - b[0])
    .map(([partNumber, etag]) => ({ partNumber, etag }));
  const contentHash = await hasher.digest();
  hasher.close();
  const { item } = await api.complete(session.id, parts, contentHash);
  return item;
}
const everyPart = (count: number, test: (n: number) => boolean) => {
  for (let n = 1; n <= count; n++) if (!test(n)) return false;
  return true;
};

/** A local file as a plaintext source. */
export const fileSource = (file: Blob): PlainReader => ({
  size: file.size,
  read: async (b, e) => new Uint8Array(await file.slice(b, e).arrayBuffer()),
});
