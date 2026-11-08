import SwiftUI
import Compression

// ZIP archives in the drive as read-only folders (flag `zip-preview`), as the web's lib/zip-reader.ts,
// lib/zip-drive.ts and components/zip-folder.tsx. The web reads ranges of the archive in place; on the
// phone the archive is downloaded once (checked, and decrypted on an encrypted account) to a private
// temporary file and read from there, so the same code serves encrypted and plain archives.

struct ZipError: LocalizedError { let message: String; var errorDescription: String? { message } }

struct ZipEntryFile: Equatable {
    /// Path inside the archive, without a leading or trailing slash.
    let path: String
    let directory: Bool
    let size: Int64
    let compressedSize: Int64
    /// 0 is stored, 8 is deflate; anything else can be listed but not opened.
    let method: Int
    let encrypted: Bool
    /// Where the entry's local header starts.
    let offset: Int64
    var modified: Date? = nil
    var name: String { (path as NSString).lastPathComponent }
    /// Why an entry can't be opened on the phone, if it can't.
    var unreadable: String? {
        if encrypted { return "This file is password-protected. Download the archive to open it." }
        if method != 0 && method != 8 { return "This file uses a compression method harbor0 can’t open. Download the archive to open it." }
        return nil
    }
}

struct ZipTree {
    struct Folder { let path: String; var size: Int64 = 0; var modified: Date? = nil }
    var files: [ZipEntryFile] = []
    /// Every folder, including ones only implied by the paths under them.
    var folders: [String: Folder] = [:]
    /// Paths directly inside each folder, by the folder's path ("" is the archive root).
    var children: [String: [String]] = ["": []]
    var byPath: [String: ZipEntryFile] = [:]
}

enum ZipDrive {
    static func isZip(_ item: DriveItem) -> Bool {
        !item.isFolder && (item.name.lowercased().hasSuffix(".zip") || ["application/zip", "application/x-zip-compressed"].contains(item.mimeType ?? ""))
    }

    /// Folder-safe path: no leading slashes, no `.` or `..` segments, forward slashes only.
    static func cleanPath(_ path: String) -> String {
        path.replacingOccurrences(of: "\\", with: "/").split(separator: "/").filter { !$0.isEmpty && $0 != "." && $0 != ".." }.joined(separator: "/")
    }
    private static let cp437 = Array("ÇüéâäàåçêëèïîìÄÅÉæÆôöòûùÿÖÜ¢£¥₧ƒáíóúñÑªº¿⌐¬½¼¡«»░▒▓│┤╡╢╖╕╣║╗╝╜╛┐└┴┬├─┼╞╟╚╔╩╦╠═╬╧╨╤╥╙╘╒╓╫╪┘┌█▄▌▐▀αßΓπΣσµτΦΘΩδ∞φε∩≡±≥≤⌠⌡÷≈°∙·√ⁿ²■ ")
    // Names without the UTF-8 flag are CP437, though many tools write UTF-8 regardless.
    static func decodeName(_ bytes: Data, utf8: Bool) -> String {
        if utf8 { return String(decoding: bytes, as: UTF8.self) }
        if let text = String(data: bytes, encoding: .utf8) { return text }
        return String(bytes.map { $0 < 128 ? Character(UnicodeScalar($0)) : cp437[Int($0) - 128] })
    }
    /// MS-DOS date and time, as written by most zip tools, in local time.
    static func dosDate(_ time: Int, _ date: Int) -> Date? {
        guard date != 0 else { return nil }
        var c = DateComponents()
        c.year = 1980 + (date >> 9); c.month = (date >> 5) & 15; c.day = date & 31
        c.hour = time >> 11; c.minute = (time >> 5) & 63; c.second = (time & 31) * 2
        return Calendar.current.date(from: c)
    }

