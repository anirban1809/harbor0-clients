import { blockReader } from '../../web/lib/media-stream';
import type { PlainReader } from './e2ee-reader';

/**
 * Encrypted video and audio previews play from harbor-media://preview/<driveItemId>?type=<mime>,
 * answered here a range at a time from the decrypting reader PDF previews use: the player asks
 * for the ranges it needs as it plays and seeks, so it starts on the first few megabytes rather
 * than once the whole file is decrypted.
 */
export const MEDIA_SCHEME = 'harbor-media';
/** Bytes handed to the player at a time. */
const PIECE = 1024 * 1024;

/** The preview a harbor-media address is for, or null for anything else. */
export function mediaTarget(address: string) {
  const url = new URL(address);
  if (url.host !== 'preview') return null;
  const driveItemId = decodeURIComponent(url.pathname.slice(1));
  if (!driveItemId || driveItemId.length > 256 || driveItemId.includes('/')) return null;
  const type = url.searchParams.get('type') ?? '';
  return {
    driveItemId,
    type: /^(video|audio)\/[\w.+-]+$/.test(type) ? type : 'application/octet-stream',
  };
}

const cached = new WeakMap<PlainReader, PlainReader['read']>();
/** A range of the file as an HTTP answer: 206 for a "bytes=" range, 200 for the whole file. */
export function mediaResponse(reader: PlainReader, type: string, rangeHeader: string | null) {
  let read = cached.get(reader);
  if (!read) cached.set(reader, (read = blockReader(reader)));
  const size = reader.size;
  const headers: Record<string, string> = {
    'Content-Type': type,
    'Accept-Ranges': 'bytes',
    'Cache-Control': 'no-store',
  };
  const range = parseRange(rangeHeader, size);
  if (range === 'invalid')
    return new Response(null, {
      status: 416,
      headers: { ...headers, 'Content-Range': `bytes */${size}` },
    });
  const [start, end] = range ?? [0, size];
  let at = start;
  const body = new ReadableStream<Uint8Array>(
    {
      async pull(controller) {
        if (at >= end) return controller.close();
        const bytes = await read(at, Math.min(end, at + PIECE));
        if (!bytes.length) return controller.error(new Error('The file ended early.'));
        at += bytes.length;
        controller.enqueue(bytes);
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
export function parseRange(
  header: string | null,
  size: number,
): [number, number] | null | 'invalid' {
  const match = header && /^bytes=(\d*)-(\d*)$/.exec(header.trim());
  if (!match || (!match[1] && !match[2])) return null;
  const [start, end] = match[1]
    ? [Number(match[1]), match[2] ? Math.min(size, Number(match[2]) + 1) : size]
    : [Math.max(0, size - Number(match[2])), size];
  return start < end ? [start, end] : 'invalid';
}
