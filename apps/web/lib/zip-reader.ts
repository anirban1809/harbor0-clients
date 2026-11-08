// Reads ZIP archives in place, per PKWARE APPNOTE 6.3.10: the central directory at the end
// lists every entry, so a listing reads only the archive's last few kilobytes, and one entry
// reads only its own bytes. Nothing is written back; archives are browsed read-only.
import type { ReadRange } from './file-preview';

export type ZipSource = { size: number; read: ReadRange };
export type ZipFile = {
  /** Path inside the archive, without a leading or trailing slash. */
  path: string;
  directory: boolean;
  size: number;
  compressedSize: number;
  /** 0 is stored, 8 is deflate; anything else can be listed but not opened. */
  method: number;
  encrypted: boolean;
  /** Where the entry's local header starts. */
  offset: number;
  modified?: string;
};
export class ZipError extends Error {
  override name = 'PreviewUnavailable';
}

const EOCD = 0x06054b50;
const EOCD64 = 0x06064b50;
const LOCATOR64 = 0x07064b50;
const CENTRAL = 0x02014b50;
const LOCAL = 0x04034b50;
// The end record is 22 bytes plus a comment of up to 64 KB; the ZIP64 locator sits before it.
const TAIL = 22 + 0xffff + 20;
/** Central directories past this are not read: about a million entries. */
const MAX_DIRECTORY = 128 * 1024 * 1024;
const READ_WINDOW = 4 * 1024 * 1024;

const view = (bytes: Uint8Array) => new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
const u64 = (v: DataView, at: number) => Number(v.getBigUint64(at, true));

/** MS-DOS date and time, as written by most zip tools, in local time. */
function dosDate(time: number, date: number) {
  if (!date) return undefined;
  const d = new Date(
    1980 + (date >> 9),
    ((date >> 5) & 15) - 1,
    date & 31,
    time >> 11,
    (time >> 5) & 63,
    (time & 31) * 2,
  );
  return Number.isNaN(d.getTime()) ? undefined : d.toISOString();
}

const utf8 = new TextDecoder('utf-8', { fatal: true });
const lenient = new TextDecoder('utf-8');
// Names without the UTF-8 flag are CP437, though many tools write UTF-8 regardless.
const cp437 =
  'ÇüéâäàåçêëèïîìÄÅÉæÆôöòûùÿÖÜ¢£¥₧ƒáíóúñÑªº¿⌐¬½¼¡«»░▒▓│┤╡╢╖╕╣║╗╝╜╛┐└┴┬├─┼╞╟╚╔╩╦╠═╬╧╨╤╥╙╘╒╓╫╪┘┌█▄▌▐▀αßΓπΣσµτΦΘΩδ∞φε∩≡±≥≤⌠⌡÷≈°∙·√ⁿ²■ ';
function decodeName(bytes: Uint8Array, flaggedUtf8: boolean) {
  if (flaggedUtf8) return lenient.decode(bytes);
  try {
    return utf8.decode(bytes);
  } catch {
    return Array.from(bytes, (b) => (b < 128 ? String.fromCharCode(b) : cp437[b - 128])).join('');
  }
}

/** Folder-safe path: no leading slashes, no `.` or `..` segments, forward slashes only. */
export function cleanPath(path: string) {
  return path
    .replaceAll('\\', '/')
    .split('/')
    .filter((part) => part && part !== '.' && part !== '..')
    .join('/');
}

