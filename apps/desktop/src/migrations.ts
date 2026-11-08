import { createWriteStream } from 'node:fs';
import { mkdir, rm, stat } from 'node:fs/promises';
import path from 'node:path';
import { Readable } from 'node:stream';
import { pipeline } from 'node:stream/promises';
import { ApiError } from '@harbor/api-client';
import {
  MIGRATION_FAILURES_LISTED,
  type Migration,
  type MigrationAccess,
  type MigrationProgress,
  type MigrationProvider,
  type MigrationsView,
} from '@harbor/contracts';
import type { DavDrive, DavEntry } from './drive-dav';

/**
 * Runs migrations started on the web (see packages/contracts/src/migrations.ts) when this computer
 * is the one chosen. Each file is downloaded from the other drive to a temporary file and uploaded
 * through the same path as WebDAV uploads, so encrypted accounts encrypt it here. Copying again
 * skips files already in the destination, which is also how an interrupted run resumes.
 */

/** A file or folder in the other drive, once listed. */
export type SourceEntry = {
  id: string;
  /** Null for files at the top of the drive. */
  parentId: string | null;
  name: string;
  folder: boolean;
  /** Bytes, when the service knows before downloading (not for exported documents). */
  size: number | null;
  modifiedAt: string | null;
  createdAt: string | null;
  /** Why it can't be copied, such as a Google Form; it's skipped and counted. */
  unsupported?: string;
};

/** One service, as the computer reads it. */
export interface SourceDrive {
  list(signal: AbortSignal): Promise<SourceEntry[]>;
  /** Saves the file's bytes at `to`, returning how many were written. */
  download(entry: SourceEntry, to: string, signal: AbortSignal): Promise<number>;
}
export type SourceFactory = (
  provider: MigrationProvider,
  token: () => Promise<string>,
) => SourceDrive;

export type MigrationHost = {
  request: <T = any>(endpoint: string, init?: { method?: string; body?: unknown }) => Promise<T>;
  drive: DavDrive;
  /** This computer: its sign-in's device id and its installation. */
  device: () => { id: string | null; publicId: string | null };
  /** Whether the account has migrations (the feature flag), checked before asking for them. */
  enabled: () => Promise<boolean>;
  /** A reason nothing can upload now, such as encrypted files being locked. */
  hold: () => string | null;
  tempDir: string;
  notify: (title: string, body: string) => void;
  sources?: SourceFactory;
};

/** Progress goes up at most this often; the answer is also how pause and cancel arrive. */
const REPORT_MS = 3000;
// Without a running migration, hints are followed at most this often…
const CHECK_MS = 30_000;
// …and the server is asked anyway this often, in case a hint was missed.
const POLL_MS = 15 * 60_000;
const HOLD_MS = 30_000;
const ATTEMPTS = 5;
const sleep = (ms: number, signal?: AbortSignal) =>
  new Promise<void>((resolve) => {
    const timer = setTimeout(resolve, ms);
    signal?.addEventListener('abort', () => (clearTimeout(timer), resolve()), { once: true });
  });

class Stopped extends Error {}
/** The migration can't go on: no access, no storage. */
class Fatal extends Error {}

export class MigrationRunner {
  private current?: { id: string; abort: AbortController };
  private checkedAt = 0;
  private checking?: Promise<void>;
  private timer?: ReturnType<typeof setInterval>;
  constructor(private host: MigrationHost) {}

  start() {
    this.timer ??= setInterval(() => void this.check(true), POLL_MS);
    this.timer.unref?.();
    void this.check(true);
  }

  async stop() {
    clearInterval(this.timer);
    this.timer = undefined;
    this.current?.abort.abort();
  }

  /** Something changed in the account; a new migration may be waiting for this computer. */
  wake() {
    if (!this.current && Date.now() - this.checkedAt >= CHECK_MS) void this.check();
  }

  async check(force = false) {
    if (this.current || (this.checking && !force)) return this.checking;
    this.checkedAt = Date.now();
    this.checking = (async () => {
      if (!(await this.host.enabled())) return;
      const { migrations }: MigrationsView = await this.host.request('/v1/migrations');
      const mine = migrations.find(
        (m) => ['QUEUED', 'RUNNING'].includes(m.status) && this.isMine(m),
      );
      if (mine && !this.current) void this.run(mine);
    })()
      .catch(() => {})
      .finally(() => (this.checking = undefined));
    return this.checking;
  }

  get running() {
    return this.current?.id ?? null;
  }

