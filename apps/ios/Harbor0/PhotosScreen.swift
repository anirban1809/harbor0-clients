import SwiftUI
import ImageIO
import AVFoundation
import CryptoKit
import UniformTypeIdentifiers

// Photos (flag `photos`), as apps/web/components/photos-page.tsx: every photo and video in the account,
// newest first, as thumbnails or a list, by month or by the device each was synced or backed up from.

/// A photo or video, with the synced folder it is in, if any (`photoSchema`).
struct PhotoItem: Decodable, Identifiable, Equatable {
    let id: String
    let type: String
    let name: String
    var mimeType: String? = nil
    let sizeBytes: Int64
    let createdAt: String
    let updatedAt: String
    var modifiedAt: String? = nil
    var backupRootId: String? = nil
    var syncFolderId: String? = nil
    var encrypted: Bool? = nil
    var parentId: String? = nil
    var revision: Int? = nil
    var currentVersionId: String? = nil
    var takenAt: String { modifiedAt ?? createdAt }
    var item: DriveItem {
        DriveItem(id: id, parentId: parentId, type: type, name: name, mimeType: mimeType, sizeBytes: sizeBytes, updatedAt: updatedAt,
                  backupRootId: backupRootId, cloudState: nil, revision: revision, createdAt: createdAt, currentVersionId: currentVersionId,
                  encrypted: encrypted)
    }
}

enum MediaKind: String { case photo, video }
enum Media {
    private static let photoExtensions = Set("jpg jpeg jpe jfif png gif webp avif bmp tif tiff heic heif jxl svg ico dng cr2 cr3 crw nef nrw arw srf sr2 orf rw2 raf pef srw raw rwl 3fr erf kdc mrw mos x3f iiq".split(separator: " ").map(String.init))
    private static let videoExtensions = Set("mp4 m4v mov qt avi mkv webm wmv asf flv f4v mpg mpeg mpe m2v m2ts mts ts 3gp 3g2 ogv vob mxf dv insv lrv".split(separator: " ").map(String.init))
    /// Whether a file is a photo or a video, by its MIME type or else its extension (contracts `mediaKind`).
    static func kind(type: String, name: String, mimeType: String?) -> MediaKind? {
        guard type == "FILE" else { return nil }
        let mime = (mimeType ?? "").split(separator: ";").first.map { $0.trimmingCharacters(in: .whitespaces).lowercased() } ?? ""
        if mime.hasPrefix("image/") { return .photo }
        if mime.hasPrefix("video/") { return .video }
        guard mime.isEmpty || mime == "application/octet-stream" || mime == "binary/octet-stream" else { return nil }
        let ext = (name as NSString).pathExtension.lowercased()
        return photoExtensions.contains(ext) ? .photo : videoExtensions.contains(ext) ? .video : nil
    }
    /// Formats iOS draws itself (ImageIO and AVFoundation), so other files aren't fetched only to fail.
    static let showable: [MediaKind: Set<String>] = [
        .photo: Set("jpg jpeg jpe jfif png gif webp avif bmp heic heif tif tiff dng ico".split(separator: " ").map(String.init)),
        .video: Set("mp4 m4v mov".split(separator: " ").map(String.init))]
    /// Encrypted photos are decrypted whole on the phone, so only smaller ones get thumbnails.
    static let maxEncryptedThumbnail: Int64 = 20_000_000
    static let maxThumbnail: Int64 = 60_000_000
    static func canShow(_ item: PhotoItem) -> Bool {
        guard let kind = kind(type: item.type, name: item.name, mimeType: item.mimeType),
              showable[kind]!.contains((item.name as NSString).pathExtension.lowercased()) else { return false }
        // Videos of any size: only the parts a frame needs are read (`EncryptedVideoLoader`).
        if kind == .video { return true }
        if item.encrypted == true { return item.sizeBytes <= maxEncryptedThumbnail }
        return kind == .video || item.sizeBytes <= maxThumbnail
    }
}

