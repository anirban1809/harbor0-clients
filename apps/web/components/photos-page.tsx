'use client';
import { useEffect, useMemo, useRef, useState } from 'react';
import { useInfiniteQuery, type InfiniteData } from '@tanstack/react-query';
import { Film, ImageIcon, LayoutGrid, List, Play } from 'lucide-react';
import {
  mediaKind,
  MAX_PHOTO_LINKS,
  MAX_PHOTO_THUMBNAILS,
  PHOTO_THUMBNAIL_PX,
  PHOTO_THUMBNAIL_QUALITY,
  type Device,
  type DriveItem,
  type MediaKind,
  type PhotoItem,
  type SyncFolderItem,
} from '@harbor/contracts';
import { deviceDisplayName } from '@harbor/contracts';
import type { BackupRoot } from '../../../packages/contracts/src/backups';
import { encryptedSize, openWhole, sealWhole } from '../../../packages/api-client/src/e2ee';
import { openEncryptedLink, rangeFetcher } from '../lib/e2ee-files';
import { onBrowserCacheClear } from '../lib/browser-cache';
import { requireKeys } from '../lib/e2ee-vault';
import { isPhone, ownsBackup } from '../lib/devices';
import { fileDate, fileSize } from '../lib/file-metadata';
import { DeviceIcon } from './device-folders';
import { EmptyState, LoadError } from './empty-state';
import { LoadMoreFiles } from './load-more-files';
import { ContentSkeleton } from './loading-states';
import { Segmented } from './ui/segmented';
import { DataTable } from './ui/table';

type Request = <T = any>(
  path: string,
  init?: { method?: string; body?: unknown; background?: boolean },
) => Promise<T>;
type View = 'grid' | 'list';
type Grouping = 'date' | 'device';
type Filter = 'all' | MediaKind;
/** One heading on the page: a month, or a device and its photos. */
type Section = { key: string; name: string; device?: Device; items: PhotoItem[] };

/**
 * Pages loaded on their own before asking, when the server returns photos in no particular
 * order (until its index of them is complete). In order, more load as the page scrolls.
 */
const AUTO_PAGES = 20;
const PAGE_SIZE = 300;
const CLOUD = 'cloud';
const takenAt = (item: PhotoItem) => item.modifiedAt ?? item.createdAt;
const month = (value: string) =>
  new Date(value).toLocaleDateString(undefined, { month: 'long', year: 'numeric' });
const settings = 'harbor0.photos';
function remembered<T extends string>(key: string, fallback: T, allowed: T[]): T {
  try {
    const value = localStorage.getItem(`${settings}.${key}`) as T | null;
    return value && allowed.includes(value) ? value : fallback;
  } catch {
    return fallback;
  }
}
function remember(key: string, value: string) {
  try {
    localStorage.setItem(`${settings}.${key}`, value);
  } catch {}
}

/**
 * Photos: every photo and video in the account, newest first, as thumbnails or a list, by month
 * or by the device each was synced or backed up from. Opening one shows it in the preview.
 */
