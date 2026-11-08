import { z } from 'zod';
import type { FlagKey } from './flags';

/**
 * Migrations copy everything from another cloud drive into My Drive. People connect the other
 * service and start a migration on the web; one of their computers does the copying, so files go
 * up the way that computer uploads them (encrypted, on an encrypted account) and the server only
 * hands it short-lived access to the other service.
 */

/** Services a migration can copy from, built or not. */
export const migrationProvider = z.enum(['GOOGLE_DRIVE', 'DROPBOX']);
export type MigrationProvider = z.infer<typeof migrationProvider>;
/** Services a migration can copy from. Each is offered only to accounts with its feature flag. */
export const migrationProviders: Record<
  MigrationProvider,
  { label: string; folderName: string; available: boolean; flag: FlagKey | null }
> = {
  GOOGLE_DRIVE: {
    label: 'Google Drive',
    folderName: 'Google Drive',
    available: true,
    flag: 'migrations-gdrive',
  },
  DROPBOX: { label: 'Dropbox', folderName: 'Dropbox', available: false, flag: null },
};
/** Flags that turn on migrations from some service; any one of them shows the feature. */
export const migrationFlags = Object.values(migrationProviders)
  .map((provider) => provider.flag)
  .filter((flag): flag is FlagKey => !!flag);

/** Oldest desktop build that runs migrations; older computers aren't offered. */
export const MIGRATION_MIN_DESKTOP = '0.1.18';
/** Whether a desktop build is new enough to run migrations (test builds count as their release). */
export function runsMigrations(appVersion: string | null | undefined) {
  if (!appVersion) return false;
  const parts = (v: string) => v.split(/[.+-]/).map((p) => Number.parseInt(p, 10) || 0);
  const [a, b] = [parts(appVersion), parts(MIGRATION_MIN_DESKTOP)];
  for (let i = 0; i < 3; i++) if (a[i] !== b[i]) return (a[i] ?? 0) > (b[i] ?? 0);
  return true;
}
/** Connected accounts one harbor0 account may keep. */
export const MAX_MIGRATION_CONNECTIONS = 10;
/** Files whose copy failed that a migration lists by name; the rest are only counted. */
export const MIGRATION_FAILURES_LISTED = 20;

export const migrationConnectionSchema = z.object({
  id: z.string(),
  provider: migrationProvider,
  /** The other service's account, as it names itself. */
  accountEmail: z.string().nullable(),
  accountName: z.string().nullable(),
  connectedAt: z.string(),
});
export type MigrationConnection = z.infer<typeof migrationConnectionSchema>;

/**
 * QUEUED until the computer picks it up. People pause, resume and cancel from the web; the
 * computer moves it to RUNNING, COMPLETED or FAILED.
 */
export const migrationStatus = z.enum([
  'QUEUED',
  'RUNNING',
  'PAUSED',
  'COMPLETED',
  'FAILED',
  'CANCELLED',
]);
export type MigrationStatus = z.infer<typeof migrationStatus>;
export const activeMigrationStatuses: MigrationStatus[] = ['QUEUED', 'RUNNING', 'PAUSED'];

export const migrationProgressSchema = z.object({
  /** Null until the computer has listed the other drive. */
  filesTotal: z.number().int().min(0).nullable(),
  bytesTotal: z.number().int().min(0).nullable(),
  /** Copied now or in an earlier run. */
  filesDone: z.number().int().min(0),
  bytesDone: z.number().int().min(0),
  /** Files that can't be copied, such as Google Forms and shortcuts. */
  filesSkipped: z.number().int().min(0),
  filesFailed: z.number().int().min(0),
  failures: z
    .array(z.object({ path: z.string().max(1024), reason: z.string().max(300) }))
    .max(MIGRATION_FAILURES_LISTED),
  /** What the computer is copying now. */
  current: z.string().max(1024).nullable(),
  /** Why the computer is waiting, such as "Unlock your files to continue". */
  waiting: z.string().max(300).nullable(),
});
export type MigrationProgress = z.infer<typeof migrationProgressSchema>;
export const emptyMigrationProgress = (): MigrationProgress => ({
  filesTotal: null,
  bytesTotal: null,
  filesDone: 0,
  bytesDone: 0,
  filesSkipped: 0,
  filesFailed: 0,
  failures: [],
  current: null,
  waiting: null,
});

export const migrationSchema = z.object({
  id: z.string(),
  provider: migrationProvider,
  connectionId: z.string(),
  /** The computer that copies: the sign-in it was chosen through, and its installation. */
  deviceId: z.string(),
  devicePublicId: z.string().nullable(),
  deviceName: z.string(),
  status: migrationStatus,
  /** The My Drive folder files go into, once the computer has made it. */
  folderId: z.string().nullable(),
  folderName: z.string(),
  progress: migrationProgressSchema,
  error: z.string().nullable(),
  createdAt: z.string(),
  updatedAt: z.string(),
  startedAt: z.string().nullable(),
  finishedAt: z.string().nullable(),
  /** When the computer last said how it's going. */
  reportedAt: z.string().nullable(),
});
export type Migration = z.infer<typeof migrationSchema>;

export const migrationsViewSchema = z.object({
  providers: z.array(
    z.object({ id: migrationProvider, label: z.string(), available: z.boolean() }),
  ),
  connections: z.array(migrationConnectionSchema),
  migrations: z.array(migrationSchema),
});
export type MigrationsView = z.infer<typeof migrationsViewSchema>;

export const migrationConnectStartInput = z.object({ provider: migrationProvider });
export const migrationConnectFinishInput = z.object({
  state: z.string().min(16).max(128),
  code: z.string().min(1).max(4096),
});
export const migrationCreateInput = z.object({
  connectionId: z.string().min(1).max(128),
  deviceId: z.string().min(1).max(128),
});
/** The computer's report; the answer says whether to carry on (RUNNING) or stop. */
export const migrationReportInput = z.object({
  status: z.enum(['RUNNING', 'COMPLETED', 'FAILED']),
  folderId: z.string().min(1).max(128).optional(),
  progress: migrationProgressSchema,
  error: z.string().max(500).optional(),
});
/** Access to the other service for the computer; it asks again before `expiresAt`. */
export const migrationAccessSchema = z.object({
  provider: migrationProvider,
  token: z.string(),
  expiresAt: z.string(),
});
export type MigrationAccess = z.infer<typeof migrationAccessSchema>;