/// Saved thumbnails (contracts `PHOTO_THUMBNAIL_PX`, `MAX_PHOTO_THUMBNAILS`): a JPEG this many pixels on its short
/// side, at most 3:1, and how many one request may save.
enum PhotoThumbnail {
    static let pixels: CGFloat = 360
    static let quality: CGFloat = 0.72
    static let maxSaved = 256 * 1024
    static let perRequest = 16
    static let batchBytes = 600_000
    /// The part of a `width`×`height` picture a thumbnail is drawn from: centred, at most 3:1.
    static func crop(width: CGFloat, height: CGFloat) -> CGRect {
        let short = min(width, height)
        let w = min(width, short * 3), h = min(height, short * 3)
        return CGRect(x: (width - w) / 2, y: (height - h) / 2, width: w, height: h)
    }
}

/// Thumbnails for the Photos page: short-lived links (`/v1/photos/links`, 60 at a time), then the thumbnail an app
/// saved when there is one; otherwise the original, drawn small, and that drawing saved for every app after this.
/// Kept on this phone as stored: an encrypted file's stays encrypted on disk and is decrypted only to show it.
@MainActor
final class PhotoThumbnails {
    private struct Link { let url: URL; let encrypted: Bool; let thumbnail: URL?; let until: Date }
    private var links: [String: Link?] = [:]
    private var waiting: [(String, CheckedContinuation<Link?, Error>)] = []
    private var flushing = false
    private var unsaved: [[String: String]] = []
    private var saveTask: Task<Void, Never>?
    private let images = NSCache<NSString, UIImage>()
    private var running = 0
    private let api: HarborAPI
    init(api: HarborAPI) { self.api = api; images.countLimit = 600 }

