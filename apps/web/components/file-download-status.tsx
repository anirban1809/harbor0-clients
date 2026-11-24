import { LoaderCircle } from 'lucide-react';
import { fileSize } from '../lib/file-metadata';
import { Button } from './ui/button';

/** Files downloading one after another: which one is on, and how far it is. */
export type FileDownloadStatus = {
  name: string;
  /** Files finished before this one. */
  done: number;
  total: number;
  loaded: number;
  size: number | null;
};

export function FileDownloadStatusPanel({
  progress,
  onCancel,
}: {
  progress: FileDownloadStatus;
  onCancel: () => void;
}) {
  const { name, done, total, loaded, size } = progress;
  const percent =
    size === null ? null : size === 0 ? 100 : Math.min(100, Math.floor((loaded / size) * 100));
  const heading = total === 1 ? `Downloading ${name}` : `Downloading ${done + 1} of ${total} files`;
  const message =
    percent === null ? 'Connecting to storage…' : `${fileSize(loaded)} of ${fileSize(size!)}`;
  return (
    <section
      className="card status-card zip-download-status"
      role="status"
      aria-label="Download status"
    >
      <div className="status-card-heading">
        <LoaderCircle className="spin" size={18} aria-hidden="true" />
        <strong title={heading}>{heading}</strong>
        <Button variant="ghost" size="sm" onClick={onCancel}>
          Cancel
        </Button>
      </div>
      <div className="status-card-details">
        <span>{message}</span>
        {total > 1 && (
          <span>
            {done} {done === 1 ? 'file' : 'files'} completed
          </span>
        )}
      </div>
      {total > 1 && (
        <p className="status-card-file" title={name}>
          {name}
        </p>
      )}
      <div
        className="progress-track"
        data-indeterminate={percent === null}
        role="progressbar"
        aria-label="Download progress"
        aria-valuemin={0}
        aria-valuemax={100}
        aria-valuenow={percent ?? undefined}
        aria-valuetext={message}
      >
        <span style={percent === null ? undefined : { width: `${percent}%` }} />
      </div>
    </section>
  );
}