  private isMine(migration: Migration) {
    const device = this.host.device();
    return (
      migration.deviceId === device.id ||
      (!!migration.devicePublicId && migration.devicePublicId === device.publicId)
    );
  }

  private async run(migration: Migration) {
    const abort = new AbortController();
    this.current = { id: migration.id, abort };
    const job = new MigrationJob(this.host, migration, abort);
    try {
      await job.run();
    } finally {
      this.current = undefined;
      await rm(path.join(this.host.tempDir, migration.id), { recursive: true, force: true }).catch(
        () => {},
      );
    }
  }
}

/** One run of one migration. */
class MigrationJob {
  private progress: MigrationProgress;
  private reportedAt = 0;
  private folderId: string | null;
  private access?: MigrationAccess;
  private reporting?: Promise<void>;
  constructor(
    private host: MigrationHost,
    private migration: Migration,
    private abort: AbortController,
  ) {
    // Totals start again: this run counts what it finds already copied.
    this.progress = {
      ...migration.progress,
      filesTotal: null,
      bytesTotal: null,
      filesDone: 0,
      bytesDone: 0,
      filesSkipped: 0,
      filesFailed: 0,
      failures: [],
      current: null,
      waiting: null,
    };
    this.folderId = migration.folderId;
  }

  private get signal() {
    return this.abort.signal;
  }

  async run() {
    const sources = this.host.sources ?? defaultSources;
    const source = sources(this.migration.provider, () => this.token());
    try {
      await this.report('RUNNING', true);
      const entries = await this.attempt(() => source.list(this.signal));
      const plan = planCopy(entries);
      this.progress.filesTotal = plan.files.length;
      this.progress.bytesTotal = plan.files.reduce((sum, f) => sum + (f.entry.size ?? 0), 0);
      this.progress.filesSkipped = plan.skipped.length;
      const root = await this.destination();
      await this.report('RUNNING', true);
      await this.copyFolder(source, plan, null, root, []);
      this.progress.current = null;
      this.progress.waiting = null;
      await this.report('COMPLETED', true);
      const p = this.progress;
      this.host.notify(
        `${this.migration.folderName} copied`,
        p.filesFailed
          ? `${p.filesDone.toLocaleString()} files copied; ${p.filesFailed.toLocaleString()} couldn't be. See Settings on the web.`
          : `${p.filesDone.toLocaleString()} files are in My Drive › ${this.migration.folderName}.`,
      );
    } catch (error) {
      if (error instanceof Stopped || this.signal.aborted) return;
      this.progress.current = null;
      await this.report('FAILED', true, (error as Error).message).catch(() => {});
      this.host.notify(`Copying ${this.migration.folderName} stopped`, (error as Error).message);
    }
  }

  private async token() {
    if (!this.access || Date.parse(this.access.expiresAt) - Date.now() < 120_000) {
      try {
        this.access = await this.host.request<MigrationAccess>(
          `/v1/migrations/${this.migration.id}/access`,
          { method: 'POST' },
        );
      } catch (error) {
        if (error instanceof ApiError && error.code === 'MIGRATION_STATE') throw new Stopped();
        if (error instanceof ApiError && error.code === 'MIGRATION_RECONNECT')
          throw new Fatal(error.message);
        throw error;
      }
    }
    return this.access.token;
  }

  /** The My Drive folder files go into: the one an earlier run made, or a new one. */
  private async destination(): Promise<string> {
    if (this.folderId) {
      const existing = await this.host
        .request(`/v1/drive/items/${encodeURIComponent(this.folderId)}`)
        .then((r: { item: { deletedAt?: string | null; type: string } }) => r.item)
        .catch(() => null);
      if (existing && !existing.deletedAt && existing.type === 'FOLDER') return this.folderId;
    }
    const taken = new Set(
      (await this.host.drive.list(null)).map((entry) => entry.name.normalize('NFC').toLowerCase()),
    );
    let name = this.migration.folderName;
    for (let n = 2; taken.has(name.toLowerCase()); n++)
      name = `${this.migration.folderName} (${n})`;
    const folder = await this.host.drive.mkdir(null, name);
    this.folderId = folder.id;
    this.migration = { ...this.migration, folderName: name };
    return folder.id;
  }