    private nonisolated static var disk: URL {
        FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0].appendingPathComponent("PhotoThumbnails", isDirectory: true)
    }
    /// Forgets every thumbnail kept on this phone, as when signing out.
    nonisolated static func clearDisk() { try? FileManager.default.removeItem(at: disk) }
    private nonisolated static func file(_ item: PhotoItem) -> URL {
        disk.appendingPathComponent(E2ee.hex(Data(SHA256.hash(data: Data("\(item.id):\(item.currentVersionId ?? "")".utf8)))) + (item.encrypted == true ? ".h0e" : ".jpg"))
    }
    private func key(_ item: PhotoItem) -> NSString { "\(item.id):\(item.currentVersionId ?? "")" as NSString }

    func cached(_ item: PhotoItem) -> UIImage? { images.object(forKey: key(item)) }

    private func link(_ id: String) async throws -> Link? {
        if let known = links[id], known == nil || known!.until > Date() { return known ?? nil }
        return try await withCheckedThrowingContinuation { continuation in
            waiting.append((id, continuation))
            guard !flushing else { return }
            flushing = true
            Task {
                try? await Task.sleep(nanoseconds: 50_000_000)
                flushing = false
                let batch = waiting
                waiting = []
                for start in stride(from: 0, to: batch.count, by: 60) {
                    let part = Array(batch[start..<min(start + 60, batch.count)])
                    struct Links: Decodable {
                        struct Item: Decodable { let id: String; let url: URL; var encrypted: Bool? = nil; var thumbnailUrl: URL? = nil }
                        let items: [Item]; let expiresAt: String
                    }
                    do {
                        let result: Links = try await api.request("/v1/photos/links", method: "POST", body: ["ids": Array(Set(part.map(\.0)))])
                        // Leave a minute to finish fetching before the link expires.
                        let until = (Format.parse(result.expiresAt) ?? Date()).addingTimeInterval(-60)
                        for (id, continuation) in part {
                            let found = result.items.first { $0.id == id }.map {
                                Link(url: $0.url, encrypted: $0.encrypted == true, thumbnail: $0.thumbnailUrl, until: until)
                            }
                            links[id] = found
                            continuation.resume(returning: found)
                        }
                    } catch { part.forEach { $0.1.resume(throwing: error) } }
                }
            }
        }
    }

    /// A small thumbnail of the photo or video, or nil when it can't be drawn.
    func thumbnail(_ item: PhotoItem, side: CGFloat) async -> UIImage? {
        if let image = cached(item) { return image }
        guard Media.kind(type: item.type, name: item.name, mimeType: item.mimeType) != nil else { return nil }
        let pixels = side * UIScreen.main.scale
        let encrypted = item.encrypted == true
        let keys = encrypted ? (try? api.transferKeys.forRead()) : nil
        if encrypted && keys == nil { return nil }
        let file = Self.file(item)
        // Kept on this phone from before.
        if let image = await Task.detached(priority: .utility, operation: { () -> UIImage? in
            guard let stored = try? Data(contentsOf: file) else { return nil }
            return Self.image(keys != nil ? ((try? FileCipher.decrypt(keys!, stored)) ?? Data()) : stored, pixels: pixels)
        }).value {
            images.setObject(image, forKey: key(item))
            return image
        }
        // A few at a time keeps scrolling smooth; originals of encrypted ones are downloaded and decrypted whole.
        while running >= 6 { try? await Task.sleep(nanoseconds: 80_000_000); if Task.isCancelled { return nil } }
        running += 1
        defer { running -= 1 }
        guard !Task.isCancelled, let link = try? await link(item.id) else { return nil }
        if let url = link.thumbnail, let image = await Task.detached(priority: .utility, operation: { () -> UIImage? in
            guard let (stored, response) = try? await SyncTransfers.storage.data(from: url),
                  (response as? HTTPURLResponse).map({ (200..<300).contains($0.statusCode) }) == true,
                  let image = Self.image(keys != nil ? ((try? FileCipher.decrypt(keys!, stored)) ?? Data()) : stored, pixels: pixels) else { return nil }
            Self.keep(stored, at: file)
            return image
        }).value {
            images.setObject(image, forKey: key(item))
            return image
        }
        guard Media.canShow(item) else { return nil }
        let kind = Media.kind(type: item.type, name: item.name, mimeType: item.mimeType)
        let drawn: (UIImage, Data?)? = await Task.detached(priority: .utility) {
            var full: CGImage?
            if kind == .video {
                // Storage sends it as a download, with no extension: say what it is, or AVFoundation can't read it.
                let ext = (item.name as NSString).pathExtension.lowercased()
                let mime = item.mimeType?.hasPrefix("video/") == true ? item.mimeType! : ext == "mov" ? "video/quicktime" : "video/mp4"
                let asset: AVURLAsset
                var loader: EncryptedVideoLoader?
                if let keys {
                    // Encrypted: AVFoundation asks for ranges, and only the chunks they touch are downloaded and decrypted.
                    loader = EncryptedVideoLoader(url: link.url, keys: keys, mime: mime)
                    asset = AVURLAsset(url: URL(string: "\(EncryptedVideoLoader.scheme)://video/\(item.id).\(ext.isEmpty ? "mp4" : ext)")!)
                    asset.resourceLoader.setDelegate(loader, queue: loader!.queue)
                } else {
                    asset = AVURLAsset(url: link.url, options: [AVURLAssetOverrideMIMETypeKey: mime])
                }
                let generator = AVAssetImageGenerator(asset: asset)
                generator.appliesPreferredTrackTransform = true
                generator.maximumSize = CGSize(width: PhotoThumbnail.pixels * 3, height: PhotoThumbnail.pixels * 3)
                full = try? await generator.image(at: CMTime(seconds: 0.5, preferredTimescale: 600)).image
                withExtendedLifetime(loader) {}
            } else {
                guard let (data, response) = try? await SyncTransfers.storage.data(from: link.url),
                      (response as? HTTPURLResponse).map({ (200..<300).contains($0.statusCode) }) == true else { return nil }
                let bytes = keys != nil ? ((try? FileCipher.decrypt(keys!, data)) ?? Data()) : data
                full = Self.thumbnailSource(bytes)
            }
            guard let full, let small = Self.shrink(full) else { return nil }
            let jpeg = UIImage(cgImage: small).jpegData(compressionQuality: PhotoThumbnail.quality)
            let stored = jpeg.flatMap { keys != nil ? try? FileCipher.sealWhole(keys!, $0) : $0 }
            if let stored { Self.keep(stored, at: file) }
            return (UIImage(cgImage: small), stored)
        }.value
        guard let (image, stored) = drawn else { return nil }
        images.setObject(image, forKey: key(item))
        if let stored { save(item, stored) }
        return image
    }

    /// A stored thumbnail's JPEG, drawn about `pixels` on its long side.
    private nonisolated static func image(_ bytes: Data, pixels: CGFloat) -> UIImage? {
        guard let source = CGImageSourceCreateWithData(bytes as CFData, nil),
              let cg = CGImageSourceCreateThumbnailAtIndex(source, 0, [kCGImageSourceCreateThumbnailFromImageAlways: true,
                                                                       kCGImageSourceCreateThumbnailWithTransform: true,
                                                                       kCGImageSourceThumbnailMaxPixelSize: pixels * 3] as CFDictionary) else { return nil }
        return UIImage(cgImage: cg)
    }
    /// An original, decoded upright and only as large as a thumbnail needs: its short side at least
    /// `PhotoThumbnail.pixels`, for `shrink`.
    private nonisolated static func thumbnailSource(_ bytes: Data) -> CGImage? {
        guard let source = CGImageSourceCreateWithData(bytes as CFData, nil) else { return nil }
        let properties = CGImageSourceCopyPropertiesAtIndex(source, 0, nil) as? [CFString: Any]
        let width = (properties?[kCGImagePropertyPixelWidth] as? NSNumber)?.doubleValue ?? 0
        let height = (properties?[kCGImagePropertyPixelHeight] as? NSNumber)?.doubleValue ?? 0
        let short = max(1, min(width, height)), long = max(width, height)
        let size = max(PhotoThumbnail.pixels, min(long, ceil(long * PhotoThumbnail.pixels / short)))
        return CGImageSourceCreateThumbnailAtIndex(source, 0, [kCGImageSourceCreateThumbnailFromImageAlways: true,
                                                              kCGImageSourceCreateThumbnailWithTransform: true,
                                                              kCGImageSourceThumbnailMaxPixelSize: size] as CFDictionary)
    }
    /// `image` `PhotoThumbnail.pixels` on its short side, cropped to at most 3:1, on white (JPEG has no transparency).
    private nonisolated static func shrink(_ image: CGImage) -> CGImage? {
        let crop = PhotoThumbnail.crop(width: CGFloat(image.width), height: CGFloat(image.height))
        let scale = min(1, PhotoThumbnail.pixels / max(1, min(crop.width, crop.height)))
        let size = CGSize(width: max(1, (crop.width * scale).rounded()), height: max(1, (crop.height * scale).rounded()))
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        format.opaque = true
        let drawn = UIGraphicsImageRenderer(size: size, format: format).image { context in
            UIColor.white.setFill()
            context.fill(CGRect(origin: .zero, size: size))
            guard let part = image.cropping(to: crop.integral) else { return }
            UIImage(cgImage: part).draw(in: CGRect(origin: .zero, size: size))
        }
        return drawn.cgImage
    }
    private nonisolated static func keep(_ stored: Data, at file: URL) {
        try? FileManager.default.createDirectory(at: disk, withIntermediateDirectories: true)
        try? stored.write(to: file, options: .atomic)
    }

    /// Saves a thumbnail this phone drew, with others drawn within a couple of seconds; failures only mean another
    /// app draws it again.
    private func save(_ item: PhotoItem, _ stored: Data) {
        guard let version = item.currentVersionId, stored.count <= PhotoThumbnail.maxSaved else { return }
        unsaved.append(["id": item.id, "versionId": version, "data": stored.base64EncodedString()])
        saveTask?.cancel()
        let full = unsaved.count >= PhotoThumbnail.perRequest || unsaved.reduce(0, { $0 + $1["data"]!.count }) >= PhotoThumbnail.batchBytes
        saveTask = Task {
            if !full { try? await Task.sleep(nanoseconds: 2_000_000_000); if Task.isCancelled { return } }
            await flushSaves()
        }
    }
    private func flushSaves() async {
        var all = unsaved
        unsaved = []
        saveTask = nil
        struct Saved: Decodable { let saved: [String] }
        while !all.isEmpty {
            var batch: [[String: String]] = []
            var size = 0
            while let next = all.first, batch.count < PhotoThumbnail.perRequest, batch.isEmpty || size + next["data"]!.count <= PhotoThumbnail.batchBytes {
                size += next["data"]!.count
                batch.append(all.removeFirst())
            }
            let _: Saved? = try? await api.request("/v1/photos/thumbnails", method: "POST", body: ["items": batch])
        }
    }
}