    /// Lists the entries of an archive from its central directory (PKWARE APPNOTE 6.3.10, with ZIP64).
    static func readDirectory(_ read: (Int64, Int64) throws -> Data, size: Int64) throws -> [ZipEntryFile] {
        let notZip = ZipError(message: "This file is not a ZIP archive, or it is damaged.")
        guard size >= 22 else { throw notZip }
        let tailStart = max(0, size - Int64(22 + 0xffff + 20))
        let tail = try read(tailStart, size)
        func u16(_ d: Data, _ at: Int) -> Int { Int(d[d.startIndex + at]) | Int(d[d.startIndex + at + 1]) << 8 }
        func u32(_ d: Data, _ at: Int) -> Int64 { Int64(u16(d, at)) | Int64(u16(d, at + 2)) << 16 }
        func u64(_ d: Data, _ at: Int) -> Int64 { u32(d, at) | u32(d, at + 4) << 32 }
        var end = -1
        var i = tail.count - 22
        while i >= 0 {
            if u32(tail, i) == 0x06054b50 && i + 22 + u16(tail, i + 20) <= tail.count { end = i; break }
            i -= 1
        }
        guard end >= 0 else { throw notZip }
        var count = Int64(u16(tail, end + 10)), dirSize = u32(tail, end + 12), dirOffset = u32(tail, end + 16)
        let locator = end - 20
        if locator >= 0 && u32(tail, locator) == 0x07064b50 {
            let at = u64(tail, locator + 8)
            let record = at >= tailStart ? Data(tail.suffix(from: tail.startIndex + Int(at - tailStart))) : try read(at, at + 56)
            guard record.count >= 56, u32(record, 0) == 0x06064b50 else { throw notZip }
            count = u64(record, 32); dirSize = u64(record, 40); dirOffset = u64(record, 48)
        }
        guard dirSize <= 128 * 1024 * 1024 else { throw ZipError(message: "This archive has too many files to list here. Download it to open it.") }
        guard dirOffset + dirSize <= size else { throw notZip }
        let dir = try read(dirOffset, dirOffset + dirSize)
        var files: [ZipEntryFile] = []
        var at = 0
        var n: Int64 = 0
        while n < count && at + 46 <= dir.count {
            guard u32(dir, at) == 0x02014b50 else { throw notZip }
            let flags = u16(dir, at + 8), method = u16(dir, at + 10)
            let nameLength = u16(dir, at + 28), extraLength = u16(dir, at + 30), commentLength = u16(dir, at + 32)
            let external = u32(dir, at + 38)
            var compressed = u32(dir, at + 20), length = u32(dir, at + 24), offset = u32(dir, at + 42)
            guard at + 46 + nameLength <= dir.count else { throw notZip }
            let raw = decodeName(dir.subdata(in: dir.startIndex + at + 46..<dir.startIndex + at + 46 + nameLength), utf8: flags & 0x800 != 0)
            // Sizes and offsets too large for 32 bits are in the ZIP64 extra field, in this order.
            var extra = at + 46 + nameLength
            let extraEnd = extra + extraLength
            while extra + 4 <= extraEnd && extra + 4 <= dir.count {
                let id = u16(dir, extra), fieldLength = u16(dir, extra + 2)
                if id == 1 {
                    var field = extra + 4
                    if length == 0xffffffff { length = u64(dir, field); field += 8 }
                    if compressed == 0xffffffff { compressed = u64(dir, field); field += 8 }
                    if offset == 0xffffffff { offset = u64(dir, field) }
                }
                extra += 4 + fieldLength
            }
            // Directories end in a slash; some Windows tools mark them only with the attribute.
            let directory = raw.hasSuffix("/") || raw.hasSuffix("\\") || (external & 0x10 != 0 && length == 0)
            let path = cleanPath(raw)
            if !path.isEmpty {
                files.append(ZipEntryFile(path: path, directory: directory, size: directory ? 0 : length, compressedSize: compressed,
                                          method: method, encrypted: flags & 1 != 0, offset: offset,
                                          modified: dosDate(u16(dir, at + 12), u16(dir, at + 14))))
            }
            at = extraEnd + commentLength
            n += 1
        }
        return files
    }

    /// Arranges a listing into folders, as the archive would unpack.
    static func tree(_ entries: [ZipEntryFile]) -> ZipTree {
        var tree = ZipTree()
        func parent(_ path: String) -> String { (path as NSString).deletingLastPathComponent }
        func addFolder(_ path: String, _ modified: Date? = nil) {
            if tree.folders[path] == nil {
                tree.folders[path] = ZipTree.Folder(path: path, modified: modified)
                tree.children[path] = []
                if path.contains("/") { addFolder(parent(path)) }
                // A file and a folder of the same name can't both unpack; the file is listed.
                if tree.byPath[path] == nil { tree.children[parent(path), default: []].append(path) }
            } else if let modified, tree.folders[path]?.modified == nil { tree.folders[path]?.modified = modified }
        }
        for entry in entries {
            // macOS adds resource forks under __MACOSX; Archive Utility hides them too.
            if entry.path == "__MACOSX" || entry.path.hasPrefix("__MACOSX/") { continue }
            if entry.directory { addFolder(entry.path, entry.modified); continue }
            // A later entry with the same path replaces the earlier one when unpacked.
            if let earlier = tree.byPath[entry.path], let index = tree.files.firstIndex(of: earlier) {
                tree.files[index] = entry; tree.byPath[entry.path] = entry; continue
            }
            if tree.folders[entry.path] != nil { continue }
            tree.byPath[entry.path] = entry
            tree.files.append(entry)
            let up = parent(entry.path)
            if !up.isEmpty { addFolder(up) }
            tree.children[up, default: []].append(entry.path)
        }
        for file in tree.files {
            var p = parent(file.path)
            while !p.isEmpty { tree.folders[p]?.size += file.size; p = parent(p) }
        }
        return tree
    }

