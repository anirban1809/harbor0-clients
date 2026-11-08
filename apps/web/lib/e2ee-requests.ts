import { ApiError, type Transport } from '@harbor/api-client';
import type { FileRequest, FileRequestInboxItem } from '@harbor/contracts';
import {
  adoptRequestFile,
  encryptName,
  linkUrl,
  newFileRequest,
  unwrapLinkSecret,
  type AccountKeys,
} from '../../../packages/api-client/src/e2ee';
import { numberedName } from './upload';

/**
 * File requests of an end-to-end encrypted account (docs/specs/client-rooms-file-requests.md).
 * Guests seal what they send to the request's public key. It waits in the account's inbox until an
 * unlocked tab like this one gives each file a header and name the account can read.
 */

export const newEncryptedRequest = (
  keys: AccountKeys,
  details: { title: string; message: string },
) => newFileRequest(keys, details);

/** The address to share: an encrypted request's carries its secret. */
export async function requestUrl(request: FileRequest, keys: AccountKeys | undefined) {
  if (!request.e2ee) return request.url;
  if (!keys) return null;
  return linkUrl(request.url, await unwrapLinkSecret(keys, request.e2ee.secretWrap));
}

const inbox = (id: string) => `/v1/file-requests/inbox/${id}`;

/** Most tries at a free name: every one of them taken means something else is wrong. */
const NAME_TRIES = 50;

async function add(request: Transport, keys: AccountKeys, file: FileRequestInboxItem) {
  const { header, meta } = await adoptRequestFile(keys, file);
  // A rewrite is idempotent, so a file left REWRITING just reports where it is.
  const { state } = await request(`${inbox(file.id)}/header`, {
    method: 'POST',
    body: { header },
  });
  if (state !== 'READY') return false;
  const uploaderName = meta.sender ? await encryptName(keys, meta.sender.slice(0, 100)) : null;
  for (let n = 1; n <= NAME_TRIES; n++) {
    const name = await encryptName(keys, n === 1 ? meta.name : numberedName(meta.name, n));
    try {
      await request(`${inbox(file.id)}/adopt`, { method: 'POST', body: { name, uploaderName } });
      return true;
    } catch (e) {
      if (!(e instanceof ApiError) || e.code !== 'NAME_CONFLICT') throw e;
    }
  }
  return false;
}

let running: Promise<number> | undefined;

/** Adds what's waiting to the drive; the number added. One run at a time, errors wait for the next. */
export function syncRequestInbox(request: Transport, keys: AccountKeys) {
  running ??= (async () => {
    const { items }: { items: FileRequestInboxItem[] } = await request('/v1/file-requests/inbox');
    let added = 0;
    for (const file of items) if (await add(request, keys, file).catch(() => false)) added++;
    return added;
  })().finally(() => {
    running = undefined;
  });
  return running;
}

/** How often an unlocked tab goes through the inbox, as large files finish rewriting. */
export const INBOX_SYNC_MS = 60_000;