  private async copyFolder(
    source: SourceDrive,
    plan: CopyPlan,
    sourceId: string | null,
    targetId: string,
    trail: string[],
  ) {
    const children = plan.children.get(sourceId) ?? [];
    if (!children.length) return;
    const existing = new Map<string, DavEntry>(
      (await this.attempt(() => this.host.drive.list(targetId))).map((e) => [key(e.name), e]),
    );
    for (const child of children) {
      if (this.signal.aborted) throw new Stopped();
      const where = [...trail, child.name];
      if (child.entry.folder) {
        let folder = existing.get(key(child.name));
        if (folder && !folder.folder) {
          this.fail(where, 'A file with this name is already in My Drive.');
          continue;
        }
        folder ??= await this.attempt(() => this.host.drive.mkdir(targetId, child.name));
        await this.copyFolder(source, plan, child.entry.id, folder.id, where);
        continue;
      }
      if (child.entry.unsupported) continue;
      const there = existing.get(key(child.name));
      // Exported documents have no size before downloading, so any copy there counts.
      if (
        there &&
        !there.folder &&
        (child.entry.size === null || there.size === child.entry.size)
      ) {
        this.done(child.entry.size ?? there.size);
        continue;
      }
      if (there?.folder) {
        this.fail(where, 'A folder with this name is already in My Drive.');
        continue;
      }
      await this.copyFile(source, child, targetId, there, where);
    }
  }

  private async copyFile(
    source: SourceDrive,
    child: PlannedEntry,
    targetId: string,
    existing: DavEntry | undefined,
    where: string[],
  ) {
    this.progress.current = where.join('/');
    void this.report('RUNNING');
    const dir = path.join(this.host.tempDir, this.migration.id);
    await mkdir(dir, { recursive: true });
    const temp = path.join(dir, child.entry.id.replace(/[^A-Za-z0-9_-]/g, '_'));
    try {
      const size = await this.attempt(() => source.download(child.entry, temp, this.signal));
      await this.whileHeld();
      await this.attempt(() =>
        this.host.drive.write(
          targetId,
          child.name,
          temp,
          existing,
          child.entry.modifiedAt ? new Date(child.entry.modifiedAt) : undefined,
        ),
      );
      this.done(size);
    } catch (error) {
      if (error instanceof Stopped || error instanceof Fatal || this.signal.aborted) throw error;
      if (error instanceof ApiError && error.code === 'STORAGE_QUOTA_EXCEEDED')
        throw new Fatal('Your harbor0 storage is full. Get more storage, then try again.');
      this.fail(where, (error as Error).message);
    } finally {
      await rm(temp, { force: true }).catch(() => {});
    }
  }

  /** Encrypted files locked on this computer: wait, saying why, rather than fail every file. */
  private async whileHeld() {
    for (let reason = this.host.hold(); reason; reason = this.host.hold()) {
      this.progress.waiting = reason;
      await this.report('RUNNING', true);
      await sleep(HOLD_MS, this.signal);
      if (this.signal.aborted) throw new Stopped();
    }
    if (this.progress.waiting) {
      this.progress.waiting = null;
      void this.report('RUNNING');
    }
  }

  /** Retries what fails for a moment (the network, rate limits); the rest fails at once. */
  private async attempt<T>(task: () => Promise<T>): Promise<T> {
    for (let attempt = 1; ; attempt++) {
      try {
        return await task();
      } catch (error) {
        if (error instanceof Stopped || error instanceof Fatal || this.signal.aborted) throw error;
        if (!retryable(error) || attempt >= ATTEMPTS) throw error;
        await sleep(Math.min(60_000, 2000 * 2 ** attempt), this.signal);
        if (this.signal.aborted) throw new Stopped();
      }
    }
  }

  private done(bytes: number) {
    this.progress.filesDone++;
    this.progress.bytesDone += bytes;
    void this.report('RUNNING');
  }

  private fail(where: string[], reason: string) {
    this.progress.filesFailed++;
    if (this.progress.failures.length < MIGRATION_FAILURES_LISTED)
      this.progress.failures.push({
        path: where.join('/').slice(0, 1024),
        reason: reason.slice(0, 300),
      });
    void this.report('RUNNING');
  }