    /// Unpacks one entry from the archive file into `target`, checking its local header.
    static func extract(_ archive: URL, _ file: ZipEntryFile, to target: URL) throws {
        if let problem = file.unreadable { throw ZipError(message: problem) }
        let damaged = ZipError(message: "This archive is damaged. Download it to open it.")
        let handle = try FileHandle(forReadingFrom: archive)
        defer { try? handle.close() }
        try handle.seek(toOffset: UInt64(file.offset))
        let header = try handle.read(upToCount: 30) ?? Data()
        guard header.count == 30, header.prefix(4) == Data([0x50, 0x4b, 0x03, 0x04]) else { throw damaged }
        let skip = Int(header[26]) | Int(header[27]) << 8, extra = Int(header[28]) | Int(header[29]) << 8
        try handle.seek(toOffset: UInt64(file.offset) + 30 + UInt64(skip + extra))
        FileManager.default.createFile(atPath: target.path, contents: nil)
        let output = try FileHandle(forWritingTo: target)
        defer { try? output.close() }
        var remaining = file.compressedSize
        if file.method == 0 {
            while remaining > 0 {
                let chunk = try handle.read(upToCount: Int(min(remaining, 1 << 20))) ?? Data()
                guard !chunk.isEmpty else { throw damaged }
                try output.write(contentsOf: chunk); remaining -= Int64(chunk.count)
            }
            return
        }
        // Raw DEFLATE (Apple's COMPRESSION_ZLIB is RFC 1951 without a header), streamed.
        let filter = try InputFilter(.decompress, using: .zlib) { (length: Int) -> Data? in
            guard remaining > 0 else { return nil }
            let chunk = ((try? handle.read(upToCount: min(length, Int(remaining)))) ?? nil) ?? Data()
            remaining -= Int64(chunk.count)
            return chunk.isEmpty ? nil : chunk
        }
        var written: Int64 = 0
        do {
            while let data = try filter.readData(ofLength: 1 << 20), !data.isEmpty {
                try Task.checkCancellation()
                try output.write(contentsOf: data); written += Int64(data.count)
            }
        } catch is CancellationError { throw CancellationError() }
        catch { throw ZipError(message: "This file in the archive is damaged and can’t be opened.") }
        guard written == file.size else { throw ZipError(message: "This file in the archive is damaged and can’t be opened.") }
    }
}

/// An opened archive: the downloaded file and its listing.
final class OpenArchive {
    let file: URL
    let tree: ZipTree
    init(file: URL) throws {
        self.file = file
        let handle = try FileHandle(forReadingFrom: file)
        defer { try? handle.close() }
        let size = Int64(try handle.seekToEnd())
        tree = ZipDrive.tree(try ZipDrive.readDirectory({ begin, end in
            try handle.seek(toOffset: UInt64(begin))
            return try handle.read(upToCount: Int(end - begin)) ?? Data()
        }, size: size))
    }
    deinit { FileTransfers.removePreview(file) }
}

/// A ZIP file in the drive opened as a read-only folder: breadcrumbs, folders first, files open in Quick Look.
struct ZipFolderSheet: View {
    let item: DriveItem
    @EnvironmentObject private var workspace: Workspace
    @EnvironmentObject private var transfers: TransferCenter
    @Environment(\.tokens) private var tokens
    @Environment(\.dismiss) private var dismiss
    @State private var archive: OpenArchive?
    @State private var error: String?
    @State private var path = ""
    @State private var opening: String?
    @State private var preview: URL?
    @State private var sharing: URL?

