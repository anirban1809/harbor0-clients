import Foundation
import Photos
import PhotosUI
import Network
import SwiftUI
import UniformTypeIdentifiers

// Photo and video backup from the iPhone's library (Android PhotoLibrary.kt / PhotoStore.kt / PhotoScreens.kt),
// with PhotoKit in place of MediaStore. The library is backed up as a backup folder named “Photos” with one
// folder per album; assets are read (exported from PhotoKit) only when they are uploaded. Photos skip the
// one-hour wait folder backups use. iOS has no foreground service: passes run while the app is open, when
// the library changes, and in the BGAppRefresh/BGProcessing time iOS grants the sync engine. Restores asked
// for elsewhere are added back to the library, in their album (Android MediaStoreTree.publish).

/// What the photo backup keeps between runs, per account.
struct PhotoBackupState: Codable, Equatable {
    var backupId: String
    var remoteId: String
    var includeVideos = true
    var cellular = false
    var paused = false
    /// Albums left out, by name.
    var excludedAlbums: [String] = []
    /// What was saved, by backup path: the asset, its modification time and the drive item.
    var saved: [String: Saved] = [:]
    /// Album folder ids in the backup.
    var folders: [String: String] = [:]
    var lastRunAt: String? = nil
    var lastError: String? = nil
    /// Set up again on an earlier backup: what it already holds is matched before anything is uploaded.
    var seedPending: Bool? = nil
    /// Restores handled but not yet acknowledged, by id: "" when done, else the error. Never added twice.
    var restored: [String: String]? = nil
    struct Saved: Codable, Equatable { var asset: String; var mtime: Double; var itemId: String; var revision: Int }
}

/// One asset as a backup path. Scans read only PhotoKit metadata; the bytes are exported on upload.
struct PhotoEntry: Equatable {
    let asset: String
    let path: String
    let album: String
    let mtime: Double
    let video: Bool
}

enum PhotoPaths {
    /// A name safe for a backup folder: no slashes or control characters, not empty.
    static func safe(_ name: String) -> String {
        let cleaned = name.map { c -> Character in c == "/" || c == "\\" || c == ":" || c.unicodeScalars.contains { CharacterSet.controlCharacters.contains($0) } ? "_" : c }
        let text = String(cleaned).trimmingCharacters(in: .whitespaces)
        return text.isEmpty || text == "." || text == ".." ? "Untitled" : String(text.prefix(200))
    }
    /// Paths for assets in their albums; equal names in an album are numbered in a stable order, as Android does.
    static func layout(_ assets: [(asset: String, album: String, name: String, mtime: Double, video: Bool)]) -> [PhotoEntry] {
        var used: [String: Int] = [:]
        var out: [PhotoEntry] = []
        for a in assets.sorted(by: { ($0.album, $0.asset) < ($1.album, $1.asset) }) {
            let album = safe(a.album), name = safe(a.name)
            var path = album + "/" + name
            let key = path.lowercased()
            if let n = used[key] {
                let base = (name as NSString).deletingPathExtension, ext = (name as NSString).pathExtension
                path = album + "/" + base + " (\(n + 1))" + (ext.isEmpty ? "" : "." + ext)
                used[key] = n + 1
            } else { used[key] = 1 }
            out.append(PhotoEntry(asset: a.asset, path: path, album: album, mtime: a.mtime, video: a.video))
        }
        return out
    }
    /// The newest “Photos” backup this iPhone made that is still running (Android `reusablePhotoRoot`).
    static func reusable(_ roots: [BackupRoot], deviceId: String) -> BackupRoot? {
        roots.filter { $0.localPathDisplayName == "Photos" && $0.deviceId == deviceId && ["ACTIVE", "PAUSED", "ERROR"].contains($0.state) }
            .max { ($0.createdAt ?? "") < ($1.createdAt ?? "") }
    }
    /**
     What a reused backup already holds (Android `photoSeed`): its albums, and the files that match the library by
     path, size and time, so they aren't uploaded again. [remote]: album name → folder id and its files.
     */
    static func seed(_ entries: [PhotoEntry], sizes: [String: Int64], remote: [String: (id: String, items: [DriveItem])])
        -> (saved: [String: PhotoBackupState.Saved], folders: [String: String]) {
        var saved: [String: PhotoBackupState.Saved] = [:]
        var folders: [String: String] = [:]
        for entry in entries {
            guard let album = remote[entry.album] else { continue }
            folders[entry.album] = album.id
            let name = SyncPaths.basename(entry.path)
            guard let item = album.items.first(where: { !$0.isFolder && $0.name == name }), let size = sizes[entry.asset], item.sizeBytes == size,
                  let modified = Format.parse(item.modifiedAt), (modified.timeIntervalSince1970 * 1000).rounded() == entry.mtime else { continue }
            saved[entry.path] = .init(asset: entry.asset, mtime: entry.mtime, itemId: item.id, revision: item.revision ?? 1)
        }
        return (saved, folders)
    }
    /// Only photos and videos go back into the library (true: a video); anything else is downloaded instead.
    static func restorable(_ name: String) -> Bool? {
        guard let type = UTType(filenameExtension: (name as NSString).pathExtension) else { return nil }
        if type.conforms(to: .movie) { return true }
        if type.conforms(to: .image) { return false }
        return nil
    }
}