/// An encrypted video read by AVFoundation through ranges (Android `EncryptedRangeReader`): only the 4 MiB chunks a
/// request touches are downloaded and decrypted, and the first and latest few are kept, as a frame needs the video's
/// index and a few frames, not the whole file. Previews play through it too (`MediaStream`), so a video starts on its
/// first chunks; [renew] signs the link again once storage refuses it as expired.
final class EncryptedVideoLoader: NSObject, AVAssetResourceLoaderDelegate {
    static let scheme = "harbor0-encrypted"
    let queue = DispatchQueue(label: "app.harbor0.encrypted-video")
    private let chunks: EncryptedChunks
    private let mime: String
    init(url: URL, keys: AccountKeys, mime: String, renew: (@Sendable () async throws -> URL)? = nil) {
        chunks = EncryptedChunks(url: url, keys: keys, renew: renew)
        self.mime = mime
    }
    func resourceLoader(_ resourceLoader: AVAssetResourceLoader, shouldWaitForLoadingOfRequestedResource request: AVAssetResourceLoadingRequest) -> Bool {
        let mime = mime
        let chunks = chunks
        Task {
            do {
                let total = try await chunks.size()
                if let info = request.contentInformationRequest {
                    info.contentType = UTType(mimeType: mime)?.identifier ?? UTType.mpeg4Movie.identifier
                    info.contentLength = total
                    info.isByteRangeAccessSupported = true
                }
                if let wanted = request.dataRequest {
                    var at = wanted.requestedOffset
                    let end = wanted.requestsAllDataToEndOfResource ? total : min(total, at + Int64(wanted.requestedLength))
                    while at < end && !request.isCancelled {
                        // A chunk at a time, so AVFoundation can start on what it has.
                        let next = min(end, (at / Int64(E2ee.chunkSize) + 1) * Int64(E2ee.chunkSize))
                        let piece = try await chunks.read(at, next)
                        if piece.isEmpty { break }
                        wanted.respond(with: piece)
                        at += Int64(piece.count)
                    }
                }
                request.finishLoading()
            } catch { request.finishLoading(with: error) }
        }
        return true
    }
}