  /** Tells the server how it's going; stops the run if it was paused or cancelled. */
  private async report(status: 'RUNNING' | 'COMPLETED' | 'FAILED', now = false, error?: string) {
    if (!now && (this.reporting || Date.now() - this.reportedAt < REPORT_MS)) return;
    await this.reporting?.catch(() => {});
    this.reportedAt = Date.now();
    const sent = this.host
      .request<{ migration: Migration }>(`/v1/migrations/${this.migration.id}/report`, {
        method: 'POST',
        body: {
          status,
          progress: this.progress,
          ...(this.folderId ? { folderId: this.folderId } : {}),
          ...(error ? { error: error.slice(0, 500) } : {}),
        },
      })
      .then(({ migration }) => {
        if (!['QUEUED', 'RUNNING', 'COMPLETED'].includes(migration.status) && status === 'RUNNING')
          this.abort.abort();
      });
    this.reporting = sent.catch(() => {}).finally(() => (this.reporting = undefined));
    if (now) {
      await sent;
      if (this.signal.aborted && status === 'RUNNING') throw new Stopped();
    }
  }
}

const key = (name: string) => name.normalize('NFC').toLowerCase();
function retryable(error: unknown) {
  if (error instanceof ApiError) return error.status === 429 || error.status >= 500;
  if (error instanceof SourceError) return error.retry;
  // Network failures, timeouts.
  return error instanceof TypeError || (error as { code?: string })?.code === 'ECONNRESET';
}

export class SourceError extends Error {
  constructor(
    message: string,
    public retry = false,
  ) {
    super(message);
  }
}

type PlannedEntry = { entry: SourceEntry; name: string };
type CopyPlan = {
  /** Each folder's children with the names they get in My Drive; null is the top. */
  children: Map<string | null, PlannedEntry[]>;
  files: PlannedEntry[];
  skipped: SourceEntry[];
};

/**
 * Lays the other drive out as My Drive will hold it. Names My Drive refuses are cleaned up, and
 * names two siblings share (other drives allow it) get “ (2)”, oldest first, so every run
 * arrives at the same names and finds what an earlier run copied.
 */
export function planCopy(entries: SourceEntry[]): CopyPlan {
  const folders = new Set(entries.filter((e) => e.folder).map((e) => e.id));
  const byParent = new Map<string | null, SourceEntry[]>();
  for (const entry of entries) {
    // Items whose folder isn't in the listing (it belongs to someone else) go at the top.
    const parent = entry.parentId && folders.has(entry.parentId) ? entry.parentId : null;
    byParent.set(parent, [...(byParent.get(parent) ?? []), entry]);
  }
  const children = new Map<string | null, PlannedEntry[]>();
  const files: PlannedEntry[] = [];
  const skipped: SourceEntry[] = [];
  for (const [parent, list] of byParent) {
    const used = new Set<string>();
    const sorted = [...list].sort(
      (a, b) => (a.createdAt ?? '').localeCompare(b.createdAt ?? '') || a.id.localeCompare(b.id),
    );
    const planned = sorted.map((entry) => {
      const base = cleanName(entry.name);
      let name = base;
      for (let n = 2; used.has(key(name)); n++) name = numbered(base, n, entry.folder);
      used.add(key(name));
      return { entry, name };
    });
    children.set(parent, planned);
    for (const item of planned) {
      if (item.entry.folder) continue;
      if (item.entry.unsupported) skipped.push(item.entry);
      else files.push(item);
    }
  }
  // Folders looping into each other (a broken listing) aren't reached from the top, so they end.
  return { children, files, skipped };
}

export function cleanName(name: string) {
  let clean = name
    .normalize('NFC')
    .replace(/[\\/\u0000-\u001f\u007f]/g, '_')
    .trim();
  if (!clean || clean === '.' || clean === '..') clean = '_';
  if (clean.length > 240) {
    const ext = path.extname(clean).slice(0, 20);
    clean = clean.slice(0, 240 - ext.length) + ext;
  }
  return clean;
}
function numbered(name: string, n: number, folder: boolean) {
  const ext = folder ? '' : path.extname(name);
  const stem = ext ? name.slice(0, -ext.length) : name;
  return cleanName(`${stem} (${n})${ext}`);
}

/** Google's own formats come over as the Office file that opens them elsewhere. */
const GOOGLE_EXPORTS: Record<string, { mime: string; ext: string }> = {
  'application/vnd.google-apps.document': {
    mime: 'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
    ext: '.docx',
  },
  'application/vnd.google-apps.spreadsheet': {
    mime: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
    ext: '.xlsx',
  },
  'application/vnd.google-apps.presentation': {
    mime: 'application/vnd.openxmlformats-officedocument.presentationml.presentation',
    ext: '.pptx',
  },
  'application/vnd.google-apps.drawing': { mime: 'application/pdf', ext: '.pdf' },
  'application/vnd.google-apps.jam': { mime: 'application/pdf', ext: '.pdf' },
  'application/vnd.google-apps.script': {
    mime: 'application/vnd.google-apps.script+json',
    ext: '.json',
  },
};
const GOOGLE_FOLDER = 'application/vnd.google-apps.folder';
type GoogleFile = {
  id: string;
  name: string;
  mimeType: string;
  parents?: string[];
  size?: string;
  modifiedTime?: string;
  createdTime?: string;
  exportLinks?: Record<string, string>;
};