@MainActor
final class PhotoBackup: NSObject, ObservableObject, PHPhotoLibraryChangeObserver {
    @Published private(set) var state: PhotoBackupState?
    @Published private(set) var access: PHAuthorizationStatus = PHPhotoLibrary.authorizationStatus(for: .readWrite)
    @Published private(set) var running = false
    @Published private(set) var progress: (done: Int, total: Int)?
    /// The photos going up now, by backup path (“Camera/IMG_0001.HEIC”), and how much of each has gone, 0 to 1.
    @Published private(set) var sending: [String: Double] = [:]
    /// How many photos' worth of the ones going up has gone: a long video keeps moving, not only whole photos.
    var fileFraction: Double { sending.values.reduce(0, +) }
    @Published private(set) var pending = 0
    @Published private(set) var waitingForWiFi = false
    @Published private(set) var albums: [String] = []
    let api: HarborAPI
    /// Keeps a backup the user started going after they leave harbor0.
    weak var sync: SyncCenter?
    private var userId: String?
    private var task: Task<Void, Never>?
    /// A pass running or about to.
    var busy: Bool { running || task != nil }
    private var observing = false
    private var expensive = false
    private var restoreLoop: Task<Void, Never>?
    private let monitor = NWPathMonitor()

    init(api: HarborAPI) {
        self.api = api
        super.init()
        monitor.pathUpdateHandler = { [weak self] path in
            Task { @MainActor in
                guard let self else { return }
                let was = self.expensive
                self.expensive = path.isExpensive
                // Photos waiting for Wi-Fi go as soon as the iPhone is on it.
                if was && !path.isExpensive && self.waitingForWiFi { self.schedule(after: 5) }
            }
        }
        monitor.start(queue: DispatchQueue(label: "app.harbor0.photos.network"))
    }

