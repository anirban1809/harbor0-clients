import type { ApiClient } from '@harbor/api-client';
import type { DriveItem } from '@harbor/contracts';

/** A file under a folder being downloaded, with the folders between them. */
export type FolderFile = { item: DriveItem; path: string[] };

/**
 * Everything under a folder, a page at a time: its files, and every folder path inside it so
 * empty folders are kept too. Files stored only on linked devices are counted, not listed.
 */
export async function listFolderFiles(
  api: ApiClient,
  folderId: string,
  signal: AbortSignal,
  onFound: (files: number) => void,
) {
  const files: FolderFile[] = [];
  const folders: string[][] = [];
  let deviceOnly = 0;
  const queue: { id: string; path: string[] }[] = [{ id: folderId, path: [] }];
  for (let next = queue.shift(); next; next = queue.shift()) {
    let cursor: string | undefined;
    do {
      signal.throwIfAborted();
      const page = await api.list(next.id, cursor, signal);
      for (const item of page.items) {
        if (item.type === 'FOLDER') {
          const path = [...next.path, item.name];
          folders.push(path);
          queue.push({ id: item.id, path });
        } else if (item.cloudState && item.cloudState !== 'AVAILABLE') deviceOnly++;
        else files.push({ item, path: next.path });
      }
      onFound(files.length);
      cursor = page.nextCursor ?? undefined;
    } while (cursor);
  }
  return { files, folders, deviceOnly };
}