export function PhotosPage({
  request,
  cacheScope,
  devices,
  backups,
  syncFolders,
  onOpen,
}: {
  request: Request;
  cacheScope: string;
  devices?: Device[];
  backups?: BackupRoot[];
  syncFolders?: SyncFolderItem[];
  onOpen: (item: DriveItem) => void;
}) {
  const [view, setView] = useState<View>('grid');
  const [grouping, setGrouping] = useState<Grouping>('date');
  const [filter, setFilter] = useState<Filter>('all');
  useEffect(() => {
    setView(remembered<View>('view', 'grid', ['grid', 'list']));
    setGrouping(remembered<Grouping>('grouping', 'date', ['date', 'device']));
  }, []);
  const photos = useInfiniteQuery({
    queryKey: ['files', 'photos', cacheScope],
    initialPageParam: '',
    queryFn: ({ pageParam, client, queryKey }) =>
      request<{ items: PhotoItem[]; nextCursor: string | null; ordered?: boolean }>(
        `/v1/photos?limit=${PAGE_SIZE}${pageParam ? `&cursor=${encodeURIComponent(pageParam)}` : ''}`,
        // A page already on screen is being refetched (focus, reconnect), not asked for.
        {
          background: !!client
            .getQueryData<InfiniteData<unknown, string>>(queryKey)
            ?.pageParams.includes(pageParam),
        },
      ),
    getNextPageParam: (page) => page.nextCursor ?? undefined,
  });
  const pageCount = photos.data?.pages.length ?? 0;
  const ordered = photos.data?.pages[0]?.ordered === true;
  const end = useRef<HTMLDivElement>(null);
  const [nearEnd, setNearEnd] = useState(false);
  useEffect(() => {
    if (!end.current) return;
    const observer = new IntersectionObserver(
      (entries) => setNearEnd(entries.some((entry) => entry.isIntersecting)),
      { rootMargin: '1500px' },
    );
    observer.observe(end.current);
    return () => observer.disconnect();
  }, [photos.isPending]);
  // In order, read on as the end of the page comes near. Otherwise newest first only means
  // something once every page is in, so keep reading for a while.
  useEffect(() => {
    if (
      photos.hasNextPage &&
      !photos.isFetchingNextPage &&
      !photos.isError &&
      (ordered ? nearEnd : pageCount < AUTO_PAGES)
    )
      void photos.fetchNextPage();
  }, [photos.hasNextPage, photos.isFetchingNextPage, photos.isError, pageCount, ordered, nearEnd]);
  const all = useMemo(() => {
    const seen = new Set<string>();
    return (photos.data?.pages ?? [])
      .flatMap((page) => page.items)
      .filter((item) => {
        // Encrypted files are listed by the server unseen; their decrypted names decide here.
        if (seen.has(item.id) || !mediaKind(item)) return false;
        seen.add(item.id);
        return true;
      })
      .sort((a, b) => takenAt(b).localeCompare(takenAt(a)));
  }, [photos.data]);
  const shown = filter === 'all' ? all : all.filter((item) => mediaKind(item) === filter);
  const sections = useMemo(
    () =>
      grouping === 'device'
        ? deviceSections(shown, devices ?? [], backups ?? [], syncFolders ?? [])
        : monthSections(shown),
    [shown, grouping, devices, backups, syncFolders],
  );
  const counts = {
    photo: all.filter((item) => mediaKind(item) === 'photo').length,
    video: all.filter((item) => mediaKind(item) === 'video').length,
  };
  const loadingMore = photos.hasNextPage || photos.isFetchingNextPage;
  return (
    <div className="photos-page">
      <div className="page-heading">
        <div>
          <h1>Photos</h1>
          <p className="muted" aria-live="polite">
            {photos.isPending
              ? 'Finding photos and videos…'
              : `${plural(counts.photo, 'photo')} and ${plural(counts.video, 'video')}${loadingMore ? ' so far' : ''}`}
          </p>
        </div>
        <div className="heading-actions">
          <Segmented
            label="Show"
            value={filter}
            onValueChange={setFilter}
            options={[
              { value: 'all', label: 'All' },
              { value: 'photo', label: 'Photos' },
              { value: 'video', label: 'Videos' },
            ]}
          />
          <Segmented
            label="Group by"
            value={grouping}
            onValueChange={(next) => {
              setGrouping(next);
              remember('grouping', next);
            }}
            options={[
              { value: 'date', label: 'By date' },
              { value: 'device', label: 'By device' },
            ]}
          />
          <Segmented
            label="View"
            className="view-switch"
            value={view}
            onValueChange={(next) => {
              setView(next);
              remember('view', next);
            }}
            options={[
              { value: 'grid', label: 'Thumbnails', icon: <LayoutGrid />, iconOnly: true },
              { value: 'list', label: 'List view', icon: <List />, iconOnly: true },
            ]}
          />
        </div>
      </div>
      {photos.isPending ? (
        <ContentSkeleton label="Loading photos" />
      ) : photos.isError && !all.length ? (
        <LoadError title="Photos couldn’t be loaded." onRetry={() => void photos.refetch()} />
      ) : !shown.length && !loadingMore ? (
        <EmptyState
          icon={filter === 'video' ? <Film /> : <ImageIcon />}
          title={
            filter === 'video'
              ? 'No videos yet'
              : filter === 'photo'
                ? 'No photos yet'
                : 'No photos or videos yet'
          }
          description="Photos and videos you upload, sync or back up from any device appear here, in every format."
        />
      ) : (
        <>
          {sections.map((section) => (
            <section className="photo-section" key={section.key} aria-label={section.name}>
              <h2 className="photo-section-title">
                {section.device && <DeviceIcon device={section.device} />}
                <span>{section.name}</span>
                <small>{section.items.length}</small>
              </h2>
              {view === 'grid' ? (
                <ul className="photo-grid">
                  {section.items.map((item) => (
                    <li key={item.id}>
                      <PhotoTile item={item} request={request} onOpen={onOpen} />
                    </li>
                  ))}
                </ul>
              ) : (
                <PhotoList
                  items={section.items}
                  label={section.name}
                  place={(item) => placeName(item, devices ?? [], backups ?? [], syncFolders ?? [])}
                  onOpen={onOpen}
                />
              )}
            </section>
          ))}
          {photos.hasNextPage &&
          !ordered &&
          pageCount >= AUTO_PAGES &&
          !photos.isFetchingNextPage ? (
            <LoadMoreFiles onLoad={() => void photos.fetchNextPage()} loading={false} />
          ) : (
            loadingMore && (
              <p className="muted photo-loading" role="status">
                Looking for more photos and videos…
              </p>
            )
          )}
        </>
      )}
      <div ref={end} aria-hidden="true" />
    </div>
  );
}

