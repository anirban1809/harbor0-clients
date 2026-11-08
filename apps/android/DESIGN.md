# Android workspace

The Android app follows the web and desktop interface described in [docs/UI.md](../../docs/UI.md): the same tokens, type, icons and component shapes, laid out for a phone. Navigation, the document picker, file sharing and Back stay native.

Tokens mirror `apps/web/app/styles/tokens.css`. Five colors per mode can be customised (accent, background, cards, navigation, borders); text, fills, tints and status tones are derived in Oklab against whichever surface an element paints (`Theme.kt`, `SurfaceTokens`). The default Harbor theme is indigo `#4353d9` on white with a `#f4f5f7` navigation canvas; Ocean, Forest, Violet and Sunset match the web presets, each with a dark palette.

Type is Geist and Geist Mono, bundled as variable fonts in `res/font`. Sizes are in `sp` and follow system font scaling: 20 semibold page titles, 15 medium file names, 12–13 metadata. Icons are lucide, generated into `Icons.kt` by `generate-icons.mjs`.

Shell: the phone layout of the web app (`apps/web/app/styles/mobile.css`). A top bar on the page color holds the brand mark, a pill search field, the activity bell and the avatar menu; a bottom tab bar (Drive, Shared, Trash, More) has a hairline on top and marks the active tab with an accent-soft pill behind its icon. Pages have a 16dp gutter and a 24sp title. More, item actions, details, versions and every dialog are bottom sheets with a grab handle and 20dp top corners; dialog actions stack full width, primary first. My Drive's root lists cloud files after three pinned virtual folders (Synced Folders, Backups, Archives; no selection or actions) that open to one folder per device, then that device's folders. My Drive has a floating 56dp “New” button that turns into a selection dock while items are selected. Sign-in puts the brand and tagline on the navigation color and the form on a sheet; accounts with two-step verification get a code step (authenticator or email code) on the same sheet.

    [◉] (  Search your files   )  🔔  (A)
    My Drive
    5 items                      [≡|⊞]
    (Type: All items ⌄) (Modified ⌄) (Sort ⌄)
    ☐ Name
      [⟳] Synced Folders                 ›
      [▣] Backups                        ›
      [▦] Archives                       ›
    ☐ [▢] Documents                    ⋯
          Folder · Sep 30, 2026
    ☐ [▤] Project brief.pdf      ★     ⋯
          2.5 MB · Sep 30, 2026      [+]
    ─────────────────────────────────────
       Drive    Shared    Trash    More

UI files: `Shell.kt` (frame, top bar, tab bar, floating cards), `DriveScreen.kt`, `SharedScreen.kt`, `BackupsScreen.kt` (the backup panel shown in My Drive and its history), `TrashScreen.kt` (and in-place search results), `SyncScreen.kt` (Sync page and its sheets), `MoreScreens.kt` (Devices, Storage, Notifications, Settings), `SettingsSections.kt` (two-step verification, rclone tokens, email, sync & backups), `E2eeScreens.kt` (unlock page and the encryption card), `PublicLinks.kt`, `PreviewViews.kt`, `AuthScreens.kt`, `Sheets.kt`. State lives in `WorkspaceModel.kt`, `DriveModel.kt`, `PagesModel.kt`, `SyncModel.kt`, `Uploads.kt` and `EncryptionState` (`E2eeScreens.kt`).

Backups live in My Drive → Backups (one folder per device), as on the web; there is no Backups tab. A backup's top folder shows the web `BackupFolderPanel` above its files: a status pill (Backed up, Backing up with planned totals, Restoring with the engine's file-by-file progress for this phone, Backup paused · storage full, Needs storage, Paused, Archived, Stopped, Needs attention), the device it comes from, this phone's controls for its own backups, and History (runs with their files, restores). Synced and backed-up folders show the same storage states as desktop when cloud storage runs out: “Sync paused · storage full”, “Needs storage”, with what waits.

Previews open in a sheet once the file is downloaded and verified: images, text (first 1 MB), PDFs page by page, video and audio with play/pause, with Open in another app, Share and Download. Public links (flag `public-links`) open from the item menu (“Get public link”, “Manage public link”) and are listed under Shared.

