import { useEffect, useState } from 'react';
import { Alert } from '../../web/components/ui/alert';
import { Checkbox } from '../../web/components/ui/checkbox';
import { Field } from '../../web/components/ui/field';
import { Select } from '../../web/components/ui/select';
import type { BackupSettings } from './backups';

const WAITS = [5, 10, 30, 60] as const;
const waitLabel = (minutes: number) => (minutes === 60 ? '1 hour' : `${minutes} minutes`);

/** Settings → Desktop settings: how automatic backups treat files that are still changing. */
export function BackupSetting() {
  const bridge = window.harbor;
  const [settings, setSettings] = useState<BackupSettings>();
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  useEffect(() => {
    void bridge.backupSettings().then(setSettings, () => {});
  }, [bridge]);
  if (!settings) return null;
  async function save(next: BackupSettings) {
    setBusy(true);
    setError('');
    try {
      setSettings(await bridge.setBackupSettings(next));
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  }
  return (
    <>
      {error && <Alert tone="error">{error}</Alert>}
      <Field
        label="Back up changed files after"
        hint="A file is saved once it has been unchanged this long. Only files added or changed since the last backup are sent. Back up now saves them right away."
      >
        <Select
          value={settings.waitMinutes}
          disabled={busy}
          onChange={(event) =>
            void save({
              ...settings,
              waitMinutes: Number(event.target.value) as BackupSettings['waitMinutes'],
            })
          }
        >
          {WAITS.map((minutes) => (
            <option key={minutes} value={minutes}>
              {waitLabel(minutes)}
            </option>
          ))}
        </Select>
      </Field>
      <label className="choice">
        <Checkbox
          checked={settings.skipChanging}
          disabled={busy}
          onChange={(event) => void save({ ...settings, skipChanging: event.target.checked })}
        />
        <span>
          <strong>Skip files that change during a backup</strong>
          <span className="muted">
            A file edited while it is being saved is left for the next backup instead of being
            reported as failed.
          </span>
        </span>
      </label>
    </>
  );
}
