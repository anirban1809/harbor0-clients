import { describe, expect, it } from 'vitest';
import { mediaResponse, mediaTarget, parseRange } from '../src/media-protocol';

const file = (size: number) => {
  const bytes = Uint8Array.from({ length: size }, (_, i) => i % 251);
  const reads: [number, number][] = [];
  return {
    reads,
    reader: {
      size,
      read: async (begin: number, end: number) => {
        reads.push([begin, end]);
        return bytes.slice(begin, Math.min(end, size));
      },
    },
    bytes,
  };
};

describe('media protocol', () => {
  it('parses single byte ranges', () => {
    expect(parseRange(null, 100)).toBeNull();
    expect(parseRange('bytes=0-', 100)).toEqual([0, 100]);
    expect(parseRange('bytes=10-19', 100)).toEqual([10, 20]);
    expect(parseRange('bytes=90-500', 100)).toEqual([90, 100]);
    expect(parseRange('bytes=-30', 100)).toEqual([70, 100]);
    expect(parseRange('bytes=100-', 100)).toBe('invalid');
    expect(parseRange('bytes=0-1,5-6', 100)).toBeNull();
  });

  it('answers only previews, with a known media type', () => {
    expect(mediaTarget('harbor-media://preview/abc?type=video%2Fmp4')).toEqual({
      driveItemId: 'abc',
      type: 'video/mp4',
    });
    expect(mediaTarget('harbor-media://preview/abc?type=text%2Fhtml')?.type).toBe(
      'application/octet-stream',
    );
    expect(mediaTarget('harbor-media://other/abc')).toBeNull();
    expect(mediaTarget('harbor-media://preview/')).toBeNull();
  });

  it('streams a range, reading whole blocks once', async () => {
    const { reader, reads, bytes } = file(10 * 1024 * 1024);
    const start = 5 * 1024 * 1024 - 10;
    const response = mediaResponse(reader, 'video/mp4', `bytes=${start}-${start + 99}`);
    expect(response.status).toBe(206);
    expect(response.headers.get('Content-Range')).toBe(
      `bytes ${start}-${start + 99}/${reader.size}`,
    );
    expect(new Uint8Array(await response.arrayBuffer())).toEqual(bytes.slice(start, start + 100));
    // Block 1 (of 4 MiB) holds the range; block 2 is read ahead of it.
    const blocks = reads.map(([begin]) => begin / (4 * 1024 * 1024)).sort();
    expect(blocks).toEqual([1, 2]);
    const whole = mediaResponse(reader, 'video/mp4', null);
    expect(whole.status).toBe(200);
    expect(whole.headers.get('Content-Length')).toBe(String(reader.size));
    expect(mediaResponse(reader, 'video/mp4', 'bytes=99999999-').status).toBe(416);
  });
});