const plural = (n: number, one: string) => `${n.toLocaleString()} ${n === 1 ? one : `${one}s`}`;

function monthSections(items: PhotoItem[]): Section[] {
  const sections = new Map<string, Section>();
  for (const item of items) {
    const name = month(takenAt(item));
    if (!sections.has(name)) sections.set(name, { key: name, name, items: [] });
    sections.get(name)!.items.push(item);
  }
  return [...sections.values()];
}

/** The devices a photo came from: its backup's device, every device syncing its folder, or none. */
function sources(
  item: PhotoItem,
  devices: Device[],
  backups: BackupRoot[],
  syncFolders: SyncFolderItem[],
): { key: string; name: string; device?: Device }[] {
  // Browsers never sync or back up folders.
  const candidates = devices.filter((device) => device.platform !== 'WEB');
  if (item.backupRootId) {
    const root = backups.find((entry) => entry.id === item.backupRootId);
    const device = root && candidates.find((candidate) => ownsBackup(candidate, root));
    const name = (device && deviceDisplayName(device)) ?? root?.deviceName ?? 'Other device';
    return [{ key: device?.id ?? `other:${name}`, name, device }];
  }
  if (item.syncFolderId) {
    const folder = syncFolders.find((entry) => entry.id === item.syncFolderId);
    const found = (folder?.syncDevices ?? []).map((entry) => ({
      key: entry.id,
      name: entry.name,
      device: candidates.find((candidate) => candidate.id === entry.id),
    }));
    if (found.length) return found;
    return [{ key: 'other:synced', name: 'Synced folders' }];
  }
  return [{ key: CLOUD, name: 'Uploaded to My Drive' }];
}

/**
 * One section per device, computers before phones, then devices no longer connected, then
 * files uploaded straight to My Drive. A photo in a folder synced on two devices shows on both.
 */
function deviceSections(
  items: PhotoItem[],
  devices: Device[],
  backups: BackupRoot[],
  syncFolders: SyncFolderItem[],
): Section[] {
  const sections = new Map<string, Section>();
  for (const item of items)
    for (const source of sources(item, devices, backups, syncFolders)) {
      if (!sections.has(source.key)) sections.set(source.key, { ...source, items: [] });
      sections.get(source.key)!.items.push(item);
    }
  return [...sections.values()].sort(
    (a, b) =>
      Number(a.key === CLOUD) - Number(b.key === CLOUD) ||
      Number(!a.device) - Number(!b.device) ||
      Number(!!a.device && isPhone(a.device)) - Number(!!b.device && isPhone(b.device)) ||
      a.name.localeCompare(b.name),
  );
}

