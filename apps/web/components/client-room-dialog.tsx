'use client';
import { useEffect, useState, type FormEvent } from 'react';
import { Check, Copy, Download, Eye, Loader2 } from 'lucide-react';
import type { Transport } from '@harbor/api-client';
import {
  FILE_REQUEST_DEFAULTS,
  roomContentSchema,
  type FileRequest,
  type PublicLink,
  type RoomActivity,
  type RoomContent,
} from '@harbor/contracts';
import {
  decryptName,
  isEncryptedName,
  linkKeys,
  openRoom,
  sealRoom,
  unwrapLinkSecret,
  type AccountKeys,
} from '../../../packages/api-client/src/e2ee';
import { newEncryptedRequest, requestUrl } from '../lib/e2ee-requests';
import { useVault } from '../lib/e2ee-vault';
import { fileDate } from '../lib/file-metadata';
import { usePublicLinkHost } from '../lib/public-link-host';
import { linkEnds, useCopy, useShareableUrl } from './public-link-dialog';
import { Alert } from './ui/alert';
import { Button } from './ui/button';
import { Checkbox } from './ui/checkbox';
import { Dialog } from './ui/dialog';
import { Field } from './ui/field';
import { Input, Textarea } from './ui/input';
import { Select } from './ui/select';
import { Skeleton } from './ui/skeleton';

type Folder = { id: string; name: string };
const BLANK: RoomContent = {
  name: '',
  welcome: '',
  color: '#4353d9',
  logo: null,
  sendBackUrl: null,
};
const SEND_BACK_FOLDER = 'From client';

/** A room's page as saved, opened with the link's keys when it's encrypted. */
async function readRoom(request: Transport, link: PublicLink, keys: AccountKeys | undefined) {
  const { content }: { content: string | null } = await request(`/v1/links/${link.id}/room`);
  if (!content) return null;
  const json = link.e2ee
    ? await openRoom(await linkKeys(await unwrapLinkSecret(keys!, link.e2ee.secretWrap)), content)
    : content;
  return roomContentSchema.parse(JSON.parse(json));
}

/** Turns off a room's send-back request, found by the token in its link. */
async function closeSendBack(request: Transport, url: string) {
  const token = (url: string) => url.split('#')[1]?.split('.')[0];
  const { items }: { items: FileRequest[] } = await request('/v1/file-requests');
  const made = items.find((item) => token(item.url) === token(url));
  if (made) await request(`/v1/file-requests/${made.id}`, { method: 'DELETE' });
}

