import type { Transport } from '@harbor/api-client';
import type { PublicLink, PublicLinkScan } from '@harbor/contracts';
import {
  fromB64url,
  linkKeys,
  linkUrl,
  newLinkSecret,
  unwrapLinkSecret,
  wrapLinkFileKey,
  wrapLinkName,
  wrapLinkSecret,
  type AccountKeys,
} from '../../../packages/api-client/src/e2ee';

/**
 * Public links of an end-to-end encrypted account (docs/specs/public-folder-links.md). The link's
 * secret is only in its address; this tab seals each shared item's name and content key with it,
 * and keeps those wraps current as the files change.
 */

/** A new encrypted link's secret, and its wrapped copy for the server to keep. */
export async function newEncryptedLink(keys: AccountKeys) {
  const secret = newLinkSecret();
  return { secret, e2ee: { secretWrap: await wrapLinkSecret(keys, secret) } };
}

/** The address to share: an encrypted link's carries its secret, which needs the account's keys. */
export async function shareableUrl(link: PublicLink, keys: AccountKeys | undefined) {
  if (!link.e2ee) return link.url;
  if (!keys) return null;
  return linkUrl(link.url, await unwrapLinkSecret(keys, link.e2ee.secretWrap));
}

export type WrapProgress = {
  /** Items looked at so far. */
  scanned: number;
  /** Items wrapped now; the rest were current already. */
  wrapped: number;
  /** Encrypted files whose key can't be read yet (still uploading, or not in the cloud). */
  waiting: number;
  done: boolean;
};
type Wrap = {
  itemId: string;
  revision: number;
  nameWrap: string;
  keyWrap: string | null;
  head: string | null;
};
type ScanItem = PublicLinkScan['root'];

/** Runs under way, by link, with everyone waiting on their progress. */
const running = new Map<
  string,
  { run: Promise<WrapProgress>; listeners: Set<(progress: WrapProgress) => void> }
>();

/**
 * Wraps whatever under the link isn't wrapped for its current name and version yet: the item
 * itself and, for a folder, everything inside it. One run per link at a time; a second caller
 * joins it and hears its progress too.
 */
export function syncLinkWraps(
  request: Transport,
  keys: AccountKeys,
  link: PublicLink,
  onProgress?: (progress: WrapProgress) => void,
) {
  let entry = running.get(link.id);
  if (!entry) {
    const listeners = new Set<(progress: WrapProgress) => void>();
    const run = wrapAll(request, keys, link, (p) => listeners.forEach((l) => l(p))).finally(() =>
      running.delete(link.id),
    );
    entry = { run, listeners };
    running.set(link.id, entry);
  }
  if (onProgress) entry.listeners.add(onProgress);
  return entry.run;
}

async function wrapAll(
  request: Transport,
  keys: AccountKeys,
  link: PublicLink,
  onProgress?: (progress: WrapProgress) => void,
) {
  const sealed = await linkKeys(await unwrapLinkSecret(keys, link.e2ee!.secretWrap));
  const progress: WrapProgress = { scanned: 0, wrapped: 0, waiting: 0, done: false };
  let pending: Wrap[] = [];
  const save = async (all: boolean) => {
    while (pending.length >= 100 || (all && pending.length)) {
      const wraps = pending.slice(0, 100);
      pending = pending.slice(wraps.length);
      const { saved } = await request(`/v1/links/${link.id}/e2ee/wraps`, {
        method: 'PUT',
        body: { wraps },
      });
      // Items changed since the scan are wrapped on the next run.
      progress.wrapped += saved;
    }
  };
  const consider = async (item: ScanItem) => {
    progress.scanned++;
    if (item.current) return;
    let keyWrap: string | null = null;
    let head: string | null = null;
    if (item.encrypted) {
      if (!item.keyHeader) {
        progress.waiting++;
        return;
      }
      ({ keyWrap, head } = await wrapLinkFileKey(keys, sealed, fromB64url(item.keyHeader)));
    }
    // This tab's transport has decrypted the name already: that's what the visitor sees.
    const nameWrap = await wrapLinkName(sealed, item.id, item.name);
    pending.push({ itemId: item.id, revision: item.revision, nameWrap, keyWrap, head });
  };

  let queue: { folderId?: string; cursor?: string }[] = link.itemType === 'FOLDER' ? [{}] : [];
  let first = true;
  while (first || queue.length) {
    const folders = queue.slice(0, 25);
    queue = queue.slice(folders.length);
    const scan: PublicLinkScan = await request(`/v1/links/${link.id}/e2ee/scan`, {
      method: 'POST',
      body: { folders },
    });
    if (first) await consider(scan.root);
    first = false;
    for (const folder of scan.folders) {
      for (const item of folder.items) {
        await consider(item);
        if (item.type === 'FOLDER') queue.push({ folderId: item.id });
      }
      if (folder.nextCursor) queue.push({ folderId: folder.folderId, cursor: folder.nextCursor });
    }
    await save(false);
    onProgress?.({ ...progress });
  }
  await save(true);
  progress.done = true;
  onProgress?.({ ...progress });
  return progress;
}

/** How often an unlocked tab brings its account's encrypted links up to date. */
export const LINK_SYNC_MS = 10 * 60_000;

/** Brings every encrypted link of the account up to date, one at a time; errors wait for the next run. */
export async function syncAllLinks(request: Transport, keys: AccountKeys) {
  const { items } = (await request('/v1/links')) as { items: PublicLink[] };
  for (const link of items.filter((l) => l.e2ee && !l.disabledAt))
    await syncLinkWraps(request, keys, link).catch(() => {});
}