/** Lists the entries of an archive from its central directory. */
export async function readZipDirectory(source: ZipSource): Promise<ZipFile[]> {
  const notZip = () => new ZipError('This file is not a ZIP archive, or it is damaged.');
  if (source.size < 22) throw notZip();
  const tailStart = Math.max(0, source.size - TAIL);
  const tail = await source.read(tailStart, source.size);
  const t = view(tail);
  let end = -1;
  for (let i = tail.length - 22; i >= 0; i--) {
    if (t.getUint32(i, true) === EOCD && i + 22 + t.getUint16(i + 20, true) <= tail.length) {
      end = i;
      break;
    }
  }
  if (end < 0) throw notZip();
  let count = t.getUint16(end + 10, true);
  let dirSize = t.getUint32(end + 12, true);
  let dirOffset = t.getUint32(end + 16, true);
  const locator = end - 20;
  if (locator >= 0 && t.getUint32(locator, true) === LOCATOR64) {
    const at = u64(t, locator + 8);
    const record = at >= tailStart ? tail.subarray(at - tailStart) : await source.read(at, at + 56);
    const r = view(record);
    if (record.length < 56 || r.getUint32(0, true) !== EOCD64) throw notZip();
    count = u64(r, 32);
    dirSize = u64(r, 40);
    dirOffset = u64(r, 48);
  }
  if (dirSize > MAX_DIRECTORY)
    throw new ZipError('This archive has too many files to list here. Download it to open it.');
  if (dirOffset + dirSize > source.size) throw notZip();
  const dir =
    dirOffset >= tailStart
      ? tail.subarray(dirOffset - tailStart, dirOffset - tailStart + dirSize)
      : await source.read(dirOffset, dirOffset + dirSize);
  const d = view(dir);
  const files: ZipFile[] = [];
  let at = 0;
  for (let n = 0; n < count && at + 46 <= dir.length; n++) {
    if (d.getUint32(at, true) !== CENTRAL) throw notZip();
    const flags = d.getUint16(at + 8, true);
    const method = d.getUint16(at + 10, true);
    const nameLength = d.getUint16(at + 28, true);
    const extraLength = d.getUint16(at + 30, true);
    const commentLength = d.getUint16(at + 32, true);
    const external = d.getUint32(at + 38, true);
    let compressedSize = d.getUint32(at + 20, true);
    let size = d.getUint32(at + 24, true);
    let offset = d.getUint32(at + 42, true);
    const rawName = decodeName(dir.subarray(at + 46, at + 46 + nameLength), !!(flags & 0x800));
    // Sizes and offsets too large for 32 bits are in the ZIP64 extra field, in this order.
    let extra = at + 46 + nameLength;
    const extraEnd = extra + extraLength;
    while (extra + 4 <= extraEnd) {
      const id = d.getUint16(extra, true);
      const length = d.getUint16(extra + 2, true);
      if (id === 1) {
        let field = extra + 4;
        if (size === 0xffffffff) {
          size = u64(d, field);
          field += 8;
        }
        if (compressedSize === 0xffffffff) {
          compressedSize = u64(d, field);
          field += 8;
        }
        if (offset === 0xffffffff) offset = u64(d, field);
      }
      extra += 4 + length;
    }
    // Directories end in a slash; some Windows tools mark them only with the attribute.
    const directory = /[/\\]$/.test(rawName) || (!!(external & 0x10) && size === 0);
    const path = cleanPath(rawName);
    if (path)
      files.push({
        path,
        directory,
        size: directory ? 0 : size,
        compressedSize,
        method,
        encrypted: !!(flags & 1),
        offset,
        modified: dosDate(d.getUint16(at + 12, true), d.getUint16(at + 14, true)),
      });
    at = extraEnd + commentLength;
  }
  return files;
}

/** Why an entry can't be opened in the browser, if it can't. */
export function unreadable(file: ZipFile) {
  if (file.encrypted) return 'This file is password-protected. Download the archive to open it.';
  if (file.method !== 0 && file.method !== 8)
    return 'This file uses a compression method the browser can’t open. Download the archive to open it.';
}

/** An entry's contents, decompressed as they are read. */
export async function openZipFile(
  source: ZipSource,
  file: ZipFile,
  signal?: AbortSignal,
): Promise<ReadableStream<Uint8Array>> {
  const problem = unreadable(file);
  if (problem) throw new ZipError(problem);
  // The local header repeats the name and has its own extra field; the data follows them.
  const header = view(await source.read(file.offset, file.offset + 30));
  if (header.byteLength < 30 || header.getUint32(0, true) !== LOCAL)
    throw new ZipError('This archive is damaged. Download it to open it.');
  let at = file.offset + 30 + header.getUint16(26, true) + header.getUint16(28, true);
  const end = at + file.compressedSize;
  if (end > source.size) throw new ZipError('This archive is damaged. Download it to open it.');
  const raw = new ReadableStream<Uint8Array>({
    async pull(controller) {
      if (signal?.aborted) return controller.error(signal.reason);
      if (at >= end) return controller.close();
      const next = Math.min(end, at + READ_WINDOW);
      controller.enqueue((await source.read(at, next)).slice());
      at = next;
    },
  });
  return file.method === 8
    ? raw.pipeThrough(
        new DecompressionStream('deflate-raw') as ReadableWritablePair<Uint8Array, Uint8Array>,
      )
    : raw;
}

