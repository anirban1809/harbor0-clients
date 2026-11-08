import { crc32, deflateRawSync } from 'node:zlib';
import { expect, it } from 'vitest';
import {
  cleanPath,
  filesUnder,
  readZipDirectory,
  readZipFile,
  unreadable,
  zipTree,
  type ZipSource,
} from '../lib/zip-reader';
import {
  zipCentralHeader,
  zipDescriptor,
  zipEnd,
  zipLocalHeader,
} from '../../../packages/contracts/src/zip-format';

type Spec = {
  name: string;
  data?: string;
  deflate?: boolean;
  flags?: number;
  nameBytes?: Uint8Array;
};
/** A classic (non-ZIP64) archive, as most zip tools write one. */
function classicZip(specs: Spec[], comment = '') {
  const parts: Buffer[] = [];
  const central: Buffer[] = [];
  let offset = 0;
  for (const spec of specs) {
    const name = Buffer.from(spec.nameBytes ?? Buffer.from(spec.name));
    const plain = Buffer.from(spec.data ?? '');
    const body = spec.deflate ? deflateRawSync(plain) : plain;
    const method = spec.deflate ? 8 : 0;
    const flags = spec.flags ?? 0x800;
    const local = Buffer.alloc(30);
    local.writeUInt32LE(0x04034b50, 0);
    local.writeUInt16LE(20, 4);
    local.writeUInt16LE(flags, 6);
    local.writeUInt16LE(method, 8);
    local.writeUInt16LE((12 << 11) | (30 << 5), 10);
    local.writeUInt16LE(((2024 - 1980) << 9) | (3 << 5) | 15, 12);
    local.writeUInt32LE(crc32(plain), 14);
    local.writeUInt32LE(body.length, 18);
    local.writeUInt32LE(plain.length, 22);
    local.writeUInt16LE(name.length, 26);
    const header = Buffer.alloc(46);
    header.writeUInt32LE(0x02014b50, 0);
    header.writeUInt16LE(20, 4);
    header.writeUInt16LE(20, 6);
    header.writeUInt16LE(flags, 8);
    header.writeUInt16LE(method, 10);
    local.copy(header, 12, 10, 26);
    header.writeUInt16LE(name.length, 28);
    header.writeUInt32LE(offset, 42);
    central.push(header, name);
    parts.push(local, name, body);
    offset += local.length + name.length + body.length;
  }
  const directory = Buffer.concat(central);
  const end = Buffer.alloc(22);
  end.writeUInt32LE(0x06054b50, 0);
  end.writeUInt16LE(specs.length, 8);
  end.writeUInt16LE(specs.length, 10);
  end.writeUInt32LE(directory.length, 12);
  end.writeUInt32LE(offset, 16);
  end.writeUInt16LE(Buffer.byteLength(comment), 20);
  return new Uint8Array(Buffer.concat([...parts, directory, end, Buffer.from(comment)]));
}
function source(bytes: Uint8Array): ZipSource & { reads: [number, number][] } {
  const reads: [number, number][] = [];
  return {
    reads,
    size: bytes.length,
    read: async (begin, end) => {
      reads.push([begin, end]);
      return bytes.slice(begin, end);
    },
  };
}
const text = async (s: ZipSource, path: string) => {
  const file = zipTree(await readZipDirectory(s)).byPath.get(path)!;
  const { parts } = await readZipFile(s, file);
  return Buffer.concat(parts).toString();
};

it('lists and reads stored and deflated files', async () => {
  const long = 'harbor0 '.repeat(5000);
  const zip = source(
    classicZip(
      [
        { name: 'readme.txt', data: 'hello' },
        { name: 'docs/', data: '' },
        { name: 'docs/notes/long.txt', data: long, deflate: true },
      ],
      'archive comment',
    ),
  );
  const entries = await readZipDirectory(zip);
  expect(entries.map((e) => [e.path, e.directory, e.size, e.method])).toEqual([
    ['readme.txt', false, 5, 0],
    ['docs', true, 0, 0],
    ['docs/notes/long.txt', false, long.length, 8],
  ]);
  expect(entries[0].modified).toBe(new Date(2024, 2, 15, 12, 30).toISOString());
  expect(await text(zip, 'readme.txt')).toBe('hello');
  expect(await text(zip, 'docs/notes/long.txt')).toBe(long);
});