/// The chunks of one encrypted file, downloaded and decrypted as they are asked for.
private actor EncryptedChunks {
    private var url: URL
    private let keys: AccountKeys
    private let renew: (@Sendable () async throws -> URL)?
    private var renewing: Task<URL, Error>?
    private var opened: (cipher: FileCipher, stored: Int64)?
    private var chunks: [Int64: Task<Data, Error>] = [:]
    private var order: [Int64] = []
    init(url: URL, keys: AccountKeys, renew: (@Sendable () async throws -> URL)?) { self.url = url; self.keys = keys; self.renew = renew }

    /// Stored bytes [start, end), and the stored file's size from the answer.
    private func fetch(_ start: Int64, _ end: Int64, retry: Bool = true) async throws -> (Data, Int64?) {
        let signed = url
        var request = URLRequest(url: signed)
        request.setValue("bytes=\(start)-\(end - 1)", forHTTPHeaderField: "Range")
        let (data, response) = try await SyncTransfers.storage.data(for: request)
        // An expired link: sign it again once, shared by the reads that find it expired together.
        if let http = response as? HTTPURLResponse, [400, 403].contains(http.statusCode), retry, let renew {
            if url == signed {
                let task = renewing ?? Task { try await renew() }
                renewing = task
                defer { renewing = nil }
                url = try await task.value
            }
            return try await fetch(start, end, retry: false)
        }
        guard let http = response as? HTTPURLResponse, (200..<300).contains(http.statusCode) else {
            throw E2eeError(code: "DOWNLOAD", message: "The video couldn’t be read.")
        }
        // "bytes 0-79/12345"; storage that ignores Range sends the whole file.
        let total = (http.value(forHTTPHeaderField: "Content-Range")?.split(separator: "/").last).flatMap { Int64($0) }
        if http.statusCode == 200 { return (data.subdata(in: Int(start)..<min(data.count, Int(end))), Int64(data.count)) }
        return (data, total)
    }
    private func open() async throws -> (cipher: FileCipher, stored: Int64) {
        if let opened { return opened }
        let (header, stored) = try await fetch(0, Int64(E2ee.headerSize))
        guard let stored else { throw E2eeError(code: "DOWNLOAD", message: "The video couldn’t be read.") }
        let result = (try FileCipher.open(keys, header: header), stored)
        opened = result
        return result
    }
    /// The original file's size.
    func size() async throws -> Int64 {
        let (cipher, stored) = try await open()
        return cipher.plainSize(stored)
    }
    private func chunk(_ index: Int64) async throws -> Data {
        if let found = chunks[index] {
            order.removeAll { $0 == index }; order.append(index)
            return try await found.value
        }
        let (cipher, stored) = try await open()
        let total = cipher.plainSize(stored)
        let span = Int64(cipher.chunkSize + E2ee.tag)
        let start = Int64(E2ee.headerSize) + index * span
        let length = Int64(cipher.chunkPlainLength(total, index) + E2ee.tag)
        let last = index == cipher.chunkCount(total) - 1
        let task = Task {
            let (sealed, _) = try await self.fetch(start, start + length)
            return try cipher.decryptChunk(index, sealed, last: last)
        }
        chunks[index] = task
        order.append(index)
        // The first chunk holds most indexes; keep it and the latest three.
        while chunks.count > 4, let oldest = order.first(where: { $0 != index && $0 != 0 }) {
            chunks[oldest] = nil
            order.removeAll { $0 == oldest }
        }
        do { return try await task.value } catch {
            chunks[index] = nil
            order.removeAll { $0 == index }
            throw error
        }
    }
    /// Original bytes [begin, end), or fewer at the end of the file.
    func read(_ begin: Int64, _ end: Int64) async throws -> Data {
        let (cipher, stored) = try await open()
        let last = min(end, cipher.plainSize(stored))
        var out = Data()
        var at = begin
        while at < last {
            let index = at / Int64(cipher.chunkSize)
            let plain = try await chunk(index)
            let offset = Int(at - index * Int64(cipher.chunkSize))
            let length = min(plain.count - offset, Int(last - at))
            out.append(plain.subdata(in: (plain.startIndex + offset)..<(plain.startIndex + offset + length)))
            at += Int64(length)
        }
        return out
    }
}