function placeName(
  item: PhotoItem,
  devices: Device[],
  backups: BackupRoot[],
  syncFolders: SyncFolderItem[],
) {
  const found = sources(item, devices, backups, syncFolders);
  if (found[0].key === CLOUD) return 'My Drive';
  const names = found.map((source) => source.name).join(', ');
  return item.backupRootId ? `Backup from ${names}` : `Synced on ${names}`;
}

function PhotoList({
  items,
  label,
  place,
  onOpen,
}: {
  items: PhotoItem[];
  label: string;
  place: (item: PhotoItem) => string;
  onOpen: (item: DriveItem) => void;
}) {
  return (
    <DataTable className="files-table photo-table" label={label}>
      <thead>
        <tr>
          <th>Name</th>
          <th>From</th>
          <th>Date</th>
          <th className="numeric">Size</th>
        </tr>
      </thead>
      <tbody>
        {items.map((item) => {
          const date = fileDate(takenAt(item));
          const Icon = mediaKind(item) === 'video' ? Film : ImageIcon;
          return (
            <tr key={item.id}>
              <td>
                <button type="button" className="photo-name" onClick={() => onOpen(item)}>
                  <span className="file-entry-icon" data-kind="image">
                    <Icon aria-hidden="true" />
                  </span>
                  <span title={item.name}>{item.name}</span>
                </button>
              </td>
              <td className="muted">{place(item)}</td>
              <td title={date.full}>{date.date}</td>
              <td className="numeric">{fileSize(item.sizeBytes)}</td>
            </tr>
          );
        })}
      </tbody>
    </DataTable>
  );
}

/** Formats a browser can show, so other files (RAW, most HEIC) aren't fetched only to fail. */
const showable = {
  photo: new Set('jpg jpeg jpe jfif png gif webp avif bmp svg ico'.split(' ')),
  video: new Set('mp4 m4v mov qt webm mkv ogv 3gp'.split(' ')),
};
/**
 * Encrypted photos are decrypted whole in the browser, so only smaller ones get thumbnails.
 * Videos of any size do: only their index and a few frames are read (`videoThumbnail`).
 */
const MAX_ENCRYPTED_THUMBNAIL = 20_000_000;
/** Photos larger than this show their icon rather than downloading the original for a tile. */
const MAX_THUMBNAIL = 60_000_000;
function canShow(item: PhotoItem) {
  const kind = mediaKind(item);
  if (!kind) return false;
  const extension = item.name.toLowerCase().split('.').at(-1) ?? '';
  if (!showable[kind].has(extension)) return false;
  if (kind === 'video') return true;
  return item.sizeBytes <= (item.encrypted ? MAX_ENCRYPTED_THUMBNAIL : MAX_THUMBNAIL);
}

function PhotoTile({
  item,
  request,
  onOpen,
}: {
  item: PhotoItem;
  request: Request;
  onOpen: (item: DriveItem) => void;
}) {
  const kind = mediaKind(item) ?? 'photo';
  const tile = useRef<HTMLButtonElement>(null);
  const [visible, setVisible] = useState(false);
  const [shown, setShown] = useState<Shown | null>(() => drawn.get(thumbnailKey(item)) ?? null);
  const [failed, setFailed] = useState(false);
  useEffect(() => {
    if (failed || shown || visible || !tile.current) return;
    const observer = new IntersectionObserver(
      (entries) => {
        if (entries.some((entry) => entry.isIntersecting)) setVisible(true);
      },
      { rootMargin: '600px' },
    );
    observer.observe(tile.current);
    return () => observer.disconnect();
  }, [failed, shown, visible]);
  useEffect(() => {
    if (!visible || failed || shown) return;
    const controller = new AbortController();
    void thumbnail(item, request, controller.signal, () => onScreen(tile.current))
      .then((found) => {
        if (controller.signal.aborted) return;
        if (found) setShown(found);
        else setFailed(true);
      })
      .catch(() => {
        if (!controller.signal.aborted) setFailed(true);
      });
    return () => controller.abort();
  }, [visible, failed, item.id, item.currentVersionId]);
  const date = fileDate(takenAt(item));
  return (
    <button
      ref={tile}
      type="button"
      className="photo-tile"
      data-kind={kind}
      onClick={() => onOpen(item)}
      title={`${item.name} · ${date.date}`}
      aria-label={`${item.name}, ${kind}, ${date.date}`}
    >
      {shown && !failed ? (
        shown.video ? (
          <video
            src={`${shown.url}#t=0.5`}
            preload="metadata"
            muted
            playsInline
            tabIndex={-1}
            onError={() => setFailed(true)}
          />
        ) : (
          <img src={shown.url} alt="" decoding="async" onError={() => setFailed(true)} />
        )
      ) : (
        <span className="photo-placeholder" data-loading={!failed || undefined}>
          {kind === 'video' ? <Film aria-hidden="true" /> : <ImageIcon aria-hidden="true" />}
          {failed && <small>{item.name.split('.').at(-1)?.toUpperCase()}</small>}
        </span>
      )}
      {kind === 'video' && (
        <span className="photo-badge" aria-hidden="true">
          <Play />
        </span>
      )}
    </button>
  );
}

