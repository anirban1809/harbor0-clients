# iOS ↔ Android parity

Gap analysis of the iOS app (`apps/ios`, last feature work about 2026-10-13) against the Android app
(`apps/android`, v0.1.16 on `mobile`), with the web app as the spec. `[x]` is done on iOS, `[ ]` is
still a gap (with the reason), `[~]` is partly done (see the note). iOS-native equivalents replace
Android mechanisms: Keychain/Secure Enclave for Keystore, PhotoKit for MediaStore, BGTaskScheduler for
WorkManager and foreground services, Quick Look for the preview views, the share sheet for Downloads.

iOS is now version **0.1.16** (`MARKETING_VERSION`, app and Files extension), sent as `appVersion`.

## P0 — what the server now requires

- [x] Signed device keys (Secure Enclave P-256 proof on `POST /v1/auth/session`) — already on iOS; refresh rotation, remote sign-out.
- [x] Two-step sign-in: `twoFactor: true` on `/v1/auth/login`, challenge screen (authenticator or email code, “Email me a code instead” / “Use my authenticator app”, “Send a new code”), `/v1/auth/login/method`, `/v1/auth/login/verify`, wrong code keeps the returned session, `AUTH_EXPIRED` returns to the password. Checked by hand against a local backend (email codes) and by a unit test.
- [x] `appVersion` sent with the device proof and with `POST /v1/sync/devices/register` (which would otherwise reset it to null); a new build re-proves once on launch.
- [x] `E2EE_UPDATE_REQUIRED` (426): `HarborAPI.encryptionBlocked`; file pages show “Update harbor0 to open your files”.
- [x] Throttling retries: 429 `RATE_LIMITED` / 503 retried up to 3 times with Retry-After (signed-in calls only).
- [x] Account `flags` from `/v1/users/me` (`Account.flag(_:)`, `Workspace.flag(_:)`).
- [x] Contract: `E2EE_CLIENTS.IOS = '0.1.16'` on main (needs main merged into `mobile`). Without it the server refuses encrypted file routes to iOS and `E2eeIntegrationTests` skips its file part.

## P1 — end-to-end encryption