private struct PhotoSection: Identifiable { let id: String; let name: String; var device: Device? = nil; var items: [PhotoItem] }

/// The Photos page.
struct PhotosScreen: View {
    enum Filter: String { case all, photo, video }
    enum Grouping: String { case date, device }
    @EnvironmentObject private var workspace: Workspace
    @EnvironmentObject private var catalog: WorkspaceCatalog
    @EnvironmentObject private var transfers: TransferCenter
    @Environment(\.tokens) private var tokens
    @AppStorage("harbor0.photos.view") private var grid = true
    @AppStorage("harbor0.photos.grouping") private var grouping = Grouping.date.rawValue
    @State private var filter = Filter.all
    @State private var items: [PhotoItem] = []
    @State private var cursor: String?
    @State private var pages = 0
    @State private var loading = true
    @State private var error: String?
    @State private var thumbnails: PhotoThumbnails?
    /// The server lists newest taken first, so pages load as the end of the page comes near.
    @State private var ordered = false
    /// Pages loaded on their own before asking, when the server lists photos in no particular order.
    private static let autoPages = 20

    private var all: [PhotoItem] {
        var seen = Set<String>()
        // Encrypted files are listed by the server unseen; their decrypted names decide here.
        return items.filter { seen.insert($0.id).inserted && Media.kind(type: $0.type, name: $0.name, mimeType: $0.mimeType) != nil }
            .sorted { $0.takenAt > $1.takenAt }
    }
    private func kind(_ item: PhotoItem) -> MediaKind? { Media.kind(type: item.type, name: item.name, mimeType: item.mimeType) }