/** What a tile shows: a thumbnail, or a video's own link when no frame of it could be drawn. */
type Shown = { url: string; video?: boolean };
const thumbnailKey = (item: PhotoItem) => `${item.id}:${item.currentVersionId}`;
/**
 * Thumbnails drawn this session, by file version, so coming back to the page shows them at once.
 * Kept in memory only, never in the browser's storage, and dropped on sign-out.
 */
const drawn = new Map<string, Shown>();
const DRAWN_LIMIT = 2500;
function keep(item: PhotoItem, blob: Blob): Shown {
  const key = thumbnailKey(item);
  const shown = { url: URL.createObjectURL(blob) };
  drawn.set(key, shown);
  while (drawn.size > DRAWN_LIMIT) {
    const [oldest, entry] = drawn.entries().next().value!;
    drawn.delete(oldest);
    if (entry.url.startsWith('blob:')) URL.revokeObjectURL(entry.url);
  }
  return shown;
}
onBrowserCacheClear(() => {
  for (const entry of drawn.values())
    if (entry.url.startsWith('blob:')) URL.revokeObjectURL(entry.url);
  drawn.clear();
  links.clear();
});

/**
 * A tile's picture. The thumbnail an app saved (decrypted here for an encrypted file) when there
 * is one; otherwise the original, drawn small, and that drawing saved for every app after this.
 */
async function thumbnail(
  item: PhotoItem,
  request: Request,
  signal: AbortSignal,
  rank: () => number,
): Promise<Shown | null> {
  const link = await photoLink(request, item.id);
  signal.throwIfAborted();
  if (!link) return null;
  if (link.thumbnailUrl) {
    try {
      const bytes = await limited(saved, signal, rank, () =>
        link.encrypted
          ? openLink(link.thumbnailUrl!, signal)
          : fetchBytes(link.thumbnailUrl!, signal),
      );
      return keep(item, new Blob([bytes.slice()], { type: 'image/jpeg' }));
    } catch {
      // Drawn again from the original below.
      signal.throwIfAborted();
    }
  }
  if (!canShow(item)) return null;
  if (mediaKind(item) === 'video') {
    const frame = await limited(videos, signal, rank, async () => {
      // A decoder that never answers gives its slot up rather than holding back every other video.
      const drawn = await Promise.race([
        videoThumbnail(item, link, request, signal).catch(() => null),
        new Promise<null>((resolve) => setTimeout(() => resolve(null), 30_000)),
      ]);
      // Formats the decoder doesn't know: the browser's own player, which can't read encrypted files.
      return drawn ?? (link.encrypted ? null : await videoFrame(link.url, signal));
    });
    if (!frame) return link.encrypted ? null : { url: link.url, video: true };
    void save(request, item, frame, !!link.encrypted);
    signal.throwIfAborted();
    return keep(item, frame);
  }
  const drawn = await limited(link.encrypted ? sealed : plain, signal, rank, async () => {
    if (!link.encrypted) return drawBlob(await fetchBlob(link.url, signal));
    // The links' own download link, decrypted here: no further requests per photo.
    const plain = await openLink(link.url, signal);
    return drawBlob(new Blob([plain.slice()], { type: item.mimeType ?? '' }));
  });
  if (!drawn) return null;
  // Saved even if the tile has scrolled away meanwhile: the download is done, and every later
  // visit gets the thumbnail.
  if (drawn.small) void save(request, item, drawn.small, !!link.encrypted);
  signal.throwIfAborted();
  // A format the browser shows but can't draw (some SVGs): the original itself.
  const shown = drawn.small ?? drawn.original;
  return shown ? keep(item, shown) : null;
}
/** A thumbnail of `original`, and the original itself for when it can't be drawn small. */
const drawBlob = async (original: Blob) => ({
  small: await shrink(original).catch(() => null),
  original,
});
const vaultKeys = () => {
  try {
    return requireKeys();
  } catch {
    return undefined;
  }
};

