import { crc32 } from 'node:zlib';
import { expect, it } from 'vitest';
import { zipFolder } from '../lib/zip-folder';
import { readZipDirectory, readZipFile, type ZipSource } from '../lib/zip-reader';

async function source(parts: BlobPart[]): Promise<ZipSource> {
  const bytes = new Uint8Array(await new Blob(parts).arrayBuffer());
  return { size: bytes.length, read: async (start, end) => bytes.slice(start, end) };
}
async function save(
  folder: ReturnType<typeof zipFolder>['root'],
  name: string,
  ...chunks: string[]
) {
  const out = await (await folder.getFileHandle(name, { create: true })).createWritable();
  for (const chunk of chunks) await out.write(new TextEncoder().encode(chunk));
  await out.close();
}

it('keeps the folders and files saved into it, with their checksums', async () => {
  const zip = zipFolder('Documents');
  await save(zip.root, 'Lease.pdf', 'lease ', 'terms');
  const taxes = await zip.root.getDirectoryHandle('Taxes 2025', { create: true });
  await save(taxes, 'W-2.pdf', 'wages');
  await zip.root.getDirectoryHandle('Empty', { create: true });
  const zipSource = await source(zip.finish());
  const entries = await readZipDirectory(zipSource);
  expect(entries.map((e) => [e.path, e.directory])).toEqual([
    ['Documents', true],
    ['Documents/Lease.pdf', false],
    ['Documents/Taxes 2025', true],
    ['Documents/Taxes 2025/W-2.pdf', false],
    ['Documents/Empty', true],
  ]);
  const lease = entries[1];
  const { parts } = await readZipFile(zipSource, lease);
  expect(new TextDecoder().decode(Buffer.concat(parts))).toBe('lease terms');
  // The checksum after the data matches it, so unzip tools accept the file.
  const header = 30 + new TextEncoder().encode(lease.path).length + 20;
  const descriptor = await zipSource.read(
    lease.offset + header + lease.size,
    lease.offset + header + lease.size + 8,
  );
  const v = new DataView(descriptor.buffer, descriptor.byteOffset);
  expect(v.getUint32(0, true)).toBe(0x08074b50);
  expect(v.getUint32(4, true)).toBe(crc32('lease terms'));
});

it('acts like a folder on disk: taken names, case and removed files', async () => {
  const zip = zipFolder('Files');
  await save(zip.root, 'a.pdf', 'one');
  await expect(zip.root.getFileHandle('A.PDF')).resolves.toBeDefined();
  await expect(zip.root.getFileHandle('b.pdf')).rejects.toMatchObject({ name: 'NotFoundError' });
  await zip.root.getDirectoryHandle('Sub', { create: true });
  await expect(zip.root.getFileHandle('sub')).rejects.toMatchObject({ name: 'TypeMismatchError' });
  await expect(zip.root.getDirectoryHandle('a.pdf')).rejects.toMatchObject({
    name: 'TypeMismatchError',
  });
  // A file abandoned part way is left out.
  const out = await (await zip.root.getFileHandle('c.pdf', { create: true })).createWritable();
  await out.write(new Uint8Array([1, 2, 3]));
  await out.abort();
  await zip.root.removeEntry('c.pdf');
  await expect(zip.root.getFileHandle('c.pdf')).rejects.toMatchObject({ name: 'NotFoundError' });
  const entries = await readZipDirectory(await source(zip.finish()));
  expect(entries.map((e) => e.path)).toEqual(['Files', 'Files/a.pdf', 'Files/Sub']);
});