/** Google Drive's v3 API, read-only. Lists every file the account owns in My Drive. */
export function googleDrive(
  token: () => Promise<string>,
  fetchImpl: typeof fetch = fetch,
): SourceDrive {
  const exports = new Map<string, string>();
  const call = async (url: string, signal: AbortSignal) => {
    const response = await fetchImpl(url, {
      headers: { Authorization: `Bearer ${await token()}` },
      signal,
    });
    if (response.ok) return response;
    const body = (await response.json().catch(() => ({}))) as {
      error?: { message?: string; errors?: { reason?: string }[] };
    };
    const reason = body.error?.errors?.[0]?.reason ?? '';
    const limited =
      response.status === 429 ||
      response.status >= 500 ||
      ['rateLimitExceeded', 'userRateLimitExceeded', 'backendError'].includes(reason);
    throw new SourceError(
      reason === 'cannotDownloadFile' || reason === 'cannotExportFile'
        ? 'Its owner turned off downloading in Google Drive.'
        : reason === 'exportSizeLimitExceeded'
          ? 'Google Drive refused to export it; it is too large.'
          : `Google Drive refused (${response.status}${reason ? ` ${reason}` : ''}).`,
      limited,
    );
  };
  return {
    async list(signal) {
      const root = (await (
        await call('https://www.googleapis.com/drive/v3/files/root?fields=id', signal)
      ).json()) as { id: string };
      const out: SourceEntry[] = [];
      let pageToken = '';
      do {
        const params = new URLSearchParams({
          q: "'me' in owners and trashed = false",
          spaces: 'drive',
          pageSize: '1000',
          fields:
            'nextPageToken,files(id,name,mimeType,parents,size,modifiedTime,createdTime,exportLinks)',
          ...(pageToken ? { pageToken } : {}),
        });
        const page = (await (
          await call(`https://www.googleapis.com/drive/v3/files?${params}`, signal)
        ).json()) as { nextPageToken?: string; files: GoogleFile[] };
        for (const file of page.files) out.push(entryFor(file, root.id, exports));
        pageToken = page.nextPageToken ?? '';
      } while (pageToken);
      return out;
    },
    async download(entry, to, signal) {
      const exported = exports.get(entry.id);
      const response = await call(
        exported ??
          `https://www.googleapis.com/drive/v3/files/${encodeURIComponent(entry.id)}?alt=media&acknowledgeAbuse=true`,
        signal,
      );
      if (!response.body) throw new SourceError('Google Drive sent nothing.', true);
      await pipeline(Readable.fromWeb(response.body as never), createWriteStream(to), { signal });
      const { size } = await stat(to);
      if (entry.size !== null && size !== entry.size)
        throw new SourceError('The download was cut short.', true);
      return size;
    },
  };
}

function entryFor(file: GoogleFile, rootId: string, exports: Map<string, string>): SourceEntry {
  const parent = file.parents?.[0];
  const base = {
    id: file.id,
    parentId: !parent || parent === rootId ? null : parent,
    modifiedAt: file.modifiedTime ?? null,
    createdAt: file.createdTime ?? null,
  };
  if (file.mimeType === GOOGLE_FOLDER)
    return { ...base, name: file.name, folder: true, size: null };
  if (!file.mimeType.startsWith('application/vnd.google-apps.'))
    return { ...base, name: file.name, folder: false, size: Number(file.size ?? 0) };
  const target = GOOGLE_EXPORTS[file.mimeType];
  const link = target && file.exportLinks?.[target.mime];
  if (!target || !link)
    return {
      ...base,
      name: file.name,
      folder: false,
      size: null,
      unsupported: "Google Drive can't export this kind of item.",
    };
  exports.set(file.id, link);
  const name = file.name.toLowerCase().endsWith(target.ext) ? file.name : file.name + target.ext;
  return { ...base, name, folder: false, size: null };
}

const defaultSources: SourceFactory = (provider, token) => {
  if (provider === 'GOOGLE_DRIVE') return googleDrive(token);
  throw new Fatal('This version of harbor0 can’t copy from that service yet. Update harbor0.');
};