/** For an app whose pages can't fetch storage links or hold the keys (the desktop's). */
type PhotoHost = {
  /** A link's bytes; `open` decrypts them. */
  read: (url: string, open?: boolean) => Promise<Uint8Array>;
  /** A thumbnail encrypted like a file. */
  seal: (plain: Uint8Array) => Promise<Uint8Array>;
  /** The file itself, read by range and decrypted if it needs to be. */
  file: (id: string) => Promise<{
    size: number;
    read: (begin: number, end: number) => Promise<Uint8Array>;
  }>;
};
let host: PhotoHost | undefined;
export function setPhotoHost(next: PhotoHost) {
  host = next;
}
async function fetchBlob(url: string, signal: AbortSignal) {
  if (host) {
    const bytes = await host.read(url);
    signal.throwIfAborted();
    return new Blob([bytes.slice()]);
  }
  const response = await fetch(url, { signal, credentials: 'omit', referrerPolicy: 'no-referrer' });
  if (!response.ok) throw new Error(`The download failed (${response.status}).`);
  return response.blob();
}
const fetchBytes = async (url: string, signal: AbortSignal) =>
  new Uint8Array(await (await fetchBlob(url, signal)).arrayBuffer());
/** The plaintext of an encrypted file or thumbnail read whole. */
const openLink = async (url: string, signal: AbortSignal) =>
  host ? host.read(url, true) : openWhole(requireKeys(), await fetchBytes(url, signal));

/** A JPEG of `source` PHOTO_THUMBNAIL_PX on its short side, cropped to at most 3:1. */
async function shrink(source: ImageBitmapSource) {
  const bitmap = await createImageBitmap(source);
  try {
    const short = Math.min(bitmap.width, bitmap.height);
    if (!short) return null;
    const cropW = Math.min(bitmap.width, short * 3);
    const cropH = Math.min(bitmap.height, short * 3);
    const scale = Math.min(1, PHOTO_THUMBNAIL_PX / short);
    const canvas = document.createElement('canvas');
    canvas.width = Math.max(1, Math.round(cropW * scale));
    canvas.height = Math.max(1, Math.round(cropH * scale));
    const context = canvas.getContext('2d');
    if (!context) return null;
    // JPEG has no transparency: transparent parts show white, not black.
    context.fillStyle = '#fff';
    context.fillRect(0, 0, canvas.width, canvas.height);
    context.imageSmoothingQuality = 'high';
    context.drawImage(
      bitmap,
      (bitmap.width - cropW) / 2,
      (bitmap.height - cropH) / 2,
      cropW,
      cropH,
      0,
      0,
      canvas.width,
      canvas.height,
    );
    return await new Promise<Blob | null>((resolve) =>
      canvas.toBlob(resolve, 'image/jpeg', PHOTO_THUMBNAIL_QUALITY),
    );
  } finally {
    bitmap.close();
  }
}

/**
 * A frame half a second into a video, drawn from as little of it as needed: its index and the
 * frames up to that moment, read by range from its link (and decrypted here, if encrypted).
 */