    private func file(_ userId: String) -> URL {
        let support = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0].appendingPathComponent("harbor0/photos", isDirectory: true)
        try? FileManager.default.createDirectory(at: support, withIntermediateDirectories: true)
        let key = E2ee.sha256Hex(Data((api.baseURL.absoluteString + "\n" + userId).utf8)).prefix(16)
        return support.appendingPathComponent("\(key).json")
    }
    private func persist() {
        guard let userId, let state, let data = try? JSONEncoder().encode(state) else { return }
        try? data.write(to: file(userId), options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
    }

    /// Loads this account's photo backup, if it has one, and starts watching the library.
    func connect(userId: String) {
        guard self.userId != userId else { return }
        self.userId = userId
        state = (try? Data(contentsOf: file(userId))).flatMap { try? JSONDecoder().decode(PhotoBackupState.self, from: $0) }
        access = PHPhotoLibrary.authorizationStatus(for: .readWrite)
        if state != nil && allowed && !observing { PHPhotoLibrary.shared().register(self); observing = true }
        schedule(after: 2)
    }
    func disconnect() {
        task?.cancel(); task = nil
        restoreLoop?.cancel(); restoreLoop = nil
        if observing { PHPhotoLibrary.shared().unregisterChangeObserver(self); observing = false }
        userId = nil; state = nil; progress = nil; pending = 0
    }
    var allowed: Bool { access == .authorized || access == .limited }
    /// Asks iOS for access where it hasn't been asked yet (after a reinstall, say); Settings has no switch until then.
    func requestAccess() async {
        access = await PHPhotoLibrary.requestAuthorization(for: .readWrite)
        if allowed {
            if !observing { PHPhotoLibrary.shared().register(self); observing = true }
            backUpNow()
        }
    }

    /// While harbor0 is open, restores asked for elsewhere are looked for every two minutes.
    func foreground() {
        access = PHPhotoLibrary.authorizationStatus(for: .readWrite)
        guard restoreLoop == nil else { return }
        restoreLoop = Task { [weak self] in
            while !Task.isCancelled {
                if let self, !self.running { await self.checkRestores() }
                try? await Task.sleep(nanoseconds: 120_000_000_000)
            }
        }
    }
    func background() { restoreLoop?.cancel(); restoreLoop = nil }

    nonisolated func photoLibraryDidChange(_ changeInstance: PHChange) {
        // New photos start a pass a little later, so a burst of shots goes in one run.
        Task { @MainActor in self.schedule(after: 60) }
    }

    /// Asks for library access, then makes (or reuses) the “Photos” backup of this iPhone.
    func setUp(deviceId: String?, includeVideos: Bool, cellular: Bool) async throws {
        guard let userId, let deviceId else { throw APIError(status: 0, message: "Sync is starting. Try again in a moment.") }
        access = await PHPhotoLibrary.requestAuthorization(for: .readWrite)
        guard allowed else { throw APIError(status: 0, message: "harbor0 needs access to your photos to back them up. Allow it in Settings › harbor0 › Photos.") }
        // An earlier “Photos” backup of this iPhone that is still running is reused; files it holds aren't uploaded again.
        let catalog: BackupCatalog = try await api.request("/v1/backups")
        let root: BackupRoot
        let reused = PhotoPaths.reusable(catalog.items, deviceId: deviceId)
        if let reused {
            root = reused
        } else {
            struct Created: Decodable { let root: BackupRoot }
            let created: Created = try await api.request("/v1/backups", method: "POST", body: [
                "operationId": UUID().uuidString.lowercased(), "deviceId": deviceId, "name": "Photos"])
            root = created.root
        }
        state = PhotoBackupState(backupId: root.id, remoteId: root.remoteRootDriveItemId, includeVideos: includeVideos, cellular: cellular,
                                 seedPending: reused != nil ? true : nil)
        _ = userId
        persist()
        if !observing { PHPhotoLibrary.shared().register(self); observing = true }
        backUpNow()
    }
    /// Back up now: a pass at once, which goes on after the user leaves harbor0 until it is done.
    func backUpNow() {
        schedule(after: 0, manual: true)
        sync?.continueInBackground(.photos, title: "Backing up photos")
    }
    func update(_ change: (inout PhotoBackupState) -> Void) {
        guard var next = state else { return }
        change(&next)
        state = next
        persist()
        schedule(after: 1)
    }
    /// Stops backing up photos; the backup and its files stay in harbor0.
    func disconnect(remove: Bool) async throws {
        guard let state else { return }
        if remove { let _: EmptyResponse = try await api.request("/v1/backups/\(state.backupId)", method: "DELETE") }
        task?.cancel()
        if let userId { try? FileManager.default.removeItem(at: file(userId)) }
        self.state = nil; pending = 0; progress = nil
    }

    func schedule(after seconds: Double, manual: Bool = false) {
        guard state != nil, task == nil || manual else { return }
        task?.cancel()
        task = Task { [weak self] in
            if seconds > 0 { try? await Task.sleep(nanoseconds: UInt64(seconds * 1_000_000_000)) }
            guard let self, !Task.isCancelled else { return }
            await self.run(manual: manual)
            self.task = nil
        }
    }

    /// Every asset in the library as a backup path, in its first user album, else “Camera”.
    nonisolated static func scan(includeVideos: Bool, excluded: Set<String>) -> (entries: [PhotoEntry], albums: [String]) {
        let options = PHFetchOptions()
        options.includeHiddenAssets = false
        options.predicate = includeVideos ? NSPredicate(format: "mediaType == %d || mediaType == %d", PHAssetMediaType.image.rawValue, PHAssetMediaType.video.rawValue)
            : NSPredicate(format: "mediaType == %d", PHAssetMediaType.image.rawValue)
        var albumOf: [String: String] = [:]
        var albums: [String] = []
        let collections = PHAssetCollection.fetchAssetCollections(with: .album, subtype: .albumRegular, options: nil)
        collections.enumerateObjects { collection, _, _ in
            let title = collection.localizedTitle ?? "Album"
            albums.append(title)
            PHAsset.fetchAssets(in: collection, options: options).enumerateObjects { asset, _, _ in
                if albumOf[asset.localIdentifier] == nil { albumOf[asset.localIdentifier] = title }
            }
        }
        var list: [(asset: String, album: String, name: String, mtime: Double, video: Bool)] = []
        PHAsset.fetchAssets(with: options).enumerateObjects { asset, _, _ in
            let album = albumOf[asset.localIdentifier] ?? "Camera"
            if excluded.contains(PhotoPaths.safe(album)) { return }
            let resources = PHAssetResource.assetResources(for: asset)
            let original = resources.first { $0.type == .photo || $0.type == .video || $0.type == .fullSizePhoto } ?? resources.first
            let name = original?.originalFilename ?? "\(asset.localIdentifier.prefix(8)).\(asset.mediaType == .video ? "mov" : "jpg")"
            let date = asset.modificationDate ?? asset.creationDate ?? Date(timeIntervalSince1970: 0)
            list.append((asset.localIdentifier, album, name, (date.timeIntervalSince1970 * 1000).rounded(), asset.mediaType == .video))
        }
        return (PhotoPaths.layout(list), ["Camera"] + albums.sorted())
    }

    /// The library's albums, for choosing which are backed up before the first pass has listed them.
    func loadAlbums() async {
        guard allowed else { return }
        albums = await Task.detached(priority: .userInitiated) {
            var titles: [String] = []
            PHAssetCollection.fetchAssetCollections(with: .album, subtype: .albumRegular, options: nil).enumerateObjects { collection, _, _ in
                titles.append(collection.localizedTitle ?? "Album")
            }
            return ["Camera"] + titles.sorted()
        }.value
    }

    /// Each asset's original size, from PhotoKit's metadata; nothing is downloaded.
    nonisolated static func sizes(_ entries: [PhotoEntry]) -> [String: Int64] {
        let assets = PHAsset.fetchAssets(withLocalIdentifiers: entries.map(\.asset), options: nil)
        var out: [String: Int64] = [:]
        assets.enumerateObjects { asset, _, _ in
            let resources = PHAssetResource.assetResources(for: asset)
            let original = resources.first { $0.type == .fullSizePhoto || $0.type == .fullSizeVideo } ?? resources.first { $0.type == .photo || $0.type == .video } ?? resources.first
            if let size = original?.value(forKey: "fileSize") as? Int64 { out[asset.localIdentifier] = size }
            else if let size = (original?.value(forKey: "fileSize") as? NSNumber)?.int64Value { out[asset.localIdentifier] = size }
        }
        return out
    }

    /// A reused backup's albums and their files, as the server lists them.
    private func remoteAlbums(_ state: PhotoBackupState) async throws -> [String: (id: String, items: [DriveItem])] {
        var out: [String: (id: String, items: [DriveItem])] = [:]
        for album in try await all(state.remoteId) where album.isFolder {
            out[album.name] = (album.id, try await all(album.id))
        }
        return out
    }
    private func all(_ parent: String) async throws -> [DriveItem] {
        var items: [DriveItem] = []
        var cursor: String?
        repeat {
            let page = try await api.list(parentID: parent, cursor: cursor)
            items += page.items
            cursor = page.nextCursor
        } while cursor != nil
        return items
    }

    /// Restores asked for this backup from the web or another device, put back into the library and acknowledged.
    func checkRestores() async {
        guard var current = state, allowed, api.signedIn, !api.transferKeys.view().locked else { return }
        let url = "/v1/backups/\(current.backupId)"
        do {
            // Pending entries are removed on acknowledgement; always read from the beginning.
            let page: Page<BackupRestore> = try await api.request("\(url)/pending-restores")
            for request in page.items {
                guard let itemId = request.itemId, let versionId = request.versionId else { continue }
                var outcome = current.restored?[request.id]
                if outcome == nil {
                    do { try await restore(request.relativePath, itemId: itemId, versionId: versionId, into: &current); outcome = "" }
                    catch {
                        if error.isNetwork || error is CancellationError || (error as? APIError).map({ $0.status >= 500 || $0.status == 429 }) == true { throw error }
                        outcome = String(error.localizedDescription.prefix(2000))
                    }
                    // Recorded before acknowledging, so a retry after a dropped connection doesn't add the photo twice.
                    current.restored = (current.restored ?? [:]).merging([request.id: outcome!]) { $1 }
                    state = current; persist()
                }
                let _: EmptyResponse = try await api.request("\(url)/restores/\(request.id)/complete", method: "POST",
                                                             body: outcome!.isEmpty ? [:] : ["error": outcome!])
                current.restored?[request.id] = nil
                state = current; persist()
            }
        } catch {
            // Tried again at the next check.
        }
    }

    /// One restored file, downloaded and checked, added to the library in its album. A photo still on this iPhone
    /// isn't replaced: harbor0 never deletes photos here.
    private func restore(_ path: String, itemId: String, versionId: String, into current: inout PhotoBackupState) async throws {
        let parts = path.split(separator: "/", omittingEmptySubsequences: false).map(String.init)
        guard parts.count == 2, !parts[0].isEmpty, !parts[1].isEmpty else {
            throw APIError(status: 0, message: "Photos can only be restored into an album.")
        }
        let (album, name) = (parts[0], parts[1])
        guard let video = PhotoPaths.restorable(name) else {
            throw APIError(status: 0, message: "“\(name)” isn’t a photo or video, so it can’t be restored to the photo library. Download it instead.")
        }
        if let saved = current.saved[path], PHAsset.fetchAssets(withLocalIdentifiers: [saved.asset], options: nil).firstObject != nil {
            throw APIError(status: 0, message: "“\(name)” is still on this iPhone. Delete it in Photos first, or download this version instead.")
        }
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent("HarborPhotoRestore-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: directory) }
        let file = directory.appendingPathComponent(name)
        try await SyncTransfers.download(api: api, ["driveItemId": itemId, "versionId": versionId], to: file)
        var created: String?
        try await PHPhotoLibrary.shared().performChanges {
            let request = PHAssetCreationRequest.forAsset()
            let options = PHAssetResourceCreationOptions()
            options.originalFilename = name
            request.addResource(with: video ? .video : .photo, fileURL: file, options: options)
            guard let placeholder = request.placeholderForCreatedAsset else { return }
            created = placeholder.localIdentifier
            // “Camera” is the library itself; any other album is found by name, or made.
            guard PhotoPaths.safe(album) != "Camera" else { return }
            let match = PHFetchOptions()
            match.predicate = NSPredicate(format: "title == %@", album)
            if let existing = PHAssetCollection.fetchAssetCollections(with: .album, subtype: .albumRegular, options: match).firstObject {
                PHAssetCollectionChangeRequest(for: existing)?.addAssets([placeholder] as NSArray)
            } else {
                PHAssetCollectionChangeRequest.creationRequestForAssetCollection(withTitle: album).addAssets([placeholder] as NSArray)
            }
        }
        // Recorded as saved, so the next pass doesn't back the restored copy up again.
        if let created, let asset = PHAsset.fetchAssets(withLocalIdentifiers: [created], options: nil).firstObject {
            let date = asset.modificationDate ?? asset.creationDate ?? Date(timeIntervalSince1970: 0)
            let item: ItemResponse? = try? await api.request("/v1/drive/items/\(itemId)")
            current.saved[path] = .init(asset: created, mtime: (date.timeIntervalSince1970 * 1000).rounded(), itemId: itemId, revision: item?.item.revision ?? 1)
        }
    }

    /// Exports an asset's original file (downloading it from iCloud Photos if needed), dated as the asset.
    nonisolated static func export(_ entry: PhotoEntry, to target: URL) async throws {
        guard let asset = PHAsset.fetchAssets(withLocalIdentifiers: [entry.asset], options: nil).firstObject else {
            throw APIError(status: 0, message: "This photo is no longer in the library.")
        }
        let resources = PHAssetResource.assetResources(for: asset)
        guard let resource = resources.first(where: { $0.type == .fullSizePhoto || $0.type == .fullSizeVideo })
                ?? resources.first(where: { $0.type == .photo || $0.type == .video }) ?? resources.first else {
            throw APIError(status: 0, message: "This photo can’t be read.")
        }
        try? FileManager.default.removeItem(at: target)
        let options = PHAssetResourceRequestOptions()
        options.isNetworkAccessAllowed = true
        try await PHAssetResourceManager.default().writeData(for: resource, toFile: target, options: options)
        try FileManager.default.setAttributes([.modificationDate: Date(timeIntervalSince1970: entry.mtime / 1000)], ofItemAtPath: target.path)
    }

    /// One pass: scan, then upload what's new or changed in a backup run. With a deadline (background time), no new
    /// photo starts after it; the rest wait for the next pass.
    func run(manual: Bool = false, until deadline: Date? = nil) async {
        guard var current = state, allowed, !running, api.signedIn else { return }
        if current.paused && !manual { return }
        if api.transferKeys.view().locked { return }
        if expensive && !current.cellular { waitingForWiFi = true; return }
        waitingForWiFi = false
        running = true
        defer { running = false; progress = nil; sending = [:] }
        await checkRestores()
        if let restored = state { current = restored }
        let scan = await Task.detached(priority: .utility) { [current] in
            Self.scan(includeVideos: current.includeVideos, excluded: Set(current.excludedAlbums))
        }.value
        albums = scan.albums
        if current.seedPending == true {
            // Matched by path, size and time; anything that doesn't match is uploaded as usual.
            do {
                let remote = try await remoteAlbums(current)
                let candidates = scan.entries.filter { remote[$0.album] != nil && current.saved[$0.path] == nil }
                let sizes = await Task.detached(priority: .utility) { Self.sizes(candidates) }.value
                let seeded = PhotoPaths.seed(candidates, sizes: sizes, remote: remote)
                current.saved.merge(seeded.saved) { old, _ in old }
                current.folders.merge(seeded.folders) { old, _ in old }
                current.seedPending = nil
                state = current; persist()
            } catch {
                current.lastError = error.localizedDescription; state = current; persist(); return
            }
        }
        let changed = scan.entries.filter { entry in current.saved[entry.path].map { $0.asset != entry.asset || $0.mtime != entry.mtime } ?? true }
        pending = changed.count
        guard !changed.isEmpty else {
            current.lastRunAt = SyncClock.iso(); current.lastError = nil; state = current; persist(); return
        }
        let url = "/v1/backups/\(current.backupId)"
        let runId = UUID().uuidString.lowercased()
        do {
            // Planned totals, so the run's progress shows on the web and in the console.
            let sizes = await Task.detached(priority: .utility) { Self.sizes(changed) }.value
            let _: EmptyResponse = try await api.request("\(url)/runs", method: "POST", body: [
                "id": runId, "trigger": manual ? "MANUAL" : "AUTOMATIC", "plannedFileCount": changed.count,
                "plannedSizeBytes": changed.reduce(Int64(0)) { $0 + (sizes[$1.asset] ?? 0) }])
            var failure: String?
            let directory = FileManager.default.temporaryDirectory.appendingPathComponent("HarborPhotos-\(runId)", isDirectory: true)
            try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
            defer { try? FileManager.default.removeItem(at: directory) }
            // A few photos at a time: most are small, and each spends seconds on the server's steps around its bytes.
            var next = 0
            var done = 0
            var stopped = false
            progress = (0, changed.count)
            await withTaskGroup(of: (PhotoEntry, Result<DriveItem, Error>).self) { group in
                @MainActor func start() async -> Bool {
                    guard !stopped, next < changed.count, !Task.isCancelled, api.signedIn else { return false }
                    if let deadline, Date() > deadline { return false }
                    if expensive && !current.cellular { waitingForWiFi = true; return false }
                    let entry = changed[next]
                    next += 1
                    var parent = current.folders[entry.album]
                    if parent == nil {
                        // Made here, one at a time, so two photos of a new album don't both create it.
                        do {
                            let folder = try await ensureFolder(entry.album, in: current, runId: runId)
                            current.folders[entry.album] = folder
                            parent = folder
                        } catch {
                            failure = "\(entry.path): \(error.localizedDescription)"
                            return await start()
                        }
                    }
                    let known = current.saved[entry.path]
                    let backupId = current.backupId
                    sending[entry.path] = 0
                    // Its share of the bar is dropped with done += 1 below, so the progress never goes back between the two.
                    group.addTask { @MainActor [self] in
                        do { return (entry, .success(try await save(entry, parent: parent!, known: known, backupId: backupId, runId: runId, directory: directory))) }
                        catch { return (entry, .failure(error)) }
                    }
                    return true
                }
                for _ in 0..<Self.concurrentPhotos { if !(await start()) { break } }
                while let (entry, result) = await group.next() {
                    switch result {
                    case .success(let item):
                        current.saved[entry.path] = .init(asset: entry.asset, mtime: entry.mtime, itemId: item.id, revision: item.revision ?? 1)
                        state = current
                        persist()
                    case .failure(let error as APIError) where ["STORAGE_QUOTA_EXCEEDED", "E2EE_LOCKED", "BACKUP_DISCONNECTED"].contains(error.code ?? ""):
                        // Nothing more can be saved in this run.
                        failure = error.message
                        stopped = true
                    case .failure(is CancellationError): stopped = true
                    case .failure(let error): failure = "\(entry.path): \(error.localizedDescription)"
                    }
                    done += 1
                    sending[entry.path] = nil
                    progress = (done, changed.count)
                    pending = changed.count - done
                    _ = await start()
                }
            }
            let _: EmptyResponse = try await api.request("\(url)/runs/\(runId)/complete", method: "POST", body: failure.map { ["error": String($0.prefix(2000))] } ?? [:])
            current.lastRunAt = SyncClock.iso()
            current.lastError = failure
        } catch {
            current.lastError = error.localizedDescription
        }
        state = current
        persist()
    }

    /// Photos sent at once.
    static let concurrentPhotos = 3

    /// Exports one photo, uploads it and records it in the run; the caller saves it in this iPhone's state.
    private func save(_ entry: PhotoEntry, parent: String, known: PhotoBackupState.Saved?, backupId: String, runId: String, directory: URL) async throws -> DriveItem {
        let staged = directory.appendingPathComponent(UUID().uuidString + "-" + SyncPaths.basename(entry.path))
        try await Self.export(entry, to: staged)
        defer { try? FileManager.default.removeItem(at: staged) }
        let name = SyncPaths.basename(entry.path)
        var upload = UploadState(operationId: UUID().uuidString.lowercased())
        let item: DriveItem
        let sent: (Int64, Int64) -> Void = { [weak self] loaded, total in
            Task { @MainActor in
                // Whole percents only: each change redraws the pages showing it, and iOS reports sent bytes many times a second.
                let fraction = total > 0 ? (min(1, Double(loaded) / Double(total)) * 100).rounded(.down) / 100 : 0
                if let current = self?.sending[entry.path], current != fraction { self?.sending[entry.path] = fraction }
            }
        }
        do {
            item = try await SyncTransfers.upload(api: api, file: staged, name: name, parentId: parent, state: &upload, persist: { _ in },
                                                  existing: known.map { ($0.itemId, $0.revision) }, backup: (backupId, runId), progress: sent)
        } catch let error as APIError where error.code == "NAME_CONFLICT" && known == nil {
            // Saved by an earlier setup on this iPhone: keep it rather than upload it again if it matches.
            guard let existing = try await child(parent, name) else { throw error }
            item = try await SyncTransfers.upload(api: api, file: staged, name: name, parentId: parent, state: &upload, persist: { _ in },
                                                  existing: (existing.id, existing.revision ?? 1), backup: (backupId, runId), progress: sent)
        }
        let _: EmptyResponse = try await api.request("/v1/backups/\(backupId)/runs/\(runId)/files", method: "POST", body: [
            "relativePath": entry.path, "itemId": item.id, "versionId": item.currentVersionId ?? "", "sizeBytes": item.sizeBytes,
            "modifiedAt": SyncClock.iso(Date(timeIntervalSince1970: entry.mtime / 1000)), "savedAt": SyncClock.iso()])
        return item
    }

    private func ensureFolder(_ name: String, in state: PhotoBackupState, runId: String) async throws -> String {
        do {
            let response: ItemResponse = try await api.request("/v1/backups/\(state.backupId)/runs/\(runId)/folders", method: "POST", body: [
                "name": name, "parentId": state.remoteId, "operationId": UUID().uuidString.lowercased()])
            return response.item.id
        } catch let error as APIError where error.code == "NAME_CONFLICT" {
            if let existing = try await child(state.remoteId, name), existing.isFolder { return existing.id }
            throw error
        }
    }
    private func child(_ parent: String?, _ name: String) async throws -> DriveItem? {
        var cursor: String?
        repeat {
            let page = try await api.list(parentID: parent, cursor: cursor)
            if let match = page.items.first(where: { $0.name.precomposedStringWithCanonicalMapping.lowercased() == name.precomposedStringWithCanonicalMapping.lowercased() }) { return match }
            cursor = page.nextCursor
        } while cursor != nil
        return nil
    }
}

// MARK: - Screens

/// “Back up photos”, shown wherever “Back up a folder” is until this iPhone backs its photos up.
struct BackUpPhotosButton: View {
    @EnvironmentObject private var workspace: Workspace
    @Environment(\.tokens) private var tokens
    @State private var setup = false
    var body: some View {
        Button { setup = true } label: { Icon(.images, size: 15); Text("Back up photos") }
            .harborButton(.outline, size: .sm).disabled(!workspace.sync.ready).accessibilityIdentifier("backUpPhotos")
            .sheet(isPresented: $setup) { PhotoBackupSheet().environment(\.tokens, tokens).environmentObject(workspace) }
    }
}

/// Setup: what is saved where, the two options, then iOS's permission request.
struct PhotoBackupSheet: View {
    @EnvironmentObject private var workspace: Workspace
    @Environment(\.tokens) private var tokens
    @Environment(\.dismiss) private var dismiss
    @State private var videos = true
    // Not everyone has Wi-Fi: mobile data is the starting choice, asked for here rather than left in a toggle.
    @State private var cellular = true
    @State private var busy = false
    @State private var error: String?
    var body: some View {
        FittedSheet {
            SheetScaffold(title: "Back up photos", description: "Photos from this iPhone are saved to My Drive › Backups › this iPhone › Photos, one folder per album. harbor0 never deletes, moves or renames photos on your iPhone.") {
                VStack(alignment: .leading, spacing: 14) {
                    FieldLabel(text: "Back up on")
                    VStack(spacing: 8) {
                        choice(true, "Wi-Fi and mobile data", "Photos are saved wherever you are. Uses your data plan.", icon: .smartphone)
                        choice(false, "Wi-Fi only", "Photos wait until this iPhone is on Wi-Fi.", icon: .wifi)
                    }.accessibilityIdentifier("photosNetwork")
                    Toggle(isOn: $videos) {
                        VStack(alignment: .leading, spacing: 2) {
                            Text("Include videos").font(TypeScale.base)
                            Text("Back up videos as well as photos." + (cellular ? " Videos can use a lot of mobile data." : ""))
                                .font(TypeScale.xs).foregroundStyle(tokens.text2).fixedSize(horizontal: false, vertical: true)
                        }
                    }.tint(tokens.primary).accessibilityIdentifier("photosVideos")
                    Text("You can change these later in the backup’s settings.").font(TypeScale.xs).foregroundStyle(tokens.text2)
                    Text("New photos are backed up while harbor0 is open, and when iOS gives it time in the background.")
                        .font(TypeScale.xs).foregroundStyle(tokens.text2).fixedSize(horizontal: false, vertical: true)
                    if let error { AlertBanner(tone: .danger, text: error) }
                    SheetActions {
                        Button(busy ? "Setting up…" : "Allow access and back up") {
                            busy = true; error = nil
                            Task {
                                defer { busy = false }
                                do {
                                    try await workspace.photos.setUp(deviceId: workspace.sync.deviceId, includeVideos: videos, cellular: cellular)
                                    dismiss()
                                    // My Drive › Backups lists the new Photos folder at once.
                                    await workspace.catalog.load(workspace.api)
                                }
                                catch { self.error = error.localizedDescription }
                            }
                        }.harborButton(.primary, size: .lg, block: true).disabled(busy).accessibilityIdentifier("startPhotoBackup")
                        Button("Cancel") { dismiss() }.harborButton(.outline, size: .lg, block: true).disabled(busy)
                    }
                }
            }
        }
    }
    private func choice(_ value: Bool, _ title: String, _ detail: String, icon: Lucide) -> some View {
        let on = cellular == value
        return Button { cellular = value } label: {
            HStack(alignment: .top, spacing: 12) {
                Icon(icon, size: 18).foregroundStyle(on ? tokens.accentText : tokens.text2).padding(.top, 1)
                VStack(alignment: .leading, spacing: 2) {
                    Text(title).font(TypeScale.baseMedium).foregroundStyle(tokens.text)
                    Text(detail).font(TypeScale.xs).foregroundStyle(tokens.text2).fixedSize(horizontal: false, vertical: true)
                }
                Spacer(minLength: 0)
                if on { Icon(.check, size: 16).foregroundStyle(tokens.accentText) }
            }
            .padding(12)
            .background(on ? tokens.accentSoft.opacity(0.5) : .clear, in: RoundedRectangle(cornerRadius: 10))
            .overlay(RoundedRectangle(cornerRadius: 10).strokeBorder(on ? tokens.primary : tokens.line, lineWidth: 1))
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(on ? [.isSelected] : [])
        .accessibilityIdentifier(value ? "photosMobileData" : "photosWifiOnly")
    }
}

/// The photo backup's own card (Backups page, and its folder in My Drive): its status with one progress bar, a note when
/// there is something to say, Back up now and Pause, and a menu with history, albums, the two options and disconnect.
struct PhotoBackupCard: View {
    @ObservedObject var photos: PhotoBackup
    @EnvironmentObject private var workspace: Workspace
    @EnvironmentObject private var backups: BackupsModel
    @Environment(\.tokens) private var tokens
    @Environment(\.openURL) private var openURL
    @State private var menu = false
    @State private var albums = false
    @State private var disconnecting = false
    var body: some View {
        if let state = photos.state {
            let status = status(state)
            let wifi = photos.waitingForWiFi && photos.pending > 0 && !state.paused
            Card {
                VStack(alignment: .leading, spacing: 12) {
                    HStack(alignment: .top, spacing: 12) {
                        IconTile(icon: .images, size: 40, iconSize: 18, radius: 10, active: true)
                        VStack(alignment: .leading, spacing: 6) {
                            HStack(spacing: 8) {
                                Text("Photo backup").font(TypeScale.baseMedium.weight(.semibold)).lineLimit(1)
                                Badge(text: status.label, tone: status.tone, icon: status.icon, spinning: status.icon == .loaderCircle)
                                    .accessibilityIdentifier("photoBackupStatus")
                            }
                            Text(status.detail).font(TypeScale.sm).foregroundStyle(tokens.text2).fixedSize(horizontal: false, vertical: true)
                            // The photo on its way, under the run's own progress: one bar for the whole backup.
                            if let progress = photos.progress {
                                ProgressBar(value: min(0.99, (Double(progress.done) + photos.fileFraction) / Double(max(1, progress.total))), height: 4)
                                if let file = photos.sending.keys.sorted().first {
                                    Text(SyncPaths.basename(file)).font(TypeScale.xs).foregroundStyle(tokens.text2).lineLimit(1).truncationMode(.middle)
                                        .accessibilityIdentifier("photoBackupSending")
                                }
                            }
                        }
                    }
                    // The status above already says "Backed up" or shows the run: this only adds what it can't.
                    if !state.paused, !photos.running, !wifi, photos.pending > 0 {
                        Text("\(Format.count(photos.pending, "photo")) waiting to be saved.").font(TypeScale.xs).foregroundStyle(tokens.text2)
                    }
                    if wifi {
                        AlertBanner(tone: .neutral, text: "\(Format.count(photos.pending, "photo")) waiting for Wi-Fi.", actionLabel: "Use mobile data") {
                            photos.update { $0.cellular = true }
                        }.accessibilityIdentifier("photosWifi")
                    }
                    accessAlert
                    if let error = state.lastError, !photos.running { AlertBanner(tone: .danger, text: error) }
                    HStack(spacing: 8) {
                        // A run under way: Back up now would only be refused.
                        Button { photos.backUpNow() } label: { Icon(.cloudUpload, size: 14); Text("Back up now") }
                            .harborButton(.primary, size: .sm).disabled(photos.busy || !photos.allowed || state.paused).accessibilityIdentifier("photoBackupNow")
                        Button { photos.update { $0.paused.toggle() } } label: { Icon(state.paused ? .play : .pause, size: 14); Text(state.paused ? "Resume" : "Pause") }
                            .harborButton(.outline, size: .sm)
                        Spacer(minLength: 0)
                        Button { menu = true } label: { Icon(.ellipsis, size: 18) }
                            .harborButton(.ghost, size: .icon).accessibilityLabel("More backup options").accessibilityIdentifier("photoBackupMenu")
                    }
                }
            }
            .accessibilityElement(children: .contain)
            .accessibilityIdentifier("photoBackupCard")
            .sheet(isPresented: $menu) { menuSheet(state).environment(\.tokens, tokens) }
            .sheet(isPresented: $albums) { AlbumsSheet(photos: photos).environment(\.tokens, tokens) }
            .sheet(isPresented: $disconnecting) {
                AsyncConfirm(title: "Stop backing up photos?", description: "Stop backing up photos from this iPhone. Every saved photo and version stays in My Drive and becomes editable. Nothing on this iPhone is deleted.",
                             confirm: "Stop backing up", danger: true) {
                    do { try await photos.disconnect(remove: true); return true } catch { workspace.fail(error); return false }
                }.environment(\.tokens, tokens)
            }
        }
    }

    private func menuSheet(_ state: PhotoBackupState) -> some View {
        // A row that opens another sheet waits for this one to close.
        func then(_ open: @escaping () -> Void) { menu = false; DispatchQueue.main.asyncAfter(deadline: .now() + 0.45, execute: open) }
        return SheetMenu {
            MenuRow(title: "History", icon: .clock3, identifier: "photoBackupHistory") {
                menu = false; backups.select(state.backupId); workspace.navigate(.backups)
            }
            MenuRow(title: "Albums", icon: .images, identifier: "photoBackupAlbums") { then { albums = true } }
            MenuSeparator()
            MenuRow(title: "Include videos", icon: .film, checked: state.includeVideos, identifier: "photosVideos") {
                menu = false; photos.update { $0.includeVideos.toggle() }
            }
            MenuRow(title: "Back up over mobile data", icon: .smartphone, checked: state.cellular, identifier: "photosMobileData") {
                menu = false; photos.update { $0.cellular.toggle() }
            }
            MenuSeparator()
            // harbor0 never deletes photos on the iPhone, so there is no Archive here.
            MenuRow(title: "Stop backing up photos", icon: .unplug, tone: .danger) { then { disconnecting = true } }
        }
    }

    /// The photo library's access, when harbor0 can't see all of it, with the way to fix it.
    @ViewBuilder private var accessAlert: some View {
        if photos.access == .notDetermined {
            AlertBanner(tone: .warning, text: "harbor0 needs access to your photos to keep backing them up.", actionLabel: "Allow access") {
                Task { await photos.requestAccess() }
            }
        } else if !photos.allowed {
            AlertBanner(tone: .danger, text: "harbor0 can’t see your photos. Allow access in Settings to keep backing them up.", actionLabel: "Open Settings") {
                if let url = URL(string: UIApplication.openSettingsURLString) { openURL(url) }
            }
        } else if photos.access == .limited {
            AlertBanner(tone: .warning, text: "harbor0 can see only some of your photos. Only those are backed up.", actionLabel: "Choose more") {
                if let scene = UIApplication.shared.connectedScenes.compactMap({ $0 as? UIWindowScene }).first,
                   let top = scene.keyWindow?.rootViewController {
                    PHPhotoLibrary.shared().presentLimitedLibraryPicker(from: top.presentedViewController ?? top)
                }
            }
        }
    }

    private struct Status { var label: String; var tone: Tone; var icon: Lucide; var detail: String }
    private func status(_ state: PhotoBackupState) -> Status {
        let saved = "\(Format.count(state.saved.count, "item")) backed up" + (state.lastRunAt.map { " · checked \(Format.ago($0))" } ?? "")
        if state.paused { return Status(label: "Paused", tone: .neutral, icon: .pause, detail: "No new photos are saved until you resume.") }
        if workspace.needsUnlock { return Status(label: "Locked", tone: .warning, icon: .lock, detail: "Unlock your files to back up photos.") }
        if let progress = photos.progress {
            return Status(label: "Backing up", tone: .accent, icon: .loaderCircle, detail: "Saving \(min(progress.done + 1, progress.total)) of \(Format.count(progress.total, "photo"))")
        }
        if photos.running { return Status(label: "Backing up", tone: .accent, icon: .loaderCircle, detail: "Looking for new photos…") }
        if photos.waitingForWiFi && photos.pending > 0 { return Status(label: "Waiting for Wi-Fi", tone: .neutral, icon: .wifiOff, detail: saved) }
        if !photos.allowed { return Status(label: "Needs access", tone: .warning, icon: .triangleAlert, detail: saved) }
        if state.lastError != nil { return Status(label: "Needs attention", tone: .danger, icon: .circleAlert, detail: saved) }
        if photos.pending > 0 { return Status(label: "Waiting", tone: .neutral, icon: .clock3, detail: saved) }
        return Status(label: "Backed up", tone: .success, icon: .circleCheck, detail: saved + ". New photos are saved within a few minutes.")
    }
}

/// Which albums are backed up: everything else is left out.
private struct AlbumsSheet: View {
    @ObservedObject var photos: PhotoBackup
    @Environment(\.dismiss) private var dismiss
    @Environment(\.tokens) private var tokens
    var body: some View {
        VStack(spacing: 0) {
            SheetScaffold(title: "Albums to back up", description: "Photos in albums you turn off aren’t backed up. Photos already saved from them stay in the backup. Photos in several albums are saved in the first one.", divider: true, scrolls: true) {
                VStack(alignment: .leading, spacing: 4) {
                    if shown.isEmpty { Text(photos.albums.isEmpty ? "Loading albums…" : "No photos on this iPhone yet.").font(TypeScale.sm).foregroundStyle(tokens.text2) }
                    ForEach(shown, id: \.self) { album in
                        let excluded = photos.state?.excludedAlbums.contains(PhotoPaths.safe(album)) == true
                        Toggle(album, isOn: Binding(get: { !excluded }, set: { on in
                            photos.update { state in
                                let key = PhotoPaths.safe(album)
                                state.excludedAlbums.removeAll { $0 == key }
                                if !on { state.excludedAlbums.append(key) }
                            }
                        })).tint(tokens.primary).font(TypeScale.base).frame(minHeight: 44)
                    }
                }
            }
            Button("Done") { dismiss() }.harborButton(.outline, size: .lg, block: true).padding(.horizontal, 20).padding(.bottom, 16)
        }
        .cardSurface(paint: false)
        .modifier(TallSheet())
        .task { await photos.loadAlbums() }
    }
    /// Albums left out earlier stay listed after they empty, so they can be included again.
    private var shown: [String] {
        let excluded = (photos.state?.excludedAlbums ?? []).filter { left in !photos.albums.contains { PhotoPaths.safe($0) == left } }
        return photos.albums + excluded.sorted()
    }
}
