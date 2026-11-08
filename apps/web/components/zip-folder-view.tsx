'use client';
import { useEffect, useMemo, useState, type ReactNode } from 'react';
import { useQuery } from '@tanstack/react-query';
import {
  ChevronRight,
  CopyPlus,
  Download,
  FileArchive,
  Folder,
  LayoutGrid,
  List,
  X,
} from 'lucide-react';
import type { ApiClient } from '@harbor/api-client';
import type { DriveItem } from '@harbor/contracts';
import type { UploadEntry } from '../lib/dropped-files';
import { defaultDriveFilters, driveView } from '../lib/drive-view';
import { fileSize } from '../lib/file-metadata';
import {
  archivePreviewLoader,
  copyPlan,
  type OpenArchive,
  COPY_MAX,
  listFolder,
  openArchive,
  saveArchiveFile,
  unpack,
  type ZipEntry,
} from '../lib/zip-drive';
import { unreadable } from '../lib/zip-reader';
import { FileCollection, FileCollectionSkeleton } from './file-collection';
import { FilePreview } from './lazy-file-preview';
import { EmptyState } from './empty-state';
import { Alert } from './ui/alert';
import { Badge } from './ui/badge';
import { Button } from './ui/button';
import { ActionsMenu, MenuItem, MenuSeparator } from './ui/menu';
import { Segmented } from './ui/segmented';

const filters = { ...defaultDriveFilters, sort: 'name-asc' };

/**
 * A ZIP archive browsed like a folder: read in place, never changed. How it moves between folders
 * and reads the archive is up to the app: the web uses its router, the desktop app its own state.
 */