    var body: some View {
        let everything = all
        let shown = filter == .all ? everything : everything.filter { kind($0)?.rawValue == filter.rawValue }
        let sections = grouping == Grouping.device.rawValue ? deviceSections(shown) : monthSections(shown)
        let more = cursor != nil
        PageScroll {
            VStack(alignment: .leading, spacing: 14) {
                PageTitle(text: "Photos")
                Text(loading && items.isEmpty ? "Finding photos and videos…"
                     : "\(Format.count(everything.filter { kind($0) == .photo }.count, "photo")) and \(Format.count(everything.filter { kind($0) == .video }.count, "video"))\(more ? " so far" : "")")
                    .font(TypeScale.sm).foregroundStyle(tokens.text2)
                HStack(spacing: 8) {
                    Segmented(options: [SegmentOption(value: Filter.all, label: "All"), SegmentOption(value: .photo, label: "Photos"), SegmentOption(value: .video, label: "Videos")],
                              selection: $filter, identifier: "photos-filter")
                    Spacer(minLength: 0)
                    ViewSwitch(grid: $grid)
                }
                Segmented(options: [SegmentOption(value: Grouping.date.rawValue, label: "By date"), SegmentOption(value: Grouping.device.rawValue, label: "By device")],
                          selection: $grouping, identifier: "photos-grouping")
                if loading && items.isEmpty {
                    SkeletonRows(count: 4)
                } else if let error, items.isEmpty {
                    LoadErrorView { Task { await load(reset: true) } }.accessibilityLabel(error)
                } else if shown.isEmpty && !more {
                    EmptyStateView(icon: filter == .video ? .video : .image,
                                   title: filter == .video ? "No videos yet" : filter == .photo ? "No photos yet" : "No photos or videos yet",
                                   description: "Photos and videos you upload, sync or back up from any device appear here, in every format.")
                } else {
                    ForEach(sections) { section in
                        VStack(alignment: .leading, spacing: 8) {
                            HStack(spacing: 6) {
                                if let device = section.device { Icon(device.isPhone ? .smartphone : .laptop, size: 15).foregroundStyle(tokens.text2) }
                                Text(section.name).font(TypeScale.baseMedium.weight(.semibold))
                                Text("\(section.items.count)").font(TypeScale.xs).foregroundStyle(tokens.text3)
                            }.accessibilityAddTraits(.isHeader)
                            let last = more && ordered && section.id == sections.last?.id
                            if grid { gridView(section.items, nearEnd: last) } else { listView(section.items, nearEnd: last) }
                        }
                    }
                    if more && pages >= Self.autoPages && !ordered {
                        Button("Load more") { Task { await load() } }.harborButton(.outline, block: true)
                    } else if more {
                        HStack(spacing: 6) { Spinner(size: 14); Text("Looking for more photos and videos…") }.font(TypeScale.sm).foregroundStyle(tokens.text2)
                    }
                }
            }
        }
        // Its own task: the refresh's would be cancelled as the page redraws while loading.
        .refreshable { await Task { await load(reset: true) }.value }
        .task(id: workspace.revision) {
            if thumbnails == nil { thumbnails = PhotoThumbnails(api: workspace.api) }
            await load(reset: true)
            // Out of order, newest first only means something once every page is in, so keep reading for a while.
            while !ordered && cursor != nil && pages < Self.autoPages && !Task.isCancelled && error == nil { await load() }
        }
    }

