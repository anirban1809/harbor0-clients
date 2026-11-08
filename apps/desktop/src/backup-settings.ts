import { readFile, writeFile } from 'node:fs/promises';
import { BACKUP_WAIT_CHOICES, DEFAULT_BACKUP_SETTINGS, type BackupSettings } from './backups';

/** This computer's automatic backup choices; anything missing or unknown keeps its default. */
export function parseBackupSettings(value: unknown): BackupSettings {
  const saved = (value ?? {}) as Partial<Record<keyof BackupSettings, unknown>>;
  return {
    waitMinutes: BACKUP_WAIT_CHOICES.includes(saved.waitMinutes as BackupSettings['waitMinutes'])
      ? (saved.waitMinutes as BackupSettings['waitMinutes'])
      : DEFAULT_BACKUP_SETTINGS.waitMinutes,
    skipChanging:
      typeof saved.skipChanging === 'boolean'
        ? saved.skipChanging
        : DEFAULT_BACKUP_SETTINGS.skipChanging,
  };
}

export async function readBackupSettings(file: string): Promise<BackupSettings> {
  try {
    return parseBackupSettings(JSON.parse(await readFile(file, 'utf8')));
  } catch {
    return DEFAULT_BACKUP_SETTINGS;
  }
}
export const writeBackupSettings = (file: string, settings: BackupSettings) =>
  writeFile(file, JSON.stringify(settings) + '\n', { mode: 0o600 });