export function ZipFolderView({
  api,
  zipId,
  path,
  userId,
  pdf,
  breadcrumbs,
  go,
  crumb,
  open: openZip = openArchive,
  onNavigate,
  destination,
  onCopy,
  onDownloadArchive,
}: {
  api: ApiClient;
  zipId: string;
  /** The folder open inside the archive; '' is its top level. */
  path: string;
  userId: string;
  pdf: boolean;
  /** Crumbs up to the folder that holds the archive. */
  breadcrumbs: ReactNode;
  /** Opens a folder inside the archive. */
  go: (path: string) => void;
  /** A breadcrumb to a folder inside the archive. */
  crumb: (path: string, label: ReactNode, current: boolean) => ReactNode;
  /** Reads the archive's listing; by default through signed links from this page. */
  open?: (api: ApiClient, id: string) => Promise<OpenArchive>;
  onNavigate: () => void;
  /** Where copies go, e.g. the folder that holds the archive. */
  destination: string;
  onCopy: (entries: UploadEntry[]) => void;
  onDownloadArchive: (item: DriveItem) => Promise<unknown>;
}) {
  // One listing per archive; folders inside it are read from the same listing, so moving
  // around the archive reads nothing new. A new version shows on the next visit.
  const archive = useQuery({
    queryKey: ['zip', userId, zipId],
    queryFn: () => openZip(api, zipId),
    staleTime: 60_000,
    refetchOnWindowFocus: false,
    gcTime: 10 * 60_000,
    retry: false,
  });
  const [grid, setGrid] = useState(false);
  const [selected, setSelected] = useState<string[]>([]);
  const [previewing, setPreviewing] = useState<ZipEntry | null>(null);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [copying, setCopying] = useState<{ done: number; total: number } | null>(null);
  useEffect(() => {
    setSelected([]);
    setError('');
  }, [path, zipId]);
  const data = archive.data;
  const folderMissing = !!data && !!path && !data.tree.folders.has(path);
  const items = useMemo(
    () => (data && !folderMissing ? driveView(listFolder(data, path), filters) : []),
    [data, path, folderMissing],
  );
  const load = useMemo(() => (data ? archivePreviewLoader(data) : undefined), [data]);
  const selection = items.filter((item) => selected.includes(item.id));
  const title = path ? path.slice(path.lastIndexOf('/') + 1) : (data?.item.name ?? 'Archive');

  function open(item: ZipEntry) {
    if (item.type === 'FOLDER') {
      onNavigate();
      go(item.path);
    } else setPreviewing(item);
  }
  async function download(item: ZipEntry) {
    const file = data?.tree.byPath.get(item.path);
    if (!data || !file) return;
    const problem = unreadable(file);
    if (problem) return setError(problem);
    setError('');
    try {
      await saveArchiveFile(data, file);
    } catch (e) {
      setError((e as Error).message || 'Could not download this file. Try again.');
    }
  }
  async function copy(entries: ZipEntry[]) {
    if (!data || copying) return;
    const plan = copyPlan(
      data,
      entries.map((entry) => entry.path),
      path,
    );
    if (!plan.files.length)
      return setError(
        plan.skipped
          ? 'These files are password-protected or compressed in a way the browser can’t unpack. Download the archive to open them.'
          : 'There are no files to copy here.',
      );
    if (plan.size > COPY_MAX)
      return setError(
        `That’s ${fileSize(plan.size)} unpacked, more than the ${fileSize(COPY_MAX)} that can be copied out at once. Copy fewer files, or download the archive and upload what you need.`,
      );
    setError('');
    setNotice('');
    setCopying({ done: 0, total: plan.files.length });
    try {
      onCopy(await unpack(data, plan, (done) => setCopying({ done, total: plan.files.length })));
      setSelected([]);
      setNotice(
        `Copying ${plan.files.length.toLocaleString()} ${plan.files.length === 1 ? 'file' : 'files'} to ${destination}.` +
          (plan.skipped
            ? ` ${plan.skipped.toLocaleString()} password-protected or unsupported ${plan.skipped === 1 ? 'file was' : 'files were'} left out.`
            : ''),
      );
    } catch (e) {
      setError((e as Error).message || 'Could not unpack these files. Try again.');
    } finally {
      setCopying(null);
    }
  }
  function menu(item: ZipEntry) {
    return (
      <ActionsMenu label={`Actions for ${item.name}`}>
        <MenuItem onClick={() => open(item)}>
          {item.type === 'FOLDER' ? 'Open' : 'Preview'}
        </MenuItem>
        <MenuSeparator />
        {item.type === 'FILE' && <MenuItem onClick={() => void download(item)}>Download</MenuItem>}
        <MenuItem disabled={!!copying} onClick={() => void copy([item])}>
          Copy to {destination}
        </MenuItem>
      </ActionsMenu>
    );
  }
  const crumbs = path.split('/').filter(Boolean);
  return (
    <section className="drive-workspace zip-folder" aria-label="Archive browser">
      <div className="drive-tabs">
        <div className="page-heading drive-heading">
          <div className="drive-title">
            <h1>{title}</h1>
            <Badge title="Files in an archive can be previewed, downloaded and copied, but not changed.">
              Read-only archive
            </Badge>
          </div>
          {data && (
            <div className="zip-actions">
              <Button variant="outline" onClick={() => void onDownloadArchive(data.item)}>
                <FileArchive />
                Download archive
              </Button>
              <Button disabled={!!copying || !items.length} onClick={() => void copy(items)}>
                <CopyPlus />
                {path ? 'Copy folder contents' : 'Copy all'} to {destination}
              </Button>
            </div>
          )}
        </div>
        <nav className="breadcrumbs drive-breadcrumbs" aria-label="Drive location">
          {breadcrumbs}
          <span>
            <ChevronRight size={14} />
            {crumb('', data?.item.name ?? 'Archive', !path)}
          </span>
          {crumbs.map((name, i) => {
            const target = crumbs.slice(0, i + 1).join('/');
            return (
              <span key={target}>
                <ChevronRight size={14} />
                {crumb(target, name, target === path)}
              </span>
            );
          })}
        </nav>
        {error && (
          <Alert tone="error" dismissLabel="Dismiss error" onDismiss={() => setError('')}>
            {error}
          </Alert>
        )}
        {notice && (
          <Alert tone="success" dismissLabel="Dismiss" onDismiss={() => setNotice('')}>
            {notice}
          </Alert>
        )}
        <div className="drive-toolbar">
          {selection.length ? (
            <>
              <strong aria-live="polite">{selection.length} selected</strong>
              <div className="drive-selection-actions">
                {selection.length === 1 && selection[0].type === 'FILE' && (
                  <Button variant="ghost" onClick={() => void download(selection[0])}>
                    <Download aria-hidden="true" />
                    <span>Download</span>
                  </Button>
                )}
                <Button variant="ghost" disabled={!!copying} onClick={() => void copy(selection)}>
                  <CopyPlus aria-hidden="true" />
                  <span>Copy to {destination}</span>
                </Button>
              </div>
              <Button
                variant="ghost"
                size="icon"
                className="drive-clear-selection"
                onClick={() => setSelected([])}
                aria-label="Clear selection"
              >
                <X />
              </Button>
            </>
          ) : (
            <>
              <span className="drive-item-count" role="status">
                {copying
                  ? `Unpacking ${copying.done.toLocaleString()} of ${copying.total.toLocaleString()} files…`
                  : archive.isPending
                    ? 'Reading archive…'
                    : data
                      ? `${items.length} ${items.length === 1 ? 'item' : 'items'}`
                      : ''}
              </span>
              <Segmented
                label="View"
                className="view-switch"
                value={grid ? 'grid' : 'list'}
                onValueChange={(view) => setGrid(view === 'grid')}
                options={[
                  { value: 'list', label: 'List view', icon: <List />, iconOnly: true },
                  { value: 'grid', label: 'Grid view', icon: <LayoutGrid />, iconOnly: true },
                ]}
              />
            </>
          )}
        </div>
      </div>
      <div className="drive-content">
        {archive.isPending ? (
          <FileCollectionSkeleton grid={grid} />
        ) : archive.isError ? (
          <EmptyState
            icon={<FileArchive />}
            title="Can’t open this archive"
            description={
              archive.error.name === 'PreviewUnavailable'
                ? archive.error.message
                : 'Could not read this archive. Check your connection and try again.'
            }
            actions={
              <Button variant="outline" onClick={() => void archive.refetch()}>
                Try again
              </Button>
            }
          />
        ) : folderMissing ? (
          <EmptyState
            icon={<Folder />}
            title="This folder isn’t in the archive"
            description="The archive may have changed since this link was made."
            actions={
              <Button
                variant="outline"
                onClick={() => {
                  onNavigate();
                  go('');
                }}
              >
                Open the archive
              </Button>
            }
          />
        ) : !items.length ? (
          <EmptyState
            icon={<Folder />}
            title="This folder is empty"
            description="There are no files here in the archive."
          />
        ) : (
          <FileCollection
            items={items}
            folderSizes={Object.fromEntries(
              items
                .filter((item) => item.type === 'FOLDER')
                .map((item) => [item.id, fileSize(item.sizeBytes)]),
            )}
            userId={userId}
            grid={grid}
            selected={selected}
            onSelectionChange={setSelected}
            onOpen={open}
            renderActions={menu}
            label={`Files in ${title}`}
          />
        )}
      </div>
      {previewing && load && (
        <FilePreview
          pdf={pdf}
          cacheScope={`${userId}:zip:${zipId}:${data?.item.currentVersionId}`}
          item={previewing}
          load={load}
          onClose={() => setPreviewing(null)}
          onDownload={() => void download(previewing)}
        />
      )}
    </section>
  );
}
