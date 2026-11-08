# harbor0 for Android

Native Kotlin and Jetpack Compose app for Android 8.0 (API 26) and later. It follows the [iOS app](../ios/README.md) and uses the same deployed harbor0 API. No backend changes are needed.

## Run or install

Open this directory in Android Studio, let Gradle sync, choose an emulator or USB-connected phone, and run **app**. Sign in, or create and verify an account, in the app.

Command-line builds require JDK 17, Android SDK platform 36 and build tools 35.0.0. Set `ANDROID_HOME` to the SDK directory or create an untracked `local.properties` containing `sdk.dir=/absolute/path/to/sdk`.

From the repository root:

```sh
apps/android/gradlew -p apps/android :app:assembleDebug
adb install -r apps/android/app/build/outputs/apk/debug/app-debug.apk
```

The debug APK is signed with the local Android development key and connects to production by default. It can be installed on a phone after allowing installs from the application used to open the APK. Its application ID is `app.harbor0.android.debug`; release uses `app.harbor0.android`.

For testing on a phone at release speed, `:app:assembleProfile` builds an optimized, non-debuggable APK signed with the debug key under the debug application ID, so it installs over a debug build (`-PharborApiUrl` works here too). Debug builds scroll noticeably worse than this.

### Release signing

Google Play signs what users install with the app signing key it holds (Play App Signing). Each upload is signed with your own **upload key**. If the upload key is lost, Google support can reset it; keep a backup anyway, because uploads stop until then.

1. Create the upload key once, outside the repository (`*.jks` is ignored here as a backstop). Save the file and both passwords in a password manager:

   ```sh
   mkdir -p ~/.android-keys
   keytool -genkeypair -v -keystore ~/.android-keys/harbor0-upload.jks -alias upload -keyalg RSA -keysize 4096 -validity 10000
   ```