/** Reads up to `limit` bytes of an entry, stopping early once there are enough. */
export async function readZipFile(
  source: ZipSource,
  file: ZipFile,
  limit = Infinity,
  signal?: AbortSignal,
) {
  const reader = (await openZipFile(source, file, signal)).getReader();
  const parts: Uint8Array<ArrayBuffer>[] = [];
  let total = 0;
  try {
    while (total < limit) {
      const { done, value } = await reader.read();
      if (done) break;
      parts.push(value.slice());
      total += value.length;
    }
  } catch {
    throw new ZipError('This file in the archive is damaged and can’t be opened.');
  } finally {
    void reader.cancel().catch(() => {});
  }
  return { parts, truncated: total > limit || (total === limit && total < file.size) };
}

export type ZipFolder = { path: string; name: string; size: number; modified?: string };
export type ZipTree = {
  files: ZipFile[];
  /** Every folder, including ones only implied by the paths under them. */
  folders: Map<string, ZipFolder>;
  /** Paths directly inside each folder, by the folder's path ('' is the archive root). */
  children: Map<string, string[]>;
  byPath: Map<string, ZipFile>;
};
const parentOf = (path: string) => path.slice(0, Math.max(0, path.lastIndexOf('/')));
const nameOf = (path: string) => path.slice(path.lastIndexOf('/') + 1);

/** Arranges a listing into folders, as the archive would unpack. */
export function zipTree(entries: ZipFile[]): ZipTree {
  const folders = new Map<string, ZipFolder>();
  const children = new Map<string, string[]>([['', []]]);
  const byPath = new Map<string, ZipFile>();
  const addFolder = (path: string, modified?: string): ZipFolder => {
    let folder = folders.get(path);
    if (!folder) {
      folders.set(path, (folder = { path, name: nameOf(path), size: 0, modified }));
      children.set(path, []);
      const parent = parentOf(path);
      if (path.includes('/')) addFolder(parent);
      // A file and a folder of the same name can't both unpack; the file is listed.
      if (!byPath.has(path)) children.get(parent)!.push(path);
    } else if (modified) folder.modified ??= modified;
    return folder;
  };
  const files: ZipFile[] = [];
  for (const entry of entries) {
    // macOS adds resource forks under __MACOSX; Archive Utility hides them too.
    if (entry.path === '__MACOSX' || entry.path.startsWith('__MACOSX/')) continue;
    if (entry.directory) {
      addFolder(entry.path, entry.modified);
      continue;
    }
    // A later entry with the same path replaces the earlier one when unpacked.
    const earlier = byPath.get(entry.path);
    if (earlier) {
      files.splice(files.indexOf(earlier), 1, entry);
      byPath.set(entry.path, entry);
      continue;
    }
    if (folders.has(entry.path)) continue;
    byPath.set(entry.path, entry);
    files.push(entry);
    const parent = parentOf(entry.path);
    if (parent) addFolder(parent);
    children.get(parent)!.push(entry.path);
  }
  for (const file of files)
    for (let p = parentOf(file.path); p; p = parentOf(p)) folders.get(p)!.size += file.size;
  return { files, folders, children, byPath };
}

/** Every file at or under `path`, for copying or extracting a folder. */
export function filesUnder(tree: ZipTree, path: string): ZipFile[] {
  const file = tree.byPath.get(path);
  if (file) return [file];
  return tree.files.filter((f) => f.path.startsWith(path + '/'));
}