End-to-end encryption follows the web (`e2ee-unlock.tsx`, `e2ee-settings.tsx`) and desktop (`E2eeUnlock`, `EncryptionPanel`). While an encrypted account is locked on this phone, the file pages (Drive, Shared, Trash, Sync and search) show the unlock page in their place: lock tile, “Unlock your files”, the encryption passphrase with a “Forgot it?” link to the recovery key path (recovery key, new passphrase and its confirmation), “Remember this phone”, Unlock, and Sign out; Settings and the rest stay reachable. Settings → End-to-end encryption shows the status badge (On, Turning off, Off), this phone (Locked or Unlocked, with Lock), existing files' conversion progress, passphrase (Change) and recovery key (Create new key, shown once with Download and Copy, then one group typed back), turning off (on the web), and an Unlocked devices card (This phone badge, Remember, Lock now, Remove). Accounts with the `e2ee` flag can turn it on from the phone with the web's four-step wizard. Actions the server refuses on encrypted accounts (Send, Share access, folder ZIPs, Save to My Drive, shared sync invitations, rclone tokens) are hidden, with the web's explanation in their place.

    🔒
    Unlock your files
    Signed in as you@example.com. Enter your
    encryption passphrase to read your files on this phone.
    Encryption passphrase              Forgot it?
    [••••••••••••••••••••]            [👁]
    ☑ Remember this phone
    [            Unlock             ]
    Use recovery key instead · Sign out

Sync follows the desktop sync page (`apps/desktop/src/sync-page.tsx`) in phone form. The Sync page opens from More and from the “This phone” card at the top of My Drive → Sync. It shows a status card (Up to date, Syncing, Paused, Offline, Action required, Waiting for another device, Locked · unlock to sync, the storage states, with the transfer in progress or the number of small files uploading together in a batch), “Sync a folder”, Pause/Resume and Sync now, then cards for Needs attention, On this phone, Waiting for another device, Shared with you, On your other devices, Recent activity and Backups from this phone. Each folder row opens a sheet with its details and actions (pause, exclusions, change local folder, share, stop syncing on this phone, remove from sync everywhere). Setup is one sheet: choose the folder on the phone (system folder picker), then for a new folder either let harbor0 create a cloud folder named after it or browse My Drive and pick or create one. The backup panel in My Drive shows this phone's controls (Back up now, Pause, Exclusions, Archive / Restore archived folder, Disconnect) only for folders backed up from this phone; folders from other devices stay read-only. What sync and backups are doing (active, paused by you or the app, blocked because storage is full or the phone is locked, idle) is reported to the server for the staff console, as desktop does.

    Sync
    ┌ [▯] Sync on this phone      (✓ Up to date) ┐
    │     2 folders · Checked just now            │
    └─────────────────────────────────────────────┘
    [ + Sync a folder ] [ ‖ Pause ] [↻]
    On this phone
    ┌ ▢ Camera              (Syncing) › ┐
    │   My Drive / Phone / Camera       │
    │   128 files · Synced 2 minutes ago│
    └───────────────────────────────────┘

Photo backup is one more backup folder from this phone, named Photos (`PhotoScreens.kt`). “Back up photos” sits beside “Back up a folder” (My Drive › Backups and this phone's folder there, the Sync page's Backups from this phone, Settings › Sync & backups) until the phone has one. Setup is a sheet: “Back up photos”, where the photos go (Backups › phone › Photos, one folder per album, kept when deleted from the phone), the two options as checkbox rows (Include videos, Back up over mobile data), Start backup, then Android's permission dialog; a refusal shows a warning with Open settings. Once started, My Drive opens the Photos folder, and its backup panel shows progress as for any backup (Backing up, “Saved X of Y files”), or “Waiting for Wi-Fi”. In the panel, Exclusions becomes Albums (a checkbox per album), Archive is replaced by the options, and access problems show as an alert with Allow access, Choose more (only some photos allowed) or Open settings.

    ┌ [▣] Backup  (✓ Backed up)                    ┐
    │     Photos on this phone · Last backed up …  │
    │ Backed up from this phone. New photos are    │
    │ saved within a few minutes.                  │
    │ [          ⇪ Back up now           ]          │
    │ [ ‖ Pause ]        [ ⊘ Albums ]               │
    │ ☑ Include videos                             │
    │ ☐ Back up over mobile data                   │
    │ [        Disconnect backup         ]          │
    │ ⟲ History                                    │
    └──────────────────────────────────────────────┘

Components live in `Kit.kt` and `PhoneKit.kt` (sheets, chips, checkboxes, FAB, selection dock) and correspond to `apps/web/components/ui`: buttons (primary, outline, ghost, danger, link), bordered inputs with a focus ring and a label above, segmented control, badges, cards, alerts, progress, skeleton rows, menus, dialogs, empty states and the toast. Use them rather than Material components so heights, radii and borders stay consistent. Controls are 44dp (36dp small) with 48dp touch targets; radii are 6/8/12/16dp. Content is capped at 840dp on tablets.

States: skeleton rows on first load, the current list stays visible during background refreshes, failed loads show a retry state instead of an empty one, and confirmations use a danger button. Appearance changes apply at once and are saved to the account in the background, with a retry if saving fails.