async function videoThumbnail(
  item: PhotoItem,
  link: NonNullable<Link>,
  request: Request,
  signal: AbortSignal,
) {
  const { ALL_FORMATS, CanvasSink, CustomSource, Input } = await import('mediabunny');
  const renew = async () => (await photoLink(request, item.id, true))?.url ?? link.url;
  let read: (begin: number, end: number) => Promise<Uint8Array>;
  let size: number;
  if (host) {
    ({ read, size } = await host.file(item.id));
  } else if (link.encrypted) {
    const keys = vaultKeys();
    if (!keys) return null;
    // The encrypting transport reports the original's size; the reader needs the stored one.
    const stored = encryptedSize(link.sizeBytes);
    const reader = await openEncryptedLink(link.url, stored, renew, signal, keys);
    read = reader.read;
    size = reader.size;
  } else {
    const fetchRange = rangeFetcher(link.url, renew);
    read = (begin, end) => fetchRange(begin, end, signal);
    size = link.sizeBytes;
  }
  const input = new Input({
    formats: ALL_FORMATS,
    source: new CustomSource({ getSize: () => size, read, prefetchProfile: 'network' }),
  });
  try {
    const track = await input.getPrimaryVideoTrack();
    if (!track || !(await track.canDecode())) return null;
    const short = Math.min(track.displayWidth, track.displayHeight) || PHOTO_THUMBNAIL_PX;
    const scale = Math.min(1, PHOTO_THUMBNAIL_PX / short);
    const sink = new CanvasSink(track, {
      width: Math.max(1, Math.round(track.displayWidth * scale)),
      height: Math.max(1, Math.round(track.displayHeight * scale)),
      fit: 'fill',
    });
    const first = await track.getFirstTimestamp();
    const frame = (await sink.getCanvas(first + 0.5)) ?? (await sink.getCanvas(first));
    signal.throwIfAborted();
    return frame ? await shrink(frame.canvas) : null;
  } finally {
    input.dispose();
  }
}

/** A frame half a second into a video, read from its link by the browser's own player. */
function videoFrame(url: string, signal: AbortSignal) {
  return new Promise<Blob | null>((resolve) => {
    const video = document.createElement('video');
    let settled = false;
    const done = (frame: Blob | null) => {
      if (settled) return;
      settled = true;
      clearTimeout(timer);
      signal.removeEventListener('abort', abort);
      video.removeAttribute('src');
      video.load();
      resolve(frame);
    };
    const abort = () => done(null);
    const timer = setTimeout(abort, 20_000);
    signal.addEventListener('abort', abort);
    // Drawing a frame needs storage's CORS answer; without it the canvas can't be read.
    video.crossOrigin = 'anonymous';
    video.muted = true;
    video.playsInline = true;
    video.preload = 'auto';
    video.onerror = abort;
    video.onloadedmetadata = () => {
      video.currentTime = Math.min(0.5, (video.duration || 0) / 2);
    };
    video.onseeked = () =>
      void shrink(video)
        .catch(() => null)
        .then(done);
    video.src = url;
  });
}

/**
 * Thumbnails this browser drew, saved together: a few seconds' worth share one request. An
 * encrypted file's is encrypted first, like any file. Failures only mean another app draws it.
 */
const MAX_SAVE_BYTES = 256 * 1024;
const SAVE_BATCH_BYTES = 600_000;
const unsaved: { id: string; versionId: string; data: string }[] = [];
let saveTimer: ReturnType<typeof setTimeout> | null = null;
async function save(request: Request, item: PhotoItem, blob: Blob, encrypted: boolean) {
  try {
    if (!item.currentVersionId) return;
    const plain = new Uint8Array(await blob.arrayBuffer());
    const bytes = !encrypted
      ? plain
      : host
        ? await host.seal(plain)
        : await sealWhole(requireKeys(), plain);
    if (bytes.length > MAX_SAVE_BYTES) return;
    unsaved.push({ id: item.id, versionId: item.currentVersionId, data: base64(bytes) });
    const size = unsaved.reduce((sum, entry) => sum + entry.data.length, 0);
    if (unsaved.length >= MAX_PHOTO_THUMBNAILS || size >= SAVE_BATCH_BYTES) flushSaves(request);
    else saveTimer ??= setTimeout(() => flushSaves(request), 2000);
  } catch {}
}
function flushSaves(request: Request) {
  if (saveTimer) clearTimeout(saveTimer);
  saveTimer = null;
  while (unsaved.length) {
    const batch: typeof unsaved = [];
    let size = 0;
    while (
      unsaved.length &&
      batch.length < MAX_PHOTO_THUMBNAILS &&
      (!batch.length || size + unsaved[0].data.length <= SAVE_BATCH_BYTES)
    ) {
      size += unsaved[0].data.length;
      batch.push(unsaved.shift()!);
    }
    void request('/v1/photos/thumbnails', { method: 'POST', body: { items: batch } }).catch(
      () => {},
    );
  }
}
function base64(bytes: Uint8Array) {
  let text = '';
  for (let i = 0; i < bytes.length; i += 0x8000)
    text += String.fromCharCode(...bytes.subarray(i, i + 0x8000));
  return btoa(text);
}