    var body: some View {
        VStack(spacing: 0) {
            SheetScaffold(title: item.name, description: "Read-only · files in this archive open here without unpacking it.", divider: true) { EmptyView() }
            ScrollView {
                VStack(alignment: .leading, spacing: 10) {
                    if let error { AlertBanner(tone: .danger, text: error, dismiss: { self.error = nil }) }
                    if let archive {
                        crumbs
                        let entries = (archive.tree.children[path] ?? []).sorted { a, b in
                            let fa = archive.tree.byPath[a] == nil, fb = archive.tree.byPath[b] == nil
                            if fa != fb { return fa }
                            return (a as NSString).lastPathComponent.localizedStandardCompare((b as NSString).lastPathComponent) == .orderedAscending
                        }
                        if entries.isEmpty {
                            EmptyStateView(icon: .fileArchive, title: "This folder is empty", description: "There are no files here in the archive.", compact: true)
                        }
                        VStack(spacing: 0) {
                            ForEach(entries, id: \.self) { child in row(archive, child) }
                        }
                    } else if error == nil {
                        HStack(spacing: 8) { Spinner(size: 16); Text("Opening \(item.name)…") }.font(TypeScale.sm).foregroundStyle(tokens.text2)
                        SkeletonRows(count: 4)
                    }
                }.padding(.horizontal, 20).padding(.vertical, 12)
            }
        }
        .cardSurface(paint: false)
        .modifier(TallSheet(large: true))
        .task { await open() }
        .sheet(item: Binding(get: { preview.map(PreviewURL.init) }, set: { if $0 == nil { if let preview { FileTransfers.removePreview(preview) }; preview = nil } })) { item in
            FilePreview(url: item.url) { preview = nil }.ignoresSafeArea()
        }
        .sheet(item: Binding(get: { sharing.map(PreviewURL.init) }, set: { if $0 == nil { if let sharing { FileTransfers.removePreview(sharing) }; sharing = nil } })) { item in
            ShareSheet(urls: [item.url]) { sharing = nil }.presentationDetents([.medium, .large])
        }
    }

    private var crumbs: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 4) {
                Button(item.name) { path = "" }.harborButton(.link).font(TypeScale.sm).lineLimit(1)
                let parts = path.split(separator: "/").map(String.init)
                ForEach(Array(parts.enumerated()), id: \.offset) { index, part in
                    Icon(.chevronRight, size: 13).foregroundStyle(tokens.text3)
                    Button(part) { path = parts.prefix(index + 1).joined(separator: "/") }.harborButton(.link).font(TypeScale.sm).lineLimit(1)
                }
            }
        }
    }

    private func row(_ archive: OpenArchive, _ child: String) -> some View {
        let file = archive.tree.byPath[child]
        let name = (child as NSString).lastPathComponent
        let folder = archive.tree.folders[child]
        let modified = file?.modified ?? folder?.modified
        let kind: FileKind = file == nil ? .folder : DriveItem(id: child, parentId: nil, type: "FILE", name: name, mimeType: guessMime(name),
                                                                 sizeBytes: 0, updatedAt: "", backupRootId: nil, cloudState: nil).kind
        return HStack(spacing: 12) {
            FileTile(kind: kind)
            VStack(alignment: .leading, spacing: 2) {
                Text(name).font(TypeScale.name).lineLimit(1).truncationMode(.middle)
                Text([Format.size(file?.size ?? folder?.size ?? 0), modified.map { Format.short($0) }].compactMap { $0 }.joined(separator: " · "))
                    .font(TypeScale.xs).foregroundStyle(tokens.text2)
            }
            Spacer(minLength: 0)
            if opening == child { Spinner(size: 16) }
            else if let file {
                Button { Task { await extract(archive, file, share: true) } } label: { Icon(.download, size: 17).frame(width: 40, height: 40) }
                    .buttonStyle(.plain).foregroundStyle(tokens.text2).accessibilityLabel("Save \(name)")
            } else { Icon(.chevronRight, size: 16).foregroundStyle(tokens.text3) }
        }
        .frame(minHeight: 60)
        .contentShape(Rectangle())
        .onTapGesture {
            if let file { Task { await extract(archive, file, share: false) } } else { path = child }
        }
        .overlay(alignment: .bottom) { Hairline() }
        .accessibilityElement(children: .combine)
        .accessibilityIdentifier("zip-" + name)
    }

    private func open() async {
        do {
            let file = try await transfers.fetchFile(item)
            archive = try await Task.detached(priority: .userInitiated) { try OpenArchive(file: file) }.value
        } catch is CancellationError {
        } catch { self.error = error.localizedDescription }
    }
    private func extract(_ archive: OpenArchive, _ file: ZipEntryFile, share: Bool) async {
        guard opening == nil else { return }
        opening = file.path
        defer { opening = nil }
        do {
            let directory = FileManager.default.temporaryDirectory.appendingPathComponent("HarborTransfers", isDirectory: true)
                .appendingPathComponent(UUID().uuidString, isDirectory: true)
            try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
            let target = directory.appendingPathComponent(try FileTransfers.safeName(file.name))
            let source = archive.file
            try await Task.detached(priority: .userInitiated) { try ZipDrive.extract(source, file, to: target) }.value
            if share { sharing = target } else { preview = target }
        } catch { self.error = error.localizedDescription }
    }
}