it('reads only the end of the archive to list it', async () => {
  const filler = 'x'.repeat(200_000);
  const bytes = classicZip([
    { name: 'big.bin', data: filler },
    { name: 'small.txt', data: 'hi' },
  ]);
  const zip = source(bytes);
  await readZipDirectory(zip);
  expect(zip.reads.every(([begin]) => begin >= bytes.length - 70_000)).toBe(true);
});

it('stops reading once a preview has enough', async () => {
  const zip = source(classicZip([{ name: 'a.txt', data: 'y'.repeat(20 * 1024 * 1024) }]));
  const file = (await readZipDirectory(zip))[0];
  const { parts, truncated } = await readZipFile(zip, file, 1024);
  expect(truncated).toBe(true);
  expect(parts.reduce((n, p) => n + p.length, 0)).toBeLessThan(file.size);
});

it('reads ZIP64 archives', async () => {
  const entries = [
    { path: 'photos/a.txt', size: 3, modified: '2026-01-02T03:04:05Z', directory: false },
    { path: 'photos/b.txt', size: 4, modified: '2026-01-02T03:04:05Z', directory: false },
  ];
  const data = ['abc', 'defg'];
  const parts: Uint8Array[] = [];
  const central: Uint8Array[] = [];
  let offset = 0;
  entries.forEach((entry, i) => {
    const body = new TextEncoder().encode(data[i]);
    const crc = crc32(body);
    central.push(zipCentralHeader(entry, crc, offset));
    for (const part of [zipLocalHeader(entry), body, zipDescriptor(entry.size, crc)]) {
      parts.push(part);
      offset += part.length;
    }
  });
  const size = central.reduce((n, p) => n + p.length, 0);
  const zip = source(
    new Uint8Array(Buffer.concat([...parts, ...central, zipEnd(2, offset, size)])),
  );
  expect((await readZipDirectory(zip)).map((e) => [e.path, e.size])).toEqual([
    ['photos/a.txt', 3],
    ['photos/b.txt', 4],
  ]);
  expect(await text(zip, 'photos/b.txt')).toBe('defg');
});

it('builds folders implied by paths and hides macOS resource forks', async () => {
  const tree = zipTree(
    await readZipDirectory(
      source(
        classicZip([
          { name: 'a/b/c.txt', data: '12' },
          { name: 'a/d.txt', data: '345' },
          { name: '__MACOSX/a/._d.txt', data: 'fork' },
          { name: '/../top.txt', data: 'x' },
        ]),
      ),
    ),
  );
  expect(tree.children.get('')).toEqual(['a', 'top.txt']);
  expect(tree.children.get('a')).toEqual(['a/b', 'a/d.txt']);
  expect(tree.folders.get('a')!.size).toBe(5);
  expect(filesUnder(tree, 'a').map((f) => f.path)).toEqual(['a/b/c.txt', 'a/d.txt']);
});

it('decodes CP437 names and refuses encrypted files', async () => {
  const [entry] = await readZipDirectory(
    source(classicZip([{ name: '', nameBytes: Uint8Array.of(0x8e, 0x2e, 0x74), flags: 1 }])),
  );
  expect(entry.path).toBe('Ä.t');
  expect(unreadable(entry)).toMatch(/password/);
});

it('rejects files that are not archives', async () => {
  await expect(
    readZipDirectory(source(new TextEncoder().encode('not a zip at all, sorry'))),
  ).rejects.toThrow(/not a ZIP/);
  expect(cleanPath('..\\a\\.\\b/')).toBe('a/b');
});
