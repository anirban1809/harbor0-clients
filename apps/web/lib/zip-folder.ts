import { zipCentralHeader, zipDescriptor, zipEnd, zipLocalHeader } from '@harbor/contracts';
import { saveBlob, type SaveFolder } from './e2ee-files';

const CRC_TABLE = Array.from({ length: 256 }, (_, n) => {
  let c = n;
  for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
  return c >>> 0;
});
/** Continues a CRC-32 over `bytes`; start from 0xffffffff and finish with `~crc >>> 0`. */
function crc32(crc: number, bytes: Uint8Array) {
  for (let i = 0; i < bytes.length; i++) crc = CRC_TABLE[(crc ^ bytes[i]) & 0xff] ^ (crc >>> 8);
  return crc;
}

const named = (name: string) => Object.assign(new Error(name), { name });

/**
 * A folder that becomes one ZIP, for browsers that can't save into a folder on disk. There every
 * file would be its own download, and iPhone asks about each one and cancels whatever the page is
 * fetching when it starts. The ZIP goes to `out` as it's built, a file on disk where the browser
 * allows it, else it's kept in memory. Files are written one at a time.
 */
export function zipFolder(name: string, out?: (bytes: Uint8Array) => Promise<void>) {
  const parts: Uint8Array[] = [];
  const central: Uint8Array[] = [];
  const modified = new Date().toISOString();
  let offset = 0;
  // Places are taken at once and writes queued in order, so a slow disk can't reorder entries.
  let writing = Promise.resolve();
  const push = (bytes: Uint8Array) => {
    offset += bytes.length;
    // Kept in memory, a copy: the caller may reuse its buffer once this resolves.
    if (out) writing = writing.then(() => out(bytes));
    else parts.push(bytes.slice());
    return writing;
  };
  const addFolder = (path: string) => {
    const entry = { path, directory: true, modified, size: 0 };
    central.push(zipCentralHeader(entry, 0, offset));
    push(zipLocalHeader(entry));
    return push(zipDescriptor(0, 0));
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
            const start = offset;
            let size = 0;
            let crc = 0xffffffff;
            // Sizes and checksum follow the contents, so the contents can go out as they come.
            await push(
              zipLocalHeader({ path: `${path}/${name}`, directory: false, modified, size }),
            );
            return {
              async write(bytes) {
                crc = crc32(crc, bytes);
                size += bytes.length;
                await push(bytes);
              },
              async close() {
                const entry = { path: `${path}/${name}`, directory: false, modified, size };
                const value = ~crc >>> 0;
                central.push(zipCentralHeader(entry, value, start));
                await push(zipDescriptor(size, value));
              },
              // Left out of the listing; the download fails as a whole when a file does.
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
        await addFolder(`${path}/${name}`);
        const made = folder(`${path}/${name}`);
        entries.set(name.toLowerCase(), made);
        return made;
      },
      async removeEntry(name) {
        entries.delete(name.toLowerCase());
      },
    };
  };
  void addFolder(name);
  return {
    root: folder(name),
    /** Writes the listing that ends the ZIP; the parts are the whole ZIP when kept in memory. */
    async finish() {
      const directoryOffset = offset;
      central.forEach(push);
      await push(zipEnd(central.length, directoryOffset, offset - directoryOffset));
      return parts as BlobPart[];
    },
  };
}

/** A ZIP download under way: files go into `root`, then `save` finishes it or `discard` drops it. */
export type ZipDownload = {
  root: SaveFolder;
  /** The most it may hold, by what this browser can do. */
  limit: number;
  save: () => Promise<void>;
  discard: () => Promise<void>;
};

const GB = 1000 ** 3;
/** Phones and tablets keep a page's memory small; above this a ZIP built in memory may crash it. */
const PHONE_LIMIT = GB;
const MEMORY_LIMIT = 2 * GB;
/** Written to disk as it's built, the limit is about time and the connection, not memory. */
const DISK_LIMIT = 20 * GB;

type ZipFile = {
  createWritable: () => Promise<{
    write: (bytes: Uint8Array) => Promise<void>;
    close: () => Promise<void>;
    abort: () => Promise<void>;
  }>;
  remove?: () => Promise<void>;
};
type ZipPicker = (options: {
  suggestedName: string;
  types: { description: string; accept: Record<string, string[]> }[];
}) => Promise<ZipFile>;

const onPhone = () =>
  /Android|iPhone|iPad|iPod/i.test(navigator.userAgent) ||
  // iPadOS reports itself as a Mac.
  (navigator.platform === 'MacIntel' && navigator.maxTouchPoints > 1);

/**
 * Starts `name`.zip. On a computer with Chrome or Edge it's written straight to a file the person
 * names in a save dialog, so its size isn't bound by memory; elsewhere it's built in memory and
 * downloaded once done. Call it straight from a click: browsers only show the dialog then.
 * Undefined when the person closes the dialog.
 */
export async function startZip(name: string): Promise<ZipDownload | undefined> {
  const picker = (window as unknown as { showSaveFilePicker?: ZipPicker }).showSaveFilePicker;
  if (picker && !onPhone()) {
    let file: ZipFile | undefined;
    try {
      file = await picker({
        suggestedName: `${name}.zip`,
        types: [{ description: 'ZIP archive', accept: { 'application/zip': ['.zip'] } }],
      });
    } catch (e) {
      if ((e as Error).name === 'AbortError') return undefined;
      // Any other refusal (no recent click) builds it in memory instead.
    }
    if (file) {
      const out = await file.createWritable();
      const zip = zipFolder(name, (bytes) => out.write(bytes));
      return {
        root: zip.root,
        limit: DISK_LIMIT,
        save: async () => {
          await zip.finish();
          await out.close();
        },
        // Leave no empty or half-written ZIP behind.
        discard: async () => {
          await out.abort().catch(() => {});
          await file.remove?.().catch(() => {});
        },
      };
    }
  }
  const zip = zipFolder(name);
  return {
    root: zip.root,
    limit: onPhone() ? PHONE_LIMIT : MEMORY_LIMIT,
    save: async () => saveBlob(await zip.finish(), `${name}.zip`),
    discard: async () => {},
  };
}