/** Makes a folder a branded page for a client, or changes it, and shows who's been there. */
export function ClientRoomDialog({
  request,
  folder,
  onClose,
  onChanged,
}: {
  request: Transport;
  folder: Folder;
  onClose: () => void;
  onChanged?: () => void;
}) {
  const { keys } = useVault();
  const host = usePublicLinkHost();
  const [link, setLink] = useState<PublicLink | null>();
  const [saved, setSaved] = useState(false);
  const [content, setContent] = useState<RoomContent>({ ...BLANK, name: folder.name });
  const [expiry, setExpiry] = useState('30');
  const [neverExpires, setNeverExpires] = useState(false);
  const [sendBack, setSendBack] = useState(true);
  const [busy, setBusy] = useState(false);
  const [removing, setRemoving] = useState(false);
  const [error, setError] = useState('');
  const change = (next: Partial<RoomContent>) => setContent((c) => ({ ...c, ...next }));

  useEffect(() => {
    let current = true;
    (async () => {
      const [{ items }, allowance]: [{ items: PublicLink[] }, { neverExpires: boolean }] =
        await Promise.all([request('/v1/links'), request('/v1/links/allowance')]);
      if (current) setNeverExpires(allowance.neverExpires);
      const mine = items.filter((l) => l.driveItemId === folder.id);
      const existing = mine.find((l) => l.room) ?? mine[0] ?? null;
      if (existing?.room) {
        const room = await readRoom(request, existing, keys);
        if (current && room) {
          setContent(room);
          setSendBack(!!room.sendBackUrl);
          setSaved(true);
        }
      }
      if (current) setLink(existing);
    })().catch((e: Error) => {
      if (current) {
        setError(e.message);
        setLink(null);
      }
    });
    return () => {
      current = false;
    };
  }, [request, folder.id, keys]);

  async function ensureLink() {
    if (link) return link;
    const secret = host.encrypted ? await host.newSecret() : null;
    const { link: made }: { link: PublicLink } = await request('/v1/links', {
      method: 'POST',
      body: {
        driveItemId: folder.id,
        expiresInDays: expiry === 'never' ? null : Number(expiry),
        ...(secret ? { e2ee: secret } : {}),
      },
    });
    if (made.e2ee) await host.wrap(request, made);
    setLink(made);
    return made;
  }

  /**
   * The link clients upload through. They add to any folder in the room; guests can't encrypt
   * folder names, so an encrypted room takes uploads into one subfolder.
   */
  async function sendBackRequest() {
    if (!host.encrypted) {
      const { request: made } = await request('/v1/file-requests', {
        method: 'POST',
        body: {
          driveItemId: folder.id,
          title: content.name,
          expiresInDays: expiry === 'never' ? null : Number(expiry),
          askName: false,
          room: true,
          ...FILE_REQUEST_DEFAULTS,
        },
      });
      return requestUrl(made, keys);
    }
    const { items: siblings } = await request(
      `/v1/drive/folders/${encodeURIComponent(folder.id)}/children`,
    );
    const existing = siblings.find(
      (i: { type: string; name: string }) => i.type === 'FOLDER' && i.name === SEND_BACK_FOLDER,
    );
    const target =
      existing ??
      (
        await request('/v1/drive/folders', {
          method: 'POST',
          body: {
            operationId: crypto.randomUUID(),
            parentId: folder.id,
            name: SEND_BACK_FOLDER,
          },
        })
      ).item;
    const details = { title: content.name, message: '' };
    const sealed = await newEncryptedRequest(keys!, details);
    const { request: made } = await request('/v1/file-requests', {
      method: 'POST',
      body: {
        driveItemId: target.id,
        e2ee: sealed.e2ee,
        expiresInDays: expiry === 'never' ? null : Number(expiry),
        askName: true,
        ...FILE_REQUEST_DEFAULTS,
      },
    });
    return requestUrl(made, keys);
  }

  async function save(event: FormEvent) {
    event.preventDefault();
    setBusy(true);
    setError('');
    try {
      const target = await ensureLink();
      const sendBackUrl = !sendBack
        ? null
        : (content.sendBackUrl ?? (await sendBackRequest()));
      if (!sendBack && content.sendBackUrl) await closeSendBack(request, content.sendBackUrl);
      const room = roomContentSchema.parse({ ...content, sendBackUrl });
      const json = JSON.stringify(room);
      const sealedContent = target.e2ee
        ? await sealRoom(
            await linkKeys(await unwrapLinkSecret(keys!, target.e2ee.secretWrap)),
            json,
          )
        : json;
      const { link: changed } = await request(`/v1/links/${target.id}/room`, {
        method: 'PUT',
        body: { content: sealedContent },
      });
      setLink(changed);
      setContent(room);
      setSaved(true);
      onChanged?.();
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  }

  if (host.encrypted && !keys)
    return (
      <Dialog open onOpenChange={(open) => !open && onClose()} title="Data room">
        <Alert tone="info">Unlock your files to manage an end-to-end encrypted room.</Alert>
      </Dialog>
    );
  return (
    <Dialog
      open
      onOpenChange={(open) => {
        if (!open && !busy) onClose();
      }}
      className="public-link-dialog"
      title={saved ? 'Data room' : 'Make a data room'}
    >
      {link === undefined ? (
        <div className="plink-loading" aria-label="Loading">
          <Skeleton style={{ height: 40 }} />
          <Skeleton style={{ height: 16, width: '60%' }} />
        </div>
      ) : (
        <form className="request-form" onSubmit={save}>
          {error && <Alert tone="error">{error}</Alert>}
          {saved && link && <RoomLink link={link} />}
          <Field label="Name">
            <Input
              required
              maxLength={120}
              value={content.name}
              onChange={(e) => change({ name: e.target.value })}
            />
          </Field>
          <Field label="Welcome note" hint="Shown at the top of the data room. Optional.">
            <Textarea
              rows={3}
              maxLength={2000}
              value={content.welcome}
              onChange={(e) => change({ welcome: e.target.value })}
            />
          </Field>
          {!link && (
            <Field label="Ends after">
              <Select value={expiry} onChange={(e) => setExpiry(e.target.value)}>
                <option value="7">7 days</option>
                <option value="30">30 days</option>
                {neverExpires && <option value="never">Never</option>}
              </Select>
            </Field>
          )}
          <Field
            inline
            label="Let people upload files"
            hint={
              host.encrypted
                ? `Uploads go to a “${SEND_BACK_FOLDER}” folder in this data room.`
                : 'They can add files and folders. Everyone with the link can see them.'
            }
          >
            <Checkbox checked={sendBack} onChange={(e) => setSendBack(e.target.checked)} />
          </Field>
          {saved && link && <Activity request={request} link={link} keys={keys} />}
          <div className="plink-footer">
            <div className="plink-footer-start">
              {saved && link && (
                <Button
                  type="button"
                  variant="ghost"
                  size="sm"
                  disabled={busy}
                  onClick={async () => {
                    // The link stops working for good, so the first click only says so.
                    if (!removing) return setRemoving(true);
                    setBusy(true);
                    try {
                      if (content.sendBackUrl) await closeSendBack(request, content.sendBackUrl);
                      await request(`/v1/links/${link.id}/room`, { method: 'DELETE' });
                      onChanged?.();
                      onClose();
                    } catch (e) {
                      setError((e as Error).message);
                      setBusy(false);
                    }
                  }}
                >
                  {removing ? 'Remove data room and turn off its link' : 'Remove data room'}
                </Button>
              )}
            </div>
            <Button type="button" variant="outline" onClick={onClose}>
              {saved ? 'Done' : 'Cancel'}
            </Button>
            <Button type="submit" disabled={busy || !content.name.trim()}>
              {busy && <Loader2 className="spin" aria-hidden="true" />}
              {saved ? 'Save changes' : 'Create data room'}
            </Button>
          </div>
        </form>
      )}
    </Dialog>
  );
}

function RoomLink({ link }: { link: PublicLink }) {
  const host = usePublicLinkHost();
  const url = useShareableUrl(link, host);
  const { copied, copy } = useCopy(host.copy);
  return (
    <div className="room-link">
      <small className="field-hint">{linkEnds(link.expiresAt)}</small>
      <Button
        type="button"
        size="sm"
        disabled={!url}
        onClick={() => url && void copy(link.id, url)}
      >
        {copied === link.id ? <Check aria-hidden="true" /> : <Copy aria-hidden="true" />}
        {copied === link.id ? 'Copied' : 'Copy link'}
      </Button>
    </div>
  );
}

/** Who opened the room and what they took, newest first. */
function Activity({
  request,
  link,
  keys,
}: {
  request: Transport;
  link: PublicLink;
  keys: AccountKeys | undefined;
}) {
  const [items, setItems] = useState<RoomActivity['items']>();
  useEffect(() => {
    let current = true;
    request(`/v1/links/${link.id}/room/activity`)
      .then(async ({ items: rows }: RoomActivity) => {
        const shown = await Promise.all(
          rows.map(async (row) => {
            const name = row.item?.name ?? '';
            if (!row.item || !keys || !isEncryptedName(name)) return row;
            return { ...row, item: { ...row.item, name: await decryptName(keys, name) } };
          }),
        );
        if (current) setItems(shown);
      })
      .catch(() => current && setItems([]));
    return () => {
      current = false;
    };
  }, [request, link.id, keys]);
  if (!items) return null;
  const visitors = [...new Set(items.map((i) => i.visitor))];
  return (
    <details className="room-activity">
      <summary>Activity ({items.length})</summary>
      {items.length ? (
        <ul>
          {items.slice(0, 50).map((row, i) => (
            <li key={`${row.at}${i}`}>
              {row.kind === 'OPENED' ? <Eye aria-hidden="true" /> : <Download aria-hidden="true" />}
              <span>
                <strong>
                  {row.kind === 'OPENED'
                    ? 'Opened the data room'
                    : row.kind === 'DOWNLOADED_ALL'
                      ? `Downloaded ${row.files} files`
                      : `Downloaded ${row.item?.name || 'a file'}`}
                </strong>
                <small>
                  Visitor {visitors.indexOf(row.visitor) + 1}
                  {row.browser ? ` · ${row.browser}` : ''} · {fileDate(row.at).date}{' '}
                  {fileDate(row.at).time}
                </small>
              </span>
            </li>
          ))}
        </ul>
      ) : (
        <p className="field-hint">Nobody has opened this data room yet.</p>
      )}
    </details>
  );
}
