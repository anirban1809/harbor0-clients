import { zipCentralHeader, zipDescriptor, zipEnd, zipLocalHeader } from '@harbor/contracts';
import type { SaveFolder } from './e2ee-files';

const CRC_TABLE = Array.from({ length: 256 }, (_, n) => {
  let c = n;
  for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
  return c >>> 0;
});
function crc32(parts: Uint8Array[]) {
  let crc = 0xffffffff;
  for (const bytes of parts)
    for (let i = 0; i < bytes.length; i++) crc = CRC_TABLE[(crc ^ bytes[i]) & 0xff] ^ (crc >>> 8);
  return (crc ^ 0xffffffff) >>> 0;
}

const named = (name: string) => Object.assign(new Error(name), { name });

/**
 * A folder kept in memory that becomes one ZIP, for browsers that can't save into a folder on
 * disk (iPhone, iPad, Safari, Firefox). There every file would be its own download, and iPhone
 * asks about each one and cancels whatever the page is fetching when it starts.
 */
export function zipFolder(name: string) {
  const parts: Uint8Array[] = [];
  const central: Uint8Array[] = [];
  const modified = new Date().toISOString();
  let offset = 0;
  const push = (bytes: Uint8Array) => {
    parts.push(bytes);
    offset += bytes.length;
  };
  const add = (path: string, directory: boolean, data: Uint8Array[] = []) => {
    const entry = { path, directory, modified, size: data.reduce((n, b) => n + b.length, 0) };
    const crc = directory ? 0 : crc32(data);
    const start = offset;
    push(zipLocalHeader(entry));
    data.forEach(push);
    push(zipDescriptor(entry.size, crc));
    central.push(zipCentralHeader(entry, crc, start));
  };
  const folder = (path: string): SaveFolder => {
    // Keyed without case: names that differ only in case would overwrite each other once unpacked.
    const entries = new Map<string, SaveFolder | 'file'>();
    return {
      name: path.slice(path.lastIndexOf('/') + 1),
      async getFileHandle(name, options) {
        const found = entries.get(name.toLowerCase());
        if (found && found !== 'file') throw named('TypeMismatchError');
        if (!found && !options?.create) throw named('NotFoundError');
        entries.set(name.toLowerCase(), 'file');
        return {
          async createWritable() {
            const data: Uint8Array[] = [];
            return {
              async write(bytes) {
                data.push(bytes.slice());
              },
              async close() {
                add(`${path}/${name}`, false, data);
              },
              async abort() {},
            };
          },
        };
      },
      async getDirectoryHandle(name, options) {
        const found = entries.get(name.toLowerCase());
        if (found === 'file') throw named('TypeMismatchError');
        if (found) return found;
        if (!options?.create) throw named('NotFoundError');
        add(`${path}/${name}`, true);
        const made = folder(`${path}/${name}`);
        entries.set(name.toLowerCase(), made);
        return made;
      },
      async removeEntry(name) {
        entries.delete(name.toLowerCase());
      },
    };
  };
  add(name, true);
  return {
    root: folder(name),
    /** The finished ZIP, in parts. */
    finish() {
      const directoryOffset = offset;
      central.forEach(push);
      push(zipEnd(central.length, directoryOffset, offset - directoryOffset));
      return parts as BlobPart[];
    },
  };
}
