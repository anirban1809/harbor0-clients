'use client';
import { useEffect, useRef, useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { CloudDownload, Loader2 } from 'lucide-react';
import type { ApiClient } from '@harbor/api-client';
import {
  activeMigrationStatuses,
  deviceDisplayName,
  migrationFlags,
  migrationProviders,
  runsMigrations,
  type Device,
  type Migration,
  type MigrationConnection,
  type MigrationsView,
} from '@harbor/contracts';
import { useFeatureFlag } from '../lib/feature-flags';
import { fileSize } from '../lib/file-metadata';
import { driveHref } from '../lib/routes';
import { Alert } from './ui/alert';
import { Badge } from './ui/badge';
import { Button } from './ui/button';
import { Card } from './ui/card';
import { Dialog, DialogActions } from './ui/dialog';
import { Field } from './ui/field';
import { Progress } from './ui/progress';
import { Select } from './ui/select';

const desktops = new Set<Device['platform']>(['MACOS', 'WINDOWS', 'LINUX']);
const count = (n: number) => n.toLocaleString();
const day = (iso: string) =>
  new Date(iso).toLocaleDateString(undefined, { month: 'short', day: 'numeric', year: 'numeric' });
// While a migration is under way the card follows it; the computer reports every few seconds.
const FOLLOW_MS = 5000;

/**
 * Settings card for copying everything from another drive (Google Drive for now) into My Drive.
 * People connect the drive and start here; one of their computers does the copying.
 */
export function MigrationSettings({ api }: { api: ApiClient }) {
  // Each service has its own flag; the card shows once any of them is on.
  const enabled = migrationFlags.map(useFeatureFlag).some(Boolean);
  const queries = useQueryClient();
  const view = useQuery<MigrationsView>({
    queryKey: ['migrations'],
    queryFn: () => api.request('/v1/migrations'),
    enabled,
    refetchInterval: (query) =>
      query.state.data?.migrations.some((m) => activeMigrationStatuses.includes(m.status))
        ? FOLLOW_MS
        : false,
  });
  const [starting, setStarting] = useState<MigrationConnection>();
  const [disconnecting, setDisconnecting] = useState<MigrationConnection>();
  const [busy, setBusy] = useState('');
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const returned = useRef(false);

  // The other service sends people back to Settings with ?state=…&code=… (or ?error=…).
  useEffect(() => {
    const params = new URLSearchParams(window.location.search);
    const state = params.get('state');
    if (returned.current || !state || !(params.has('code') || params.has('error'))) return;
    returned.current = true;
    window.history.replaceState(null, '', window.location.pathname);
    const code = params.get('code');
    if (!code) {
      setError('Access was not allowed, so nothing was connected.');
      return;
    }
    setBusy('connect');
    api
      .request('/v1/migrations/connections/finish', { method: 'POST', body: { state, code } })
      .then(({ connection }: { connection: MigrationConnection }) => {
        setNotice(`${migrationProviders[connection.provider].label} is connected.`);
        return queries.invalidateQueries({ queryKey: ['migrations'] });
      })
      .catch((e: Error) => setError(e.message))
      .finally(() => setBusy(''));
  }, [api, queries]);

  if (!enabled) return null;

  async function run(key: string, task: () => Promise<unknown>) {
    setBusy(key);
    setError('');
    setNotice('');
    try {
      await task();
      await queries.invalidateQueries({ queryKey: ['migrations'] });
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy('');
    }
  }
  const connect = (provider: MigrationConnection['provider']) =>
    run('connect', async () => {
      const { url } = await api.request('/v1/migrations/connections', {
        method: 'POST',
        body: { provider },
      });
      window.location.assign(url);
      // Keep the button busy while the browser leaves.
      await new Promise(() => {});
    });
  const control = (migration: Migration, action: 'pause' | 'resume' | 'cancel') =>
    run(`${action}:${migration.id}`, () =>
      api.request(`/v1/migrations/${migration.id}/${action}`, { method: 'POST' }),
    );

  const data = view.data;
  const latest = (connection: MigrationConnection) =>
    data?.migrations.find((m) => m.connectionId === connection.id);
  const available = data?.providers.filter((p) => p.available) ?? [];
  const soon = data?.providers.filter((p) => !p.available) ?? [];

  return (
    <Card
      className="panel"
      title="Bring files from another drive"
      description="Copy everything from Google Drive into My Drive. One of your computers with harbor0 does the copying, so it needs to stay on and online until it's done."
    >
      {error && <Alert tone="error">{error}</Alert>}
      {notice && <Alert tone="success">{notice}</Alert>}
      {view.isPending ? (
        <Loader2 className="spin" aria-label="Loading" />
      ) : view.isError ? (
        <Alert tone="error">{(view.error as Error).message}</Alert>
      ) : (
        <ul className="two-factor-methods migration-list">
          {data!.connections.map((connection) => (
            <ConnectionRow
              key={connection.id}
              connection={connection}
              migration={latest(connection)}
              busy={busy}
              onStart={() => setStarting(connection)}
              onControl={control}
              onDisconnect={() => setDisconnecting(connection)}
            />
          ))}
          {available
            .filter((p) => !data!.connections.some((c) => c.provider === p.id))
            .map((provider) => (
              <li key={provider.id}>
                <span className="two-factor-icon">
                  <CloudDownload aria-hidden="true" />
                </span>
                <div>
                  <strong>{provider.label}</strong>
                  <p className="muted">
                    Sign in to Google and allow harbor0 to read your files. Docs, Sheets and Slides
                    come over as Word, Excel and PowerPoint files.
                  </p>
                </div>
                <Button size="sm" disabled={!!busy} onClick={() => void connect(provider.id)}>
                  {busy === 'connect' && <Loader2 className="spin" aria-hidden="true" />}
                  Connect
                </Button>
              </li>
            ))}
          {soon.map((provider) => (
            <li key={provider.id}>
              <span className="two-factor-icon">
                <CloudDownload aria-hidden="true" />
              </span>
              <div>
                <strong>
                  {provider.label}
                  <Badge>Coming soon</Badge>
                </strong>
              </div>
            </li>
          ))}
        </ul>
      )}
      {starting && (
        <StartDialog
          api={api}
          connection={starting}
          onClose={() => setStarting(undefined)}
          onStarted={() => {
            setStarting(undefined);
            void queries.invalidateQueries({ queryKey: ['migrations'] });
          }}
        />
      )}
      <Dialog
        open={!!disconnecting}
        onOpenChange={(open) => {
          if (!open && !busy) setDisconnecting(undefined);
        }}
        title={`Disconnect ${disconnecting ? migrationProviders[disconnecting.provider].label : ''}?`}
        description="harbor0 loses access to it at once, and a migration still going is cancelled. Files already copied stay in My Drive."
      >
        <DialogActions>
          <Button variant="outline" disabled={!!busy} onClick={() => setDisconnecting(undefined)}>
            Cancel
          </Button>
          <Button
            variant="danger"
            disabled={!!busy}
            onClick={() =>
              disconnecting &&
              void run('disconnect', async () => {
                await api.request(`/v1/migrations/connections/${disconnecting.id}`, {
                  method: 'DELETE',
                });
                setDisconnecting(undefined);
              })
            }
          >
            {busy === 'disconnect' ? 'Disconnecting…' : 'Disconnect'}
          </Button>
        </DialogActions>
      </Dialog>
    </Card>
  );
}

function ConnectionRow({
  connection,
  migration,
  busy,
  onStart,
  onControl,
  onDisconnect,
}: {
  connection: MigrationConnection;
  migration?: Migration;
  busy: string;
  onStart: () => void;
  onControl: (migration: Migration, action: 'pause' | 'resume' | 'cancel') => void;
  onDisconnect: () => void;
}) {
  const label = migrationProviders[connection.provider].label;
  const active = migration && activeMigrationStatuses.includes(migration.status);
  const act = (action: 'pause' | 'resume' | 'cancel', text: string, variant?: 'outline') =>
    migration && (
      <Button
        size="sm"
        variant={variant ?? 'outline'}
        disabled={!!busy}
        onClick={() => onControl(migration, action)}
      >
        {busy === `${action}:${migration.id}` && <Loader2 className="spin" aria-hidden="true" />}
        {text}
      </Button>
    );
  return (
    <li className="migration-row">
      <span className="two-factor-icon">
        <CloudDownload aria-hidden="true" />
      </span>
      <div>
        <strong>{label}</strong>
        <p className="muted">
          {connection.accountEmail ?? connection.accountName ?? 'Connected'} · connected{' '}
          {day(connection.connectedAt)}
        </p>
      </div>
      <div className="migration-actions">
        {!active && (
          <Button size="sm" disabled={!!busy} onClick={onStart}>
            {migration?.status === 'COMPLETED' ? 'Copy again' : 'Start copying'}
          </Button>
        )}
        {(migration?.status === 'QUEUED' || migration?.status === 'RUNNING') &&
          act('pause', 'Pause')}
        {migration?.status === 'PAUSED' && act('resume', 'Resume')}
        {migration?.status === 'FAILED' && act('resume', 'Try again')}
        {active && act('cancel', 'Cancel')}
        <Button size="sm" variant="ghost" disabled={!!busy} onClick={onDisconnect}>
          Disconnect
        </Button>
      </div>
      {migration && <MigrationStatusView migration={migration} />}
    </li>
  );
}

function MigrationStatusView({ migration }: { migration: Migration }) {
  const p = migration.progress;
  const where = migration.folderId ? (
    <a href={driveHref(migration.folderId)}>My Drive › {migration.folderName}</a>
  ) : (
    `My Drive › ${migration.folderName}`
  );
  const totals =
    p.filesTotal === null
      ? `${count(p.filesDone)} files`
      : `${count(p.filesDone)} of ${count(p.filesTotal)} files`;
  const bytes =
    p.bytesTotal === null
      ? fileSize(p.bytesDone)
      : `${fileSize(p.bytesDone)} of ${fileSize(p.bytesTotal)}`;
  const extra = [
    p.filesSkipped ? `${count(p.filesSkipped)} skipped` : '',
    p.filesFailed ? `${count(p.filesFailed)} couldn't be copied` : '',
  ].filter(Boolean);
  const line = (() => {
    switch (migration.status) {
      case 'QUEUED':
        return `Waiting for ${migration.deviceName} to start. Open harbor0 on it if it isn't running.`;
      case 'RUNNING':
        return p.waiting ?? `Copying on ${migration.deviceName} · ${totals} · ${bytes}`;
      case 'PAUSED':
        return `Paused · ${totals} copied so far`;
      case 'COMPLETED':
        return `Copied ${count(p.filesDone)} files (${fileSize(p.bytesDone)}) on ${day(migration.finishedAt ?? migration.updatedAt)}`;
      case 'FAILED':
        return migration.error ?? 'The migration stopped.';
      case 'CANCELLED':
        return `Cancelled · ${totals} copied`;
    }
  })();
  const fraction =
    p.bytesTotal && p.bytesTotal > 0
      ? p.bytesDone / p.bytesTotal
      : p.filesTotal
        ? (p.filesDone + p.filesSkipped + p.filesFailed) / p.filesTotal
        : null;
  return (
    <div className="migration-status">
      <p className={migration.status === 'FAILED' ? 'migration-error' : 'muted'}>
        {line}
        {extra.length ? ` · ${extra.join(' · ')}` : ''}
      </p>
      {migration.status === 'RUNNING' && (
        <Progress
          className="e2ee-progress"
          max={1}
          value={fraction ?? undefined}
          aria-label="Migration progress"
        />
      )}
      {migration.status === 'RUNNING' && p.current && (
        <p className="muted migration-current">{p.current}</p>
      )}
      <p className="muted">Into {where}</p>
      {p.failures.length > 0 && (
        <details className="migration-failures">
          <summary>Files that couldn&apos;t be copied</summary>
          <ul>
            {p.failures.map((failure) => (
              <li key={failure.path}>
                <span>{failure.path}</span> <span className="muted">— {failure.reason}</span>
              </li>
            ))}
          </ul>
        </details>
      )}
    </div>
  );
}

function StartDialog({
  api,
  connection,
  onClose,
  onStarted,
}: {
  api: ApiClient;
  connection: MigrationConnection;
  onClose: () => void;
  onStarted: () => void;
}) {
  const devices = useQuery<{ items: Device[] }>({
    queryKey: ['devices'],
    queryFn: () => api.request('/v1/devices'),
  });
  const computers = (devices.data?.items ?? []).filter(
    (d) => desktops.has(d.platform) && !d.revokedAt,
  );
  const ready = computers.filter((d) => runsMigrations(d.appVersion));
  const [deviceId, setDeviceId] = useState('');
  const chosen = deviceId || ready[0]?.id || '';
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const label = migrationProviders[connection.provider].label;

  async function start() {
    setBusy(true);
    setError('');
    try {
      await api.request('/v1/migrations', {
        method: 'POST',
        body: { connectionId: connection.id, deviceId: chosen },
      });
      onStarted();
    } catch (e) {
      setError((e as Error).message);
      setBusy(false);
    }
  }

  return (
    <Dialog
      open
      onOpenChange={(open) => {
        if (!open && !busy) onClose();
      }}
      title={`Copy ${label} into My Drive`}
      description={`Everything in ${connection.accountEmail ?? label} goes into a “${migrationProviders[connection.provider].folderName}” folder in My Drive, with its folders as they are. Copying again later skips files already there.`}
    >
      <div className="form">
        {error && <Alert tone="error">{error}</Alert>}
        {devices.isPending ? (
          <Loader2 className="spin" aria-label="Loading" />
        ) : ready.length ? (
          <Field label="Computer that copies">
            <Select block value={chosen} onChange={(e) => setDeviceId(e.target.value)}>
              {computers.map((device) => (
                <option
                  key={device.id}
                  value={device.id}
                  disabled={!runsMigrations(device.appVersion)}
                >
                  {deviceDisplayName(device)}
                  {runsMigrations(device.appVersion) ? '' : ' (update harbor0 first)'}
                </option>
              ))}
            </Select>
          </Field>
        ) : (
          <Alert tone="info">
            {computers.length
              ? 'Update harbor0 on your computer to the latest version, then come back here.'
              : 'Install the harbor0 desktop app on a computer and sign in. It does the copying.'}
          </Alert>
        )}
        <p className="muted">
          The computer downloads each file from {label} and uploads it to harbor0, so it needs room
          for the largest file and should stay on until it&apos;s done. Copied files count towards
          your storage.
        </p>
        <DialogActions>
          <Button type="button" variant="outline" disabled={busy} onClick={onClose}>
            Cancel
          </Button>
          <Button disabled={busy || !chosen} onClick={() => void start()}>
            {busy && <Loader2 className="spin" aria-hidden="true" />}
            {busy ? 'Starting…' : 'Start copying'}
          </Button>
        </DialogActions>
      </div>
    </Dialog>
  );
}