- [x] Crypto core byte-for-byte with `packages/api-client/src/e2ee.ts` (`E2eeCrypto.swift`): HKDF subkeys + proof, Argon2id in pure Swift (BLAKE2b + RFC 9106, no packages), passphrase/recovery/device envelopes, Crockford recovery keys with forgiving parsing, deterministic `h0e1.` names, H0E1 files (80-byte header, 4 MiB chunks), sizes.
- [x] XCTest against `Harbor0Tests/e2ee-vectors.json` (copied from Android's resources): all vectors, including Argon2id at 64 MiB × 3.
- [x] Transport (`E2eeTransport.swift`): every signed-in request goes through it; outgoing names for folders, rename/move, uploads, backup runs/folders/files, batches, sync operations and lookups are sealed; answers come back with names, paths, plain sizes and guessed MIME types; client-side name search. Strict mode: file routes are refused while locked, and an account encrypted elsewhere is noticed.
- [x] Vault (`E2eeVault.swift`): keys in memory; “Remember this iPhone” keeps a copy in the shared keychain group that also needs the server half; session-only unlocks; lock; removed copy → passphrase again with a notice; restore retried when the app returns.
- [x] Unlock page in place of file pages while locked (passphrase, or recovery key + new passphrase, strength meter, Remember toggle).
- [x] Settings › End-to-end encryption: status, this iPhone (Lock / Remember), existing-files progress, Change passphrase, Create new recovery key (group check), unlocked devices (Remove), turn-off pointer to the web; the four-step turn-on wizard behind `e2ee`.
- [x] Encrypted manual uploads (incl. new versions) and downloads/previews (checked as stored, then decrypted).
- [x] Encrypted sync and backup uploads (sealed copy kept under Application Support so multipart uploads resume with the same bytes; restart when the copy is lost or encryption flips), decrypted sync downloads and backup restores, receipts confirmed by stored hash, encrypted remote versions compared through the journal, sync waits while locked (“Locked · unlock to sync”).
- [x] Unavailable on encrypted accounts, with the explanation: folder ZIP, Send, Share access, Save to My Drive, rclone tokens. (Public links work — they carry the key.)
- [x] Files app extension: opens the account with the remembered copy (shared keychain); refuses with “not authenticated” while locked; downloads decrypt; uploads encrypt.
- [x] “Share with others” (shared sync) is hidden on encrypted accounts.
- [ ] Converting this iPhone's own backups when encryption is turned on/off (Android `FolderBackups.convert`): not ported — a large engine change; the web/desktop convert other files, and new backup uploads are already encrypted.

## P2 — drive, sharing, account pages

- [x] My Drive with Synced Folders / Backups / Archives by device, breadcrumbs, filters, sort, selection dock, usage (existing).
- [x] Item actions (existing) plus **Upload new version**, **Get / Manage public link**; Share hidden on synced folders.
- [x] Tabs as Android/web: Drive · Shared · Trash · More; Backups moved into More (and still reachable from My Drive › Backups).
- [x] Public links: see P4 (c).
- [x] Devices: Sign out (pause) and Revoke (remove) with what revoking does (backups archived, synced folders left, folders synced nowhere else); “This device” badge; signed-out state; signing out or revoking this iPhone signs out.
- [x] Notifications described from type and names (web `describeNotification`), tap opens Shared › Received/Sent or Storage, Mark all as read.
- [x] Storage: audit CSV (`/v1/storage/audit`, same columns and summary as the web, BOM, formula-safe cells) via the share sheet; Open Trash.
- [x] Settings in the web order: account, encryption, two-step verification (authenticator setup with QR code and key, email codes, switch, turn off), rclone access tokens (`rclone` flag: create with access/expiry, shown once with the setup command, revoke), email preferences, Sync & backups card (pause all, open places, photos), appearance, delete account, sign out.
- [x] Problem reports (`/v1/client-events`): storage part retries and failed manual uploads, without names or URLs.
- [x] Activity reporting (`/v1/activity`): engine, each synced folder and backup (and the photo backup) — paused, blocked (locked / storage full), active, idle; queued across restarts.
- [x] Previews: Quick Look already shows PDF, images, video, audio and text inline; encrypted files are decrypted first.
- [x] Status and controls in My Drive, as the desktop app: a synced folder shows `SyncFolderPanel` (state, last synced, the file moving with its progress, Pause/Resume, Show in Files, More, attention, or “Sync to this iPhone”); a backup folder shows `BackupFolderPanel` (state, “Saved X of Y files · N%”, Back up now, Pause/Resume, History; the photo backup's card for Photos); subfolders show `OpenFolderProgress`; a device's folders show “This iPhone · Syncing 42%”; the file moving shows “Uploading · N%” on its badge. `SyncStatusLine` above the tab bar is the desktop sidebar's status line (problems, paused, offline, restoring, syncing/backing up with percent, photos waiting for Wi-Fi), hidden while all is up to date. Backup runs now keep their starting total (`backupRuns` in the snapshot).
- [ ] No separate Sync page (Android a51a189): iOS still has More › Sync as well.
- [ ] Replace / Keep both when an uploaded name is taken: not done.
- [ ] Bulk requests four at a time with transient retries: iOS still runs bulk actions one by one.
- [ ] Batch small uploads (`/v1/uploads/batch`, backup batches) and batch receipts (`/v1/sync/items/acknowledge`): not ported; the per-file routes still work.
- [ ] Backup wait time and “skip files that change during backup” choices (Android 3674509): iOS keeps the one-hour rule.
- [ ] Restore progress per file and storage-full states per folder in the backup panel: not ported.

## P3 — photo backup and background work

- [x] Back up photos (`PhotoBackup.swift`, PhotoKit): “Photos” backup folder of this iPhone (reused if one is still running), one folder per album (first user album, else “Camera”), safe names numbered stably on clashes, scans read only PhotoKit metadata, originals exported (iCloud Photos downloaded) only to upload, encrypted on encrypted accounts, include-videos and mobile-data options (Waiting for Wi-Fi), album exclusions, limited-library notice, Back up now / Pause / Stop. Entry points: Backups page, Settings › Sync & backups.
- [x] Background: library change observer schedules a pass; `BGAppRefreshTask` runs a short photo pass (about 10 s) before folders, `BGProcessingTask` a longer one (up to 5 min); both are scheduled when only photos are backed up. The time iOS gives on leaving the app also covers a photo pass under way.
- [x] Background uploads: storage parts go through a background `URLSession` (`app.harbor0.ios.uploads`, `BackgroundUploads`), so a part keeps uploading while harbor0 is suspended; iOS wakes harbor0 when it finishes (`handleEventsForBackgroundURLSession`) and the next part starts. A part finished after harbor0 was quit is read back from the server's part list. The Files extension keeps its own session.
- [x] Continued backups (iOS 26, `BGContinuedProcessingTask`, `app.harbor0.ios.backup.*`; Android's “Backing up” service): Back up now, a new backup folder and the first photo backup keep running after the user leaves harbor0, with progress in iOS's own banner, until done or iOS expires them. Not verified: the simulator accepts the request but never runs it; needs a real iPhone.
- [x] Restores into the library (Android `MediaStoreTree.publish`): restores asked for the photo backup on the web or another device are downloaded, checked and added to Photos in their album (made if needed; “Camera” is the library itself), recorded as saved so they aren't backed up again, then acknowledged; looked for on each pass and every two minutes while open. A photo still on this iPhone isn't replaced (“Delete it in Photos first, or download this version instead”); non-photo files are refused.
- [x] Set up again on an earlier “Photos” backup of this iPhone (newest still running): its albums and the files that match the library by path, size and time are recorded without uploading (Android `photoSeed`). Uploads now send `modifiedAt`, as Android's do, so this matches.
- [x] Runs send planned totals (file count and size); photos waiting for Wi-Fi go once the iPhone is on it; the album list loads before the first pass and keeps turned-off albums that emptied; limited library access offers “Choose more”; “Back up photos” in My Drive › Backups; Android's status and stop wording.
- [ ] Showing the photo backup inside the sync engine's folder list (Android runs it as a sync root): iOS keeps its own card on the Backups page.

## P4 — new features

- [x] (a) Photos page behind `photos` (More › Photos): all photos and videos newest first, All/Photos/Videos, by date or by device, thumbnails (60 links per request, ImageIO/AVFoundation, encrypted ones decrypted up to 20 MB) or a list, opens in Quick Look.
- [x] (b) ZIP files as read-only folders behind `zip-preview` (`ZipDrive.swift`): ZIP64 central directory, CP437/UTF-8 names, `__MACOSX` hidden, folders implied by paths, stored and deflated entries, open in Quick Look or save. The phone downloads the archive once (checked, decrypted if encrypted) instead of reading ranges like the web. Copying entries into My Drive is not offered.
- [x] (c) Public links incl. E2EE links (`PublicLinks.swift`): create (expiry 1/7/30 days or never when allowed, optional password), copy/share, change expiry, set/remove password, turn off, Shared › Links list, allowance and limit notices. Encrypted accounts make encrypted links (secret only in the URL fragment, wrapped for the account; names and content keys wrapped per item from the scan, including `keyHeader`), wraps kept current on open and every 10 minutes while unlocked. Link crypto checked against values from `packages/api-client/src/e2ee.ts`.

Note: the `mobile` branch's contracts don't define the `photos` and `zip-preview` flags yet (they are on main), so those two features stay hidden against a server built from `mobile` until main is merged.
