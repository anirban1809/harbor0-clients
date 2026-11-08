// A ZIP archive in the drive, read in place: listing, previews, downloads and copies of the
// files inside it, without downloading the whole archive.
import type { ApiClient } from '@harbor/api-client';
import type { DriveItem } from '@harbor/contracts';
import { TEXT_PREVIEW_BYTES } from '../../../packages/api-client/src/preview';
import { guessMime } from './e2ee-transport';
import { isEncrypted, openEncrypted, rangeFetcher } from './e2ee-files';
import type { FileEntry } from './file-metadata';
import { previewKind, type PreviewLoader } from './file-preview';
import type { UploadEntry } from './dropped-files';
import {
  filesUnder,
  openZipFile,
  readZipDirectory,
  readZipFile,
  unreadable,
  zipTree,
  ZipError,
  type ZipFile,
  type ZipSource,
  type ZipTree,
} from './zip-reader';

export const isZip = (item: Pick<DriveItem, 'type' | 'name' | 'mimeType'>) =>
  item.type === 'FILE' &&
  (/\.zip$/i.test(item.name) ||
    ['application/zip', 'application/x-zip-compressed'].includes(item.mimeType ?? ''));

export type OpenArchive = { item: DriveItem; source: ZipSource; tree: ZipTree };

/** Reads an archive's listing; its files are then read from the same download link. */
export async function openArchive(api: ApiClient, id: string): Promise<OpenArchive> {
  const { item }: { item: DriveItem } = await api.request(`/v1/drive/items/${id}`);
  if (item.type !== 'FILE') throw new ZipError('This item is not a ZIP archive.');
  const input = { driveItemId: id, preview: true };
  let source: ZipSource;
  if (isEncrypted(item)) source = await openEncrypted(api, input);
  else {
    const link = await api.download(input);
    const read = rangeFetcher(
      link.downloadUrl,
      async () => (await api.download(input)).downloadUrl,
    );
    source = {
      size: link.sizeBytes,
      read: (begin, end) => read(begin, Math.min(end, link.sizeBytes)),
    };
  }
  return archiveFrom(item, source);
}

/** An archive read through `source`, wherever its bytes come from. */
export async function archiveFrom(item: DriveItem, source: ZipSource): Promise<OpenArchive> {
  if (item.type !== 'FILE') throw new ZipError('This item is not a ZIP archive.');
  return { item, source, tree: zipTree(await readZipDirectory(source)) };
}

/** IDs of listed entries: folders and files are kept apart in case a path is both. */
export const folderId = (path: string) => `zip-folder:${path}`;
export const fileId = (path: string) => `zip-file:${path}`;
export type ZipEntry = FileEntry & { path: string };

/** What a folder in the archive lists, as drive entries. */
export function listFolder(archive: OpenArchive, path: string): ZipEntry[] {
  const { tree, item } = archive;
  return (tree.children.get(path) ?? []).map((child): ZipEntry => {
    const file = tree.byPath.get(child);
    const name = child.slice(child.lastIndexOf('/') + 1);
    const modified = file?.modified ?? tree.folders.get(child)?.modified ?? item.updatedAt;
    return file
      ? {
          id: fileId(child),
          path: child,
          name,
          type: 'FILE',
          sizeBytes: file.size,
          mimeType: guessMime(name),
          ownerUserId: item.ownerUserId,
          updatedAt: modified,
        }
      : {
          id: folderId(child),
          path: child,
          name,
          type: 'FOLDER',
          sizeBytes: tree.folders.get(child)?.size ?? 0,
          ownerUserId: item.ownerUserId,
          updatedAt: modified,
        };
  });
}

/** Files larger than this aren't previewed from an archive: the preview is built in memory. */
const PREVIEW_MAX = 512 * 1024 * 1024;

/** Previews of files inside an archive. */
export function archivePreviewLoader(archive: OpenArchive): PreviewLoader {
  return async (entry, signal) => {
    const file = archive.tree.byPath.get((entry as ZipEntry).path);
    if (!file) throw new ZipError('This file is no longer in the archive.');
    const kind = previewKind(entry);
    if (kind === 'text') {
      const { parts, truncated } = await readZipFile(
        archive.source,
        file,
        TEXT_PREVIEW_BYTES,
        signal,
      );
      const text = new TextDecoder().decode(
        new Uint8Array(await new Blob(parts).slice(0, TEXT_PREVIEW_BYTES).arrayBuffer()),
      );
      if (text.includes('\0'))
        throw new Error('This file contains binary data and cannot be shown as text.');
      return { text, truncated };
    }
    if (file.size > PREVIEW_MAX)
      throw new ZipError(
        'This file is too large to preview from inside an archive. Download it to open it.',
      );
    const blob = new Blob((await readZipFile(archive.source, file, Infinity, signal)).parts, {
      type: entry.mimeType ?? guessMime(entry.name),
    });
    if (kind === 'pdf')
      return {
        size: blob.size,
        read: async (begin, end) => new Uint8Array(await blob.slice(begin, end).arrayBuffer()),
      };
    return { url: URL.createObjectURL(blob) };
  };
}

// Larger files stream straight to disk where the browser can; others are built in memory.
const STREAM_ABOVE = 256 * 1024 * 1024;
type SavePicker = (options: { suggestedName: string }) => Promise<{
  createWritable: () => Promise<WritableStream<Uint8Array>>;
}>;

/** Saves one file from the archive under its own name. */
export async function saveArchiveFile(archive: OpenArchive, file: ZipFile) {
  const name = file.path.slice(file.path.lastIndexOf('/') + 1);
  const picker = (window as unknown as { showSaveFilePicker?: SavePicker }).showSaveFilePicker;
  if (picker && file.size > STREAM_ABOVE) {
    let handle;
    try {
      handle = await picker({ suggestedName: name });
    } catch {
      return;
    }
    await (await openZipFile(archive.source, file)).pipeTo(await handle.createWritable());
    return;
  }
  const blob = new Blob((await readZipFile(archive.source, file)).parts, { type: guessMime(name) });
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = name;
  document.body.append(a);
  a.click();
  a.remove();
  setTimeout(() => URL.revokeObjectURL(url), 60_000);
}

/** Copies are unpacked in memory before they upload; past this, unpack on a computer. */
export const COPY_MAX = 2 * 1024 * 1024 * 1024;

/** The files under `paths`, unpacked for upload, laid out as they are below `base`. */
export function copyPlan(archive: OpenArchive, paths: string[], base: string) {
  const all = [...new Set(paths.flatMap((path) => filesUnder(archive.tree, path)))];
  // Password-protected files, and ones in formats the browser can't unpack, are left out.
  const files = all.filter((file) => !unreadable(file));
  return {
    files,
    skipped: all.length - files.length,
    size: files.reduce((total, file) => total + file.size, 0),
    base,
  };
}
export async function unpack(
  archive: OpenArchive,
  plan: ReturnType<typeof copyPlan>,
  onProgress?: (done: number) => void,
  signal?: AbortSignal,
): Promise<UploadEntry[]> {
  const entries: UploadEntry[] = [];
  for (const file of plan.files) {
    signal?.throwIfAborted();
    const relative = plan.base ? file.path.slice(plan.base.length + 1) : file.path;
    const segments = relative.split('/');
    const name = segments.pop()!;
    const { parts } = await readZipFile(archive.source, file, Infinity, signal);
    const lastModified = file.modified ? Date.parse(file.modified) : Date.now();
    entries.push({
      file: new File(parts, name, { type: guessMime(name), lastModified }),
      folders: segments,
    });
    onProgress?.(entries.length);
  }
  return entries;
}