2. Point Gradle at it in `~/.gradle/gradle.properties` in your home directory (not this project's), readable only by you (`chmod 600`):

   ```properties
   HARBOR_UPLOAD_STORE_FILE=/absolute/path/to/harbor0-upload.jks
   HARBOR_UPLOAD_KEY_ALIAS=upload
   HARBOR_UPLOAD_STORE_PASSWORD=…
   HARBOR_UPLOAD_KEY_PASSWORD=…
   ```

   Release builds are signed when all four are set. Without them they stay unsigned; with only some set, Gradle warns and lists the missing ones.

3. Raise `versionCode` in `app/build.gradle.kts` (Play refuses one it has seen) and set `versionName`, then build the bundle from a committed state:

   ```sh
   apps/android/gradlew -p apps/android :app:bundleRelease
   ```

   The bundle is `apps/android/app/build/outputs/bundle/release/app-release.aab`. Check it is signed with the upload key: `keytool -printcert -jarfile` on it shows the upload certificate.

4. In the Play Console, create the app as `app.harbor0.android`, keep Play App Signing (the default), and upload the bundle to the internal testing track first. The release build always uses the production API.

On the development Mac used for this implementation, the SDK is in `~/Library/Android/sdk`, JDK 17 is in `~/Library/Java/JavaVirtualMachines/temurin-17.jdk`, and the emulator is named `Harbor0_Android`.

## Included

The app follows the web app's phone layout (`apps/web/app/styles/mobile.css`) and covers the web features:

- **Shell:** top bar with brand mark, pill search, activity bell (unread dot, Activity sheet, “All notifications”) and avatar account menu (profile, storage, Account settings, Devices, Manage storage, Sign out). Bottom tabs Drive · Shared · Trash · More (backups are in My Drive, as on the web); More is a bottom sheet with Devices, Storage, Settings, a theme toggle and the storage card. Offline and error banners, toasts above the tab bar. Android Back closes sheets, clears selection or search, goes up folders, then returns to Drive.
- **Accounts:** sign in, two-step verification (authenticator app or email code, with switching between them), email confirmation with resend cooldown, forgot/reset password and “Verify now” for unverified accounts, all in-app. Android device registration with a Keystore-held device key proof, encrypted credentials, token refresh/rotation, session restoration and remote sign-out.
- **My Drive:** cloud files, with three pinned virtual places first at the root: **Synced Folders**, **Backups** (active, paused or failing backup roots) and **Archives** (archived roots). Each lists one folder per computer or phone (`GET /v1/devices`; sync folders matched by `syncDevices`, backup roots by device key, then session id, then name, with unmatched roots under their saved device name), then that device's folders, which open in the normal browser as My Drive › place › device › folder (`DriveModel.kt` `placeGroups`). Storage used is shown at every level (places, devices, folders, and folder rows plus the current folder's total inside them) from `GET /v1/drive/usage`, fetched in batches of 50 after the list shows; partial counts read “At least …” or “… +”; item count, list/grid views, Type/Modified/Sort chips (folders first, system files), breadcrumbs, low-storage warning, sync status badges, favorites, skeletons, pull-to-refresh, paging. FAB menu (Upload files, Upload a folder, New folder); multi-file upload queue with per-file progress, pause/resume/cancel and folder uploads that recreate subfolders. Long-press or checkbox selection with a dock (Download, Send, Move, Move to trash, more). Item sheet: Open, Download / Download as ZIP (server-built ZIP with progress and cancel), Send, Share (viewer/editor), Rename, Move (folder picker), favorites, View details, Version history (download, restore, restore locally for backups), Disconnect backup, Remove from sync, Move to trash. Synced files are kept in the cloud and every device downloads changes from there; older files still held only on linked computers can be requested.
- **Search** stays on the current page as on the web: My Drive (with Search all files / Search this folder inside folders), Trash (deleted items), and in-place file results on every other page.
- **Shared:** Received | Sent transfer cards with status, per-file download, “Show more files”, Accept / Decline / Save to My Drive / Cancel transfer (with confirmations), paging, and the Shared access list with Remove access.
- **Sync on this phone:** a port of the desktop sync engine (`apps/desktop/src/sync.ts`, `backups.ts`, `sync-receipts.ts`) in `SyncEngine.kt`, `SyncBackups.kt`, `SyncReceipts.kt`, `SyncTransfers.kt` and `SyncJournal.kt`. The phone registers as a sync device (`POST /v1/sync/devices/register`) and publishes its folders with `PUT /v1/sync/folders`, so sync status shows when it is up to date. Synced files stay in the cloud. Two-way sync of folders chosen with the system folder picker (Storage Access Framework, persisted access): uploads and deletions of local changes, downloads, moves and deletions of remote changes from the change feed, full reconciles, conflict copies (`name (Conflict - device - id).ext`), kept copies of folders removed elsewhere, relay requests for files held on another device, delivery acknowledgements with a durable outbox and audits, checkpoints, retries with backoff and per-file/per-folder issues. Sync page: status, pause/resume all, Sync a folder (new cloud folder with numbered fallback, any My Drive folder, or a new folder inside one), per-folder pause, exclusions, change local folder, Stop syncing on this phone, Remove from sync everywhere, problems with Retry / Mark reviewed, recent activity, files waiting for another device, sync invitations (invite and remove people, accept into a local folder, decline) and “Sync to this phone” for folders synced on other devices (also in the My Drive item menu).
- **Backups (in My Drive → Backups):** one folder per device; a backup's top folder shows the web backup panel with its status (backed up, backing up with planned totals, restoring with this phone's own file-by-file progress, backup paused · storage full, needs storage, paused, archived, stopped), History (restores and runs with their files), Stop backing up and Remove from Backups; files keep their versions with Restore and Download. Asking to restore a backup of this phone starts the restore at once. **Back up a folder** from the phone: automatic runs once files have been unchanged for an hour, Back up now, pause/resume, exclusions, Archive (a final full backup, then removal of only the local files whose content matches the saved version) and Restore archived folder, Disconnect, and restores requested from other devices are applied to the local folder. These controls appear only for folders backed up from this phone.
- **Photo backup** (`PhotoLibrary.kt`, `PhotoStore.kt`, `PhotoScreens.kt`): **Back up photos** (in My Drive › Backups and this phone's folder there, the Sync page and Settings › Sync & backups) backs up the phone's photos and, optionally, videos from MediaStore (every volume; pending and trashed items left out) as a backup folder named “Photos”, shown in My Drive › Backups › this phone › Photos with one folder per album (bucket name, else the last folder of its path, else “Other”). Names are made safe and items sharing a name in an album are numbered by MediaStore id (“IMG_1 (2).jpg”), so paths stay the same from scan to scan. Scans compare size and time straight from MediaStore and read nothing; files are read (with their location kept, via `setRequireOriginal` with `ACCESS_MEDIA_LOCATION`) and hashed only when they are uploaded. Photos skip the hour folders wait; storage-full handling, batches, planned run totals, activity reports and end-to-end encryption are those of folder backups. Options kept with the backup: **Include videos** (on) and **Back up over mobile data** (off: on a metered network the photo backup shows “Waiting for Wi-Fi” and is reported as paused by the app). New photos start a background pass about a minute later (a WorkManager content-URI trigger, on unmetered networks unless mobile data is allowed, armed again after each run), and a `ContentObserver` wakes the engine while the app is running. Permissions are asked for on setup (`READ_MEDIA_IMAGES`/`READ_MEDIA_VIDEO`, `READ_MEDIA_VISUAL_USER_SELECTED` on Android 14 — with only some photos allowed, those are backed up and the panel says so with “Choose more” — `READ_EXTERNAL_STORAGE` up to Android 12, `ACCESS_MEDIA_LOCATION`); refused or revoked access shows an explanation with Allow access / Open settings. The panel has Back up now, Pause/Resume, **Albums** (exclusions by album), the two options, Disconnect and History; Archive and Change local folder are not offered, since harbor0 never deletes, moves or renames photos. Restores put files back into the library (Camera into DCIM/Camera, other albums into Pictures/<album>, hidden while written; Android 9 and earlier write into those folders and let the media scanner add them); a photo still on the phone that harbor0 didn't save is never replaced, and the restore says so. Setting up again on a phone whose earlier “Photos” backup is still running on the server reuses it, and files it already holds (same path, size and time) are not uploaded again.
- **Trash:** search, item count, Empty Trash, list/grid, Restore / Delete permanently per item or for a selection, paging.
- **Devices, Storage, Notifications, Settings:** device list with Revoke; storage usage and plan; notifications with Mark read; appearance (Light/Dark/System, Harbor/Ocean/Forest/Violet/Sunset presets, custom colors per mode with hex input and swatch picker, saved to the account automatically), profile (display name, username), Manage devices, Sign out, Delete account.
- **Files on the phone:** verified downloads (size and SHA-256), preview sheet with inline previews of images, text (first 1 MB), PDFs (page by page) and video/audio, open in another app, share, and Download to the public Downloads folder (Android 9 and earlier choose a location). Multipart uploads with retries, renewed signed URLs and reservation cleanup. A file whose stored bytes were damaged while uploading is reported as such instead of failing its check again and again.
- **Public links** (flag `public-links`): get, copy, share and remove a file's or folder's public link from the item menu, and the list of links under Shared. On an end-to-end encrypted account links are encrypted, as on the web (`E2eeLinks.kt`, docs/specs/public-folder-links.md): a random 32-byte secret goes only in the link's fragment (`/s#<token>.<secret>`) and is kept by the server wrapped by the account key; the phone wraps each shared item's name and content key for the link (`/v1/links/:id/e2ee/scan` and `/wraps`) when the link is made or opened, and every 10 minutes while unlocked. Checked against vectors from `packages/api-client` (`e2ee-link-vectors.ts`).
- **Photos** (flag `photos`, in More): every photo and video in the drive (`GET /v1/photos`, 500 a page, 20 pages read by themselves, then Load more), newest first, by month or by the device it was synced or backed up from, as thumbnails or a list, with All / Photos / Videos. Thumbnails come from `/v1/photos/links` (asked for together, 60 at a time), drawn small and kept in the cache; videos show a frame read from their link; encrypted photos up to 20 MB are decrypted in memory and never cached on disk. Opening one shows the preview. This is not the phone's photo backup.
- **ZIP archives as folders** (flag `zip-preview`): opening a .zip in My Drive browses it read-only, from its central directory read by range (`ZipReader.kt`, `ZipFolder.kt`; ZIP64, CP437 names, `__MACOSX` hidden; encrypted archives decrypted chunk by chunk). Files inside preview and download (stored or deflate, CRC-32 checked), and files or folders copy into the folder holding the archive (My Drive where that can't take files), up to 2 GB unpacked at once; Download archive saves the whole file.
- **End-to-end encryption:** an encrypted account works as on the web and desktop (`E2eeCrypto.kt`, `E2eeTransport.kt`, `E2eeVault.kt`, `E2eeScreens.kt`). Every request goes through the vault's transport (`HarborApi.transport`), which encrypts names going out and decrypts names, sizes and types coming back, so every page, search and sync see plaintext; sync uses a strict transport that refuses file requests while locked. Unlock with the passphrase, or the recovery key and a new passphrase; “Remember this phone” keeps a Keystore-sealed copy that also needs the server's half, so removing the phone in the web app locks it. Uploads (manual, new versions, keep both, sync single and multipart with resume, batches, backups) get their own key per file and are sealed chunk by chunk; the server sees only encrypted names and sizes and the stored bytes' SHA-256. Downloads (manual, previews, sync, restores) are checked as stored, then decrypted. While locked, sync and backups wait (“Locked · unlock to sync”, reported as blocked: locked). Settings shows the status, this phone (Lock), conversion progress, Change passphrase, Create new recovery key, unlocked devices (Remember, Lock now, Remove) and, with the `e2ee` flag, the turn-on wizard. Backups made from this phone are converted by this phone when encryption is turned on or off (a manual run with planned totals). Folder ZIPs, Send, Share access, Save to My Drive, shared sync invitations and rclone tokens are unavailable on encrypted accounts, as the server refuses them; public links are encrypted instead.
- **Activity and problem reports:** sync and backups report what they are doing (active, paused by you or the app, blocked by full storage or a locked phone, idle; backup runs with their totals), and failed uploads are reported without names, for the staff console.

Small files (up to 8 MB) from synced and backed-up folders upload 25 at a time in one batch, 8 to storage at once. Manual uploads and downloads run while the app remains open. They do not resume across process death; unfinished server reservations use the backend's expiry cleanup. Pausing an upload restarts that file from the beginning when resumed. Previews are private temporary files removed on the next launch.

Photo backups read the library at most once a minute on screen and every five minutes otherwise, sooner when the library changes. Sync and backups run while the app process is alive (server checks every 2 s backing off to 30 s, local folders rescanned every 5 s on screen and every minute otherwise) and as a WorkManager periodic job about every 15 minutes with a network connection. Android decides when background work actually runs: Doze, battery saver and app standby buckets can defer it for hours, and a single background pass is limited to about 10 minutes. Interrupted uploads and downloads resume from the journal (multipart upload state, `.harbor-part` files verified by size and SHA-256). Limits compared with desktop: there is no file watcher, so local changes are found by comparing each folder with its previous scan; there are no live-update connections, so remote changes arrive by polling; transfers are sequential rather than four-way parallel; an archived backup keeps its (empty) top folder so the folder grant survives for a restore; and the sync journal is per account in the app's private storage, so uninstalling the app forgets mappings (local files are kept and reconciled when a folder is set up again).

Encryption limits: existing unencrypted files are re-encrypted (and, when turning it off, decrypted) by harbor0 on the web or the desktop app, not by the phone, and turning encryption off starts on the web; this phone converts only the backups it made itself. Previews of encrypted files download and decrypt the whole file first, since the preview sheet hands that verified file to other apps. Without “Remember this phone”, the app locks when Android ends its process, so background sync waits until the app is opened and unlocked again.

Not included: offline browsing of cloud-only files, an Android DocumentsProvider, a receive-share extension, system push notifications, drag-and-drop uploads, and opening the local copy of a conflict in another app.

## Tests

Quick client checks (the JVM tests include the sync rules — safe names, internal paths, conflict names, scan diffing, the one-hour backup rule, folder states — photo backup against an in-memory photo library (album paths, safe and numbered names, scans that read nothing, restores into the library, options, Wi-Fi gating, reusing an earlier backup), resumable sync transfers against a mock server, and end-to-end encryption: the crypto against vectors from `packages/api-client`, the transport, the vault, and encrypted uploads, resumed multipart uploads, batches and decrypting downloads against a mock server):

```sh
apps/android/gradlew -p apps/android :app:testDebugUnitTest :app:lintDebug
```

The real backend test is skipped unless the isolated-service runner enables it. To run all tests, start the repository's Docker services and one Android emulator or USB-debugging device, install repository npm dependencies, and run:

```sh
docker compose up -d
ANDROID_HOME="$HOME/Library/Android/sdk" node apps/android/test-android.mjs
```

Use `ANDROID_SERIAL` when more than one device is connected. The runner creates a unique local DynamoDB table and MinIO bucket, runs the real backend on port 18990 and the Android failure-injection fixture on port 18989, configures an adb reverse tunnel, and removes its backend processes, table and bucket afterward. It never uses production accounts or storage. After the tests it rebuilds the APK against production.

Reports are under `app/build/reports`; emulator screenshots are under `build/screenshots`. The UI test writes only its named `harbor0-*.png` screenshots to the test device's Downloads directory so they survive test-app removal.

Debug builds alone accept a build-time API override:

```sh
apps/android/gradlew -p apps/android :app:assembleDebug \
  -PharborApiUrl=http://10.0.2.2:8787
```

HTTPS is required outside emulator/loopback addresses (`10.0.2.2`, `127.0.0.1`, `localhost`). Release builds always use the production endpoint and reject all cleartext network traffic. Fixture code is outside the Android application sources and never packaged.

Geist and Geist Mono (SIL Open Font License, see [GEIST-LICENSE.txt](GEIST-LICENSE.txt)) are bundled in `app/src/main/res/font` as Latin-subset variable fonts converted from the repository's `@fontsource-variable` packages; other scripts fall back to the system font. After adding an icon name to `generate-icons.mjs`, run `node apps/android/generate-icons.mjs` to regenerate `Icons.kt`.

## Verified on 20 October 2026

Debug and unsigned release APKs built, lint reported no errors, and `test-android.mjs` passed: 102 JVM tests (API, transfers, sync, storage, activity, access rules, encryption vectors byte-for-byte against `packages/api-client/src/e2ee.ts`), three real backend integration tests (sync and backup batches, `modifiedAt` and planned run totals, whole-folder restores, activity, problem reports, and an encrypted account: turning encryption on, encrypted folder, manual, sync and batch uploads stored with `h0e1.` names and encrypted sizes, downloads decrypted back, search, a remembered unlock, a removed copy, and sync refused while locked) and the end-to-end emulator UI test (now with My Drive › Backups and its History in place of the Backups tab). `test-android.mjs` turns the `e2ee` flag on in its disposable table (`enable-test-flags.ts`).

Checked by hand on the emulator against a local backend with the `e2ee`, `two-factor` and `public-links` flags: email two-step sign-in, the four-step encryption wizard (strength meter, recovery key, group check), an encrypted upload from the phone (the server held only an `h0e1.` name, 131 bytes for a 35-byte file and `application/octet-stream`), its decrypted preview, an encrypted rename, the item menu hiding Send, Share and links with the explanation, reopening unlocked after the app was killed (Remember on), and Lock then unlock with the passphrase. Public links, PDF/video previews, folder restores and phone backups were not driven by hand.

## Verified on 1 October 2026

After rebuilding the UI to the web phone layout: debug and unsigned release APKs built, lint reported no errors, and `test-android.mjs` passed all 19 tests (14 API/transfer, three appearance, one real backend integration test and one end-to-end emulator UI test covering sign-in, folders, the upload queue, preview/save, rename, favorites, trash, Sync and Backup locations, the Backups page, Shared, in-place search, presets and sign-out). Light and dark screenshots of every main screen against the local backend are in `build/screenshots` (`light-*.png`, `dark-*.png`).

## Verified on 30 September 2026

After the redesign to the web and desktop style: debug and unsigned release APKs built, lint reported no errors, and all 18 tests passed (13 API/transfer, three appearance, one real backend integration test and one end-to-end emulator UI test on Android 16 / API 36). Ten emulator screenshots were reviewed, covering sign-in, drive, backups and history, the download dialog, trash, settings, Ocean light and Violet dark.

## Verified on 28 September 2026

- Debug APK and optimized unsigned release APK built successfully; Android lint has no errors. Its remaining warnings suggest newer dependency versions and optional Kotlin shorthand.
- All **18 tests passed**: 13 API/transfer tests, three appearance tests, one real backend integration test and one end-to-end emulator UI test.
- The real backend integration test uploaded/downloaded a **65 MiB** file across the 64 MiB multipart boundary, compared SHA-256 hashes, persisted custom appearance, exercised trash/restore/empty, rotated a restored session and verified remote revocation.
- Android 16 / API 36 ARM64 emulator: invalid/valid sign-in, folder creation, retained tab paths, document-provider URI upload and save with byte comparison, download verification, trash/permanent-delete/restore, read-only backups and history, theme saving, Activity restart with encrypted-session restoration, and sign-out.
- Screenshots reviewed in Harbor light, Ocean light and Violet dark.

Physical-device testing, external file-viewer behavior, native picker interactions across third-party document providers, live production-account authentication and release signing remain to be checked before a public release.