    /// In order, the last rows of the last section load the next page as they scroll into view (`nearEnd`).
    private func loadNear(_ index: Int, of count: Int, _ nearEnd: Bool) {
        if nearEnd && index >= count - 12 && !loading { Task { await load() } }
    }
    private func gridView(_ list: [PhotoItem], nearEnd: Bool) -> some View {
        LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: 3), count: 3), spacing: 3) {
            ForEach(Array(list.enumerated()), id: \.element.id) { index, item in
                PhotoTile(item: item, kind: kind(item) ?? .photo, thumbnails: thumbnails) { transfers.open(item.item) }
                    .onAppear { loadNear(index, of: list.count, nearEnd) }
            }
        }
    }
    private func listView(_ list: [PhotoItem], nearEnd: Bool) -> some View {
        LazyVStack(spacing: 0) {
            ForEach(Array(list.enumerated()), id: \.element.id) { index, item in
                FileRowView(item: item.item, detail: [Format.size(item.sizeBytes), Format.date(item.takenAt), place(item)].joined(separator: " · "),
                            showsActions: false, showsCheckbox: false, open: { transfers.open(item.item) })
                    .onAppear { loadNear(index, of: list.count, nearEnd) }
            }
        }
    }

    private func load(reset: Bool = false) async {
        if reset { cursor = nil; pages = 0 } else if (cursor == nil && pages > 0) || loading { return }
        loading = true
        defer { loading = false }
        do {
            struct PhotoPage: Decodable { let items: [PhotoItem]; let nextCursor: String?; var ordered: Bool? = nil }
            let page: PhotoPage = try await workspace.api.request(HarborAPI.pagePath("/v1/photos?limit=300", cursor: cursor))
            items = reset ? page.items : items + page.items
            cursor = page.nextCursor
            ordered = page.ordered == true
            pages += 1
            error = nil
        } catch is CancellationError {
        } catch { self.error = error.localizedDescription }
    }

    private func monthSections(_ list: [PhotoItem]) -> [PhotoSection] {
        let formatter = DateFormatter()
        formatter.setLocalizedDateFormatFromTemplate("MMMM yyyy")
        var order: [String] = []
        var groups: [String: [PhotoItem]] = [:]
        for item in list {
            let name = Format.parse(item.takenAt).map(formatter.string) ?? "Unknown date"
            if groups[name] == nil { order.append(name) }
            groups[name, default: []].append(item)
        }
        return order.map { PhotoSection(id: $0, name: $0, items: groups[$0]!) }
    }
    /// The devices a photo came from: its backup's device, every device syncing its folder, or none.
    private func sources(_ item: PhotoItem) -> [(key: String, name: String, device: Device?)] {
        let candidates = catalog.devices.filter { $0.platform != "WEB" }
        if let rootId = item.backupRootId {
            let root = catalog.allBackups.first { $0.id == rootId }
            let device = root.flatMap { root in candidates.first { d in
                if let mine = root.devicePublicId, let theirs = d.devicePublicId { return mine == theirs }
                return root.deviceId == d.id || (root.deviceName != nil && root.deviceName == d.name)
            } }
            let name = device?.name ?? root?.deviceName ?? "Other device"
            return [(device?.id ?? "other:" + name, name, device)]
        }
        if let folderId = item.syncFolderId {
            let folder = catalog.syncFolders.first { $0.id == folderId }
            let found = (folder?.syncDevices ?? []).map { ref in (key: ref.id, name: ref.name, device: candidates.first { $0.id == ref.id }) }
            return found.isEmpty ? [("other:synced", "Synced folders", nil)] : found
        }
        return [("cloud", "Uploaded to My Drive", nil)]
    }
    /// One section per device, computers before phones, then devices no longer connected, then My Drive uploads.
    private func deviceSections(_ list: [PhotoItem]) -> [PhotoSection] {
        var sections: [String: PhotoSection] = [:]
        for item in list {
            for source in sources(item) {
                sections[source.key, default: PhotoSection(id: source.key, name: source.name, device: source.device, items: [])].items.append(item)
            }
        }
        return sections.values.sorted { a, b in
            let ca = a.id == "cloud", cb = b.id == "cloud"
            if ca != cb { return cb }
            if (a.device == nil) != (b.device == nil) { return a.device != nil }
            if let da = a.device, let db = b.device, da.isPhone != db.isPhone { return !da.isPhone }
            return a.name.localizedCompare(b.name) == .orderedAscending
        }
    }
    private func place(_ item: PhotoItem) -> String {
        let found = sources(item)
        if found.first?.key == "cloud" { return "My Drive" }
        let names = found.map(\.name).joined(separator: ", ")
        return item.backupRootId != nil ? "Backup from \(names)" : "Synced on \(names)"
    }
}

private struct PhotoTile: View {
    let item: PhotoItem
    let kind: MediaKind
    let thumbnails: PhotoThumbnails?
    let open: () -> Void
    @Environment(\.tokens) private var tokens
    @State private var image: UIImage?
    var body: some View {
        Button(action: open) {
            Color.clear.aspectRatio(1, contentMode: .fit)
                .overlay {
                    if let image { Image(uiImage: image).resizable().scaledToFill() }
                    else { Icon(kind == .video ? .video : .image, size: 22).foregroundStyle(tokens.text3) }
                }
                .overlay(alignment: .bottomLeading) {
                    if kind == .video { Icon(.play, size: 12, filled: true).foregroundStyle(.white).padding(6).shadow(radius: 2) }
                }
                .background(tokens.fill2)
                .clipped()
        }
        .buttonStyle(.plain)
        .accessibilityLabel(item.name)
        .accessibilityIdentifier("photo-" + item.name)
        .task(id: item.id) {
            guard let thumbnails else { return }
            if let cached = thumbnails.cached(item) { image = cached; return }
            image = await thumbnails.thumbnail(item, side: 130)
        }
    }
}
