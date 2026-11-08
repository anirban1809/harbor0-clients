/* global self, ReadableStream, MessageChannel */
// Plays video and audio the page decrypts itself (lib/media-stream.ts) from /__media/<id>. Each
// request the player makes is answered as a range: the worker asks the page that made it for the
// bytes a piece at a time, only as the player reads them, so playback starts on the first few
// megabytes and seeking reads from the new place. The page keeps the streams, so a worker stopped
// and started again in between still finds them. The link pages' download worker loads this too.
const MEDIA_PIECE_BYTES = 1024 * 1024;

self.addEventListener('install', () => self.skipWaiting());
self.addEventListener('activate', (event) => event.waitUntil(self.clients.claim()));

self.addEventListener('fetch', (event) => {
  const url = new URL(event.request.url);
  if (url.origin !== self.location.origin || !url.pathname.startsWith('/__media/')) return;
  event.respondWith(
    mediaResponse(event, url.pathname.slice('/__media/'.length)).catch(
      () => new Response('This preview could not be read.', { status: 502 }),
    ),
  );
});

async function mediaResponse(event, id) {
  const client = event.clientId ? await self.clients.get(event.clientId) : null;
  if (!client) return new Response('This preview has ended.', { status: 404 });
  const channel = new MessageChannel();
  const port = channel.port1;
  // The page answers in order: the file's size and type first, then each read.
  const answers = [];
  const waiting = [];
  port.onmessage = ({ data }) => (waiting.length ? waiting.shift()(data) : answers.push(data));
  const next = () =>
    answers.length ? Promise.resolve(answers.shift()) : new Promise((r) => waiting.push(r));
  client.postMessage({ type: 'harbor-media', id }, [channel.port2]);
  const head = await next();
  if (head.missing) return new Response('This preview has ended.', { status: 404 });
  const size = head.size;
  const headers = {
    'Content-Type': head.type,
    'Accept-Ranges': 'bytes',
    'Cache-Control': 'no-store',
  };
  const range = parseRange(event.request.headers.get('Range'), size);
  if (range === 'invalid') {
    port.close();
    return new Response(null, {
      status: 416,
      headers: { ...headers, 'Content-Range': `bytes */${size}` },
    });
  }
  const [start, end] = range ?? [0, size];
  let at = start;
  const body = new ReadableStream(
    {
      async pull(controller) {
        if (at >= end) {
          port.postMessage({ type: 'close' });
          return controller.close();
        }
        const stop = Math.min(end, at + MEDIA_PIECE_BYTES);
        port.postMessage({ type: 'read', begin: at, end: stop });
        const answer = await next();
        if (answer.error || !answer.bytes?.byteLength) {
          port.postMessage({ type: 'close' });
          return controller.error(new Error(answer.error || 'The file ended early.'));
        }
        at += answer.bytes.byteLength;
        controller.enqueue(new Uint8Array(answer.bytes));
      },
      // The player seeks elsewhere, or the preview closes.
      cancel() {
        port.postMessage({ type: 'close' });
      },
    },
    { highWaterMark: 0 },
  );
  headers['Content-Length'] = String(end - start);
  if (!range) return new Response(body, { status: 200, headers });
  headers['Content-Range'] = `bytes ${start}-${end - 1}/${size}`;
  return new Response(body, { status: 206, headers });
}

/** [start, end) of a single-range "bytes=" header, null without one, or 'invalid'. */
function parseRange(header, size) {
  const match = header && /^bytes=(\d*)-(\d*)$/.exec(header.trim());
  if (!match || (!match[1] && !match[2])) return null;
  let start;
  let end;
  if (!match[1]) {
    start = Math.max(0, size - Number(match[2]));
    end = size;
  } else {
    start = Number(match[1]);
    end = match[2] ? Math.min(size, Number(match[2]) + 1) : size;
  }
  return start < end ? [start, end] : 'invalid';
}
