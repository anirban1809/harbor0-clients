import type { ReadRange } from './file-preview';

/**
 * Video and audio the page reads itself (an encrypted file, decrypted a chunk at a time), played
 * from an address the media worker (public/media-sw.js) answers. The player asks for the ranges
 * it needs as it plays and seeks, so it starts once the first few megabytes are in rather than
 * once the whole file is, and holds only a few chunks at a time.
 */

type Source = { size: number; type: string; read: ReadRange };

/** Bytes read and kept at a time: one encrypted chunk, which is decrypted whole anyway. */
const BLOCK = 4 * 1024 * 1024;
/** Streams kept answerable: a preview reopened from the preview cache plays from the same one. */
const KEPT = 4;
const sources = new Map<string, Source>();
let script = '/media-sw.js';
let ready: Promise<boolean> | undefined;
let ping: ReturnType<typeof setInterval> | undefined;

/**
 * The worker script registered at the site's root. A site whose root already has a worker (the
 * link pages' download worker) has that one load the media worker's handler instead.
 */
export function setMediaWorker(path: string) {
  script = path;
}

/** An address that plays `source`, or null where the browser runs no worker for the page. */
export async function streamUrl(
  source: { size: number; read: ReadRange },
  type: string,
): Promise<string | null> {
  if (!(await workerReady())) return null;
  const id = crypto.randomUUID();
  sources.set(id, { size: source.size, type, read: blockReader(source) });
  while (sources.size > KEPT) sources.delete(sources.keys().next().value!);
  // The worker may be stopped while idle between reads; messages keep it running.
  ping ??= setInterval(
    () => navigator.serviceWorker.controller?.postMessage({ type: 'ping' }),
    10_000,
  );
  return `/__media/${id}`;
}

function workerReady() {
  return (ready ??= (async () => {
    if (typeof navigator === 'undefined' || !('serviceWorker' in navigator)) return false;
    try {
      const workers = navigator.serviceWorker;
      workers.addEventListener('message', answer);
      workers.startMessages();
      await workers.register(script, { scope: '/' });
      // A worker installed just now takes over the page (clients.claim) a moment later; one
      // the browser won't run never does, and `ready` never settles.
      if (!workers.controller)
        await new Promise<void>((resolve) => {
          workers.addEventListener('controllerchange', () => resolve(), { once: true });
          setTimeout(resolve, 3000);
        });
      return !!workers.controller;
    } catch {
      return false;
    }
  })());
}

/** Answers the worker's reads for one request, over the port it sends. */
function answer(event: MessageEvent) {
  const data = event.data as { type?: string; id?: string } | null;
  const port = event.ports[0];
  if (data?.type !== 'harbor-media' || !port) return;
  const source = sources.get(data.id ?? '');
  if (!source) {
    port.postMessage({ missing: true });
    port.close();
    return;
  }
  port.postMessage({ size: source.size, type: source.type });
  port.onmessage = async ({ data: ask }) => {
    if (ask.type === 'close') return port.close();
    try {
      const bytes = (await source.read(ask.begin, ask.end)).slice();
      port.postMessage({ bytes: bytes.buffer }, [bytes.buffer]);
    } catch (e) {
      port.postMessage({ error: (e as Error)?.message ?? 'The file could not be read.' });
    }
  };
}

/**
 * `source` read a block at a time (the desktop app's media protocol reads this way too), keeping the first (which holds most videos' index) and the
 * latest three: players ask for many small ranges around the same places. Each read also starts
 * on the block after it, so playback doesn't wait at every block's end.
 */
export function blockReader(source: { size: number; read: ReadRange }): ReadRange {
  const kept = new Map<number, Promise<Uint8Array>>();
  const block = (index: number) => {
    let found = kept.get(index);
    if (found) kept.delete(index);
    else {
      const begin = index * BLOCK;
      found = source.read(begin, Math.min(source.size, begin + BLOCK));
      found.catch(() => kept.delete(index));
    }
    kept.set(index, found);
    for (const key of kept.keys())
      if (kept.size > 4 && key !== index && key !== 0) kept.delete(key);
    return found;
  };
  return async (begin, end) => {
    end = Math.min(end, source.size);
    if (end <= begin) return new Uint8Array();
    const first = Math.floor(begin / BLOCK);
    const last = Math.floor((end - 1) / BLOCK);
    if ((last + 1) * BLOCK < source.size) void block(last + 1).catch(() => {});
    const out = new Uint8Array(end - begin);
    for (let index = first; index <= last; index++) {
      const plain = await block(index);
      const from = Math.max(begin, index * BLOCK);
      const to = Math.min(end, (index + 1) * BLOCK);
      out.set(plain.subarray(from - index * BLOCK, to - index * BLOCK), from - begin);
    }
    return out;
  };
}