type Link = { url: string; sizeBytes: number; thumbnailUrl?: string; encrypted?: boolean } | null;
/**
 * Thumbnail links, asked for together: tiles that come into view within a moment of each other
 * share one request. A link lasts five minutes; a tile asking again after that gets a new one.
 */
const links = new Map<string, { link: Link; until: number }>();
let waiting: { id: string; resolve: (link: Link) => void; reject: (e: unknown) => void }[] = [];
let flush: ReturnType<typeof setTimeout> | null = null;
function photoLink(request: Request, id: string, fresh = false) {
  const known = links.get(id);
  if (!fresh && known && known.until > Date.now()) return Promise.resolve(known.link);
  return new Promise<Link>((resolve, reject) => {
    waiting.push({ id, resolve, reject });
    flush ??= setTimeout(() => {
      flush = null;
      const batch = waiting;
      waiting = [];
      for (let start = 0; start < batch.length; start += MAX_PHOTO_LINKS) {
        const part = batch.slice(start, start + MAX_PHOTO_LINKS);
        request<{
          items: (NonNullable<Link> & { id: string })[];
          expiresAt: string;
        }>('/v1/photos/links', {
          method: 'POST',
          body: { ids: [...new Set(part.map((entry) => entry.id))] },
        })
          .then(({ items, expiresAt }) => {
            // Leave a minute for the browser to finish fetching before the link expires.
            const until = new Date(expiresAt).getTime() - 60_000;
            for (const entry of part) {
              const link = items.find((found) => found.id === entry.id) ?? null;
              links.set(entry.id, { link, until });
              entry.resolve(link);
            }
          })
          .catch((e) => part.forEach((entry) => entry.reject(e)));
      }
    }, 50);
  });
}

/**
 * How many of each kind of work run at once: saved thumbnails are small; originals are
 * downloaded whole, and encrypted ones decrypted too, so fewer of those keep the tab usable.
 */
type Slots = {
  running: number;
  limit: number;
  queued: { resume: () => void; rank: () => number }[];
};
const saved: Slots = { running: 0, limit: 12, queued: [] };
const plain: Slots = { running: 0, limit: 8, queued: [] };
const sealed: Slots = { running: 0, limit: 6, queued: [] };
const videos: Slots = { running: 0, limit: 3, queued: [] };
/**
 * Runs `work` once a slot is free. A free slot goes to the waiting tile nearest the screen
 * (`onScreen`) as it is now, not as it was when the tile asked: tiles on screen first, top to
 * bottom, then those just off it. A tile that scrolled away while it waited gives its turn up
 * instead of downloading.
 */
async function limited<T>(
  slots: Slots,
  signal: AbortSignal,
  rank: () => number,
  work: () => Promise<T>,
) {
  if (slots.running >= slots.limit)
    await new Promise<void>((resume) => slots.queued.push({ resume, rank }));
  slots.running++;
  try {
    signal.throwIfAborted();
    return await work();
  } finally {
    slots.running--;
    let next = -1;
    let best = Infinity;
    slots.queued.forEach((waiting, n) => {
      const value = waiting.rank();
      if (value < best) [best, next] = [value, n];
    });
    if (slots.queued.length) slots.queued.splice(next < 0 ? 0 : next, 1)[0].resume();
  }
}
/** How far a tile is from being seen: its top on screen, else past the screen's height. */
function onScreen(element: Element | null) {
  if (!element) return Infinity;
  const box = element.getBoundingClientRect();
  const height = window.innerHeight;
  if (box.bottom >= 0 && box.top <= height) return Math.max(0, box.top);
  return height + (box.top > height ? box.top - height : -box.bottom);
}
