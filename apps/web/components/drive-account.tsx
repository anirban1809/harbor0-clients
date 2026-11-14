'use client';
import type { StorageUsage } from '@harbor/contracts';
import { AlertCircle, CheckCircle2, ChevronRight, Pause, RefreshCw, Sparkles } from 'lucide-react';
import { fileSize } from '../lib/file-metadata';
import { FolderProgress } from './folder-progress';
import { Button } from './ui/button';
import { Menu, MenuContent, MenuItem, MenuLabel, MenuSeparator, MenuTrigger } from './ui/menu';
import { Progress } from './ui/progress';

/** How sync and backups are doing overall, for the line above the storage meter. */
export type FolderActivity = {
  tone: 'ok' | 'busy' | 'paused' | 'error';
  label: string;
  percent?: number;
};

/** One line in the sidebar for sync and backups; it opens the folders it is about. */
export function FolderActivityStatus({
  activity,
  onOpen,
}: {
  activity: FolderActivity | null;
  onOpen: () => void;
}) {
  if (!activity) return null;
  const Icon =
    activity.tone === 'ok'
      ? CheckCircle2
      : activity.tone === 'busy'
        ? RefreshCw
        : activity.tone === 'paused'
          ? Pause
          : AlertCircle;
  return (
    <button
      className="folder-activity"
      data-tone={activity.tone}
      onClick={onOpen}
      title={activity.label}
      role="status"
      aria-live="polite"
    >
      <span className="folder-activity-label">
        <Icon aria-hidden="true" className={activity.tone === 'busy' ? 'spin' : undefined} />
        <span>{activity.label}</span>
      </span>
      {activity.percent !== undefined && (
        <FolderProgress percent={activity.percent} label={activity.label} showValue={false} />
      )}
    </button>
  );
}

/** The sidebar nudge for free accounts; which plan to pick stays on the Storage page. */
export function UpgradeBanner({ onUpgrade }: { onUpgrade: () => void }) {
  return (
    <button className="upgrade-banner" onClick={onUpgrade}>
      <Sparkles aria-hidden="true" />
      <span>Get more storage</span>
      <ChevronRight aria-hidden="true" />
    </button>
  );
}

/**
 * A large deleted folder's size comes off the meter as the server counts it. With the folder's
 * size from an earlier count the bar fills toward it; without one it just shows activity.
 */
function FreeingProgress({ freeing }: { freeing: NonNullable<StorageUsage['freeing']> }) {
  const total = freeing.estimatedBytes;
  const percent = total ? Math.min(100, Math.round((freeing.bytes / total) * 100)) : null;
  return (
    <div className="drive-storage-freeing" role="status">
      <div className="drive-storage-heading">
        <span>Freeing up space</span>
        {percent !== null && <span>{percent}%</span>}
      </div>
      <div
        className="progress-track"
        data-indeterminate={percent === null}
        role="progressbar"
        aria-label="Freeing up space"
        aria-valuemin={0}
        aria-valuemax={100}
        aria-valuenow={percent ?? undefined}
      >
        <span style={percent === null ? undefined : { width: `${percent}%` }} />
      </div>
      <p>
        {fileSize(freeing.bytes)}
        {total ? ` of ${fileSize(total)}` : ' freed so far'}
      </p>
    </div>
  );
}

export function StorageIndicator({
  storage,
  onManage,
  onRetry,
  onTrash,
}: {
  storage?: StorageUsage | null;
  onManage: () => void;
  onRetry?: () => void;
  /** Opens the trash, where it can be emptied; the meter shows what it holds. */
  onTrash?: () => void;
}) {
  return (
    <div className="drive-storage">
      <div className="drive-storage-heading">
        <span>Storage</span>
        <Button variant="link" className="storage-link" onClick={onManage}>
          Manage storage
        </Button>
      </div>
      {storage ? (
        <>
          <Progress
            value={storage.usedBytes}
            max={storage.quotaBytes || 1}
            aria-label="Storage used"
          />
          <p>
            {fileSize(storage.usedBytes)} of {fileSize(storage.quotaBytes)}
          </p>
          {/* Deleted files still count until the trash is emptied: say where the space is. */}
          {!!storage.trashBytes && (
            <p className="drive-storage-trash">
              {fileSize(storage.trashBytes)} in trash
              {onTrash && (
                <>
                  {' · '}
                  <Button variant="link" className="storage-link" onClick={onTrash}>
                    Empty trash
                  </Button>
                </>
              )}
            </p>
          )}
          {storage.freeing && <FreeingProgress freeing={storage.freeing} />}
        </>
      ) : (
        <Button variant="link" onClick={onRetry}>
          Storage unavailable · Retry
        </Button>
      )}
    </div>
  );
}

export function AccountMenu({
  user,
  storage,
  onNavigate,
  onSignOut,
}: {
  user?: { displayName?: string; username?: string; email?: string };
  storage?: StorageUsage | null;
  onNavigate: (section: 'Settings' | 'Devices' | 'Storage') => void;
  onSignOut: () => void;
}) {
  const name = user?.displayName || 'Your account';
  return (
    <Menu>
      <MenuTrigger render={<button className="account-trigger" />} aria-label="Account menu">
        <span className="avatar">{name[0].toUpperCase()}</span>
      </MenuTrigger>
      <MenuContent className="account-menu" sideOffset={8}>
        <MenuLabel className="account-info">
          <strong>{name}</strong>
          {user?.username && <span>@{user.username}</span>}
          {user?.email && <span>{user.email}</span>}
          {storage && (
            <small>
              {fileSize(storage.usedBytes)} of {fileSize(storage.quotaBytes)} used
            </small>
          )}
        </MenuLabel>
        <MenuSeparator />
        <MenuItem onClick={() => onNavigate('Settings')}>Account settings</MenuItem>
        <MenuItem onClick={() => onNavigate('Devices')}>Devices</MenuItem>
        <MenuItem onClick={() => onNavigate('Storage')}>Manage storage</MenuItem>
        <MenuSeparator />
        <MenuItem onClick={onSignOut}>Sign out</MenuItem>
      </MenuContent>
    </Menu>
  );
}
