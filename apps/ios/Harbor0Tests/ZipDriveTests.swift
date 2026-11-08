import XCTest
import Compression
@testable import Harbor0

/// The ZIP reader against archives built here: stored and deflated entries, folders, __MACOSX and CP437 names.
final class ZipDriveTests: XCTestCase {
    private func le(_ v: Int, _ n: Int) -> Data { Data((0..<n).map { UInt8((v >> (8 * $0)) & 255) }) }
    private func deflate(_ data: Data) -> Data {
        var out = Data()
        let filter = try! OutputFilter(.compress, using: .zlib) { chunk in if let chunk { out.append(chunk) } }
        try! filter.write(data); try! filter.finalize()
        return out
    }
    /// A minimal archive: entries of (name, contents, deflate?, utf8 flag).
    private func archive(_ entries: [(Data, Data, Bool, Bool)]) -> Data {
        var body = Data(), central = Data()
        for (name, contents, deflated, utf8) in entries {
            let stored = deflated ? deflate(contents) : contents
            let offset = body.count
            let flags = utf8 ? 0x800 : 0, method = deflated ? 8 : 0
            let local: [Data] = [le(0x04034b50, 4), le(20, 2), le(flags, 2), le(method, 2), le(0, 2), le(0x5821, 2), le(0, 4),
                                 le(stored.count, 4), le(contents.count, 4), le(name.count, 2), le(0, 2), name, stored]
            let record: [Data] = [le(0x02014b50, 4), le(20, 2), le(20, 2), le(flags, 2), le(method, 2), le(0, 2), le(0x5821, 2), le(0, 4),
                                  le(stored.count, 4), le(contents.count, 4), le(name.count, 2), le(0, 2), le(0, 2), le(0, 2), le(0, 2), le(0, 4),
                                  le(offset, 4), name]
            for part in local { body.append(part) }
            for part in record { central.append(part) }
        }
        var out = body
        out.append(central)
        let end: [Data] = [le(0x06054b50, 4), le(0, 4), le(entries.count, 2), le(entries.count, 2), le(central.count, 4), le(body.count, 4), le(0, 2)]
        for part in end { out.append(part) }
        return out
    }

    func testListsFoldersAndExtractsStoredAndDeflatedFiles() throws {
        let text = Data(String(repeating: "harbor0 zip preview ", count: 500).utf8)
        let data = archive([
            (Data("docs/".utf8), Data(), false, true),
            (Data("docs/readme.txt".utf8), text, true, true),
            (Data("photos/a/b.jpg".utf8), Data([1, 2, 3, 4]), false, true),
            (Data("__MACOSX/._b.jpg".utf8), Data([9]), false, true),
            (Data([0x63, 0x61, 0x66, 0x82, 0x2e, 0x74, 0x78, 0x74]), Data("x".utf8), false, false),
            (Data("../evil.txt".utf8), Data("e".utf8), false, true),
        ])
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: directory) }
        let file = directory.appendingPathComponent("a.zip")
        try data.write(to: file)
        let entries = try ZipDrive.readDirectory({ data.subdata(in: Int($0)..<Int($1)) }, size: Int64(data.count))
        let tree = ZipDrive.tree(entries)
        XCTAssertEqual(Set(tree.children[""]!), ["docs", "photos", "café.txt", "evil.txt"])
        XCTAssertEqual(tree.children["photos/a"], ["photos/a/b.jpg"])
        XCTAssertEqual(tree.folders["photos"]?.size, 4)
        XCTAssertNil(tree.byPath["__MACOSX/._b.jpg"])
        let readme = tree.byPath["docs/readme.txt"]!
        XCTAssertEqual(readme.method, 8)
        let out = directory.appendingPathComponent("readme.txt")
        try ZipDrive.extract(file, readme, to: out)
        XCTAssertEqual(try Data(contentsOf: out), text)
        let photo = directory.appendingPathComponent("b.jpg")
        try ZipDrive.extract(file, tree.byPath["photos/a/b.jpg"]!, to: photo)
        XCTAssertEqual(try Data(contentsOf: photo), Data([1, 2, 3, 4]))
        XCTAssertTrue(ZipDrive.isZip(DriveItem(id: "z", parentId: nil, type: "FILE", name: "Photos.ZIP", mimeType: nil, sizeBytes: 1, updatedAt: "", backupRootId: nil, cloudState: nil)))
        XCTAssertThrowsError(try ZipDrive.readDirectory({ _, _ in Data(count: 30) }, size: 30))
    }
}

/// Photo backup paths: one folder per album, safe and stable names, numbered when they clash.
final class PhotoPathsTests: XCTestCase {
    func testAlbumPathsAreSafeAndNumberedStably() {
        let entries = PhotoPaths.layout([
            ("B/1", "Camera", "IMG_1.HEIC", 2, false), ("A/1", "Camera", "IMG_1.HEIC", 1, false),
            ("C/1", "Trips/2026", "beach:sun.jpg", 3, false), ("D/1", "Camera", "img_1.heic", 4, true), ("E/1", " ", "", 5, false)])
        let paths = Dictionary(uniqueKeysWithValues: entries.map { ($0.asset, $0.path) })
        XCTAssertEqual(paths["A/1"], "Camera/IMG_1.HEIC")
        XCTAssertEqual(paths["B/1"], "Camera/IMG_1 (2).HEIC")
        XCTAssertEqual(paths["D/1"], "Camera/img_1 (3).heic")
        XCTAssertEqual(paths["C/1"], "Trips_2026/beach_sun.jpg")
        XCTAssertEqual(paths["E/1"], "Untitled/Untitled")
        // The same library gives the same paths on the next scan.
        XCTAssertEqual(PhotoPaths.layout([("A/1", "Camera", "IMG_1.HEIC", 1, false), ("B/1", "Camera", "IMG_1.HEIC", 2, false)]).map(\.path),
                       ["Camera/IMG_1.HEIC", "Camera/IMG_1 (2).HEIC"])
    }
    func testReusesThisPhonesNewestRunningPhotoBackup() {
        func root(_ id: String, _ name: String = "Photos", device: String = "phone", state: String = "ACTIVE", created: String) -> BackupRoot {
            BackupRoot(id: id, deviceName: nil, deviceId: device, createdAt: created, localPathDisplayName: name, remoteRootDriveItemId: "r-" + id, state: state, updatedAt: created)
        }
        let roots = [root("old", created: "2026-01-01T00:00:00Z"), root("new", state: "PAUSED", created: "2026-03-01T00:00:00Z"),
                     root("gone", state: "REMOVED", created: "2026-04-01T00:00:00Z"), root("other", device: "laptop", created: "2026-05-01T00:00:00Z"),
                     root("docs", "Documents", created: "2026-06-01T00:00:00Z")]
        XCTAssertEqual(PhotoPaths.reusable(roots, deviceId: "phone")?.id, "new")
        XCTAssertNil(PhotoPaths.reusable(roots, deviceId: "tablet"))
    }

    func testSeedMatchesSavedFilesByPathSizeAndTime() {
        func file(_ id: String, _ name: String, _ size: Int64, _ modified: String) -> DriveItem {
            DriveItem(id: id, parentId: "a", type: "FILE", name: name, mimeType: nil, sizeBytes: size, updatedAt: modified, backupRootId: nil, cloudState: nil, revision: 3, modifiedAt: modified)
        }
        let mtime = (Format.parse("2026-02-03T04:05:06.789Z")!.timeIntervalSince1970 * 1000).rounded()
        let entries = [PhotoEntry(asset: "1", path: "Camera/IMG_1.HEIC", album: "Camera", mtime: mtime, video: false),
                       PhotoEntry(asset: "2", path: "Camera/IMG_2.HEIC", album: "Camera", mtime: mtime, video: false),
                       PhotoEntry(asset: "3", path: "Camera/IMG_3.HEIC", album: "Camera", mtime: mtime, video: false),
                       PhotoEntry(asset: "4", path: "Trips/beach.jpg", album: "Trips", mtime: mtime, video: false)]
        let remote: [String: (id: String, items: [DriveItem])] = ["Camera": ("cam", [
            file("i1", "IMG_1.HEIC", 100, "2026-02-03T04:05:06.789Z"), // matches
            file("i2", "IMG_2.HEIC", 999, "2026-02-03T04:05:06.789Z"), // other size
            file("i3", "IMG_3.HEIC", 100, "2026-02-03T04:05:07.000Z"), // other time
        ])]
        let seeded = PhotoPaths.seed(entries, sizes: ["1": 100, "2": 100, "3": 100, "4": 100], remote: remote)
        XCTAssertEqual(Array(seeded.saved.keys), ["Camera/IMG_1.HEIC"])
        XCTAssertEqual(seeded.saved["Camera/IMG_1.HEIC"], .init(asset: "1", mtime: mtime, itemId: "i1", revision: 3))
        XCTAssertEqual(seeded.folders, ["Camera": "cam"])
    }

    func testOnlyPhotosAndVideosAreRestoredToTheLibrary() {
        XCTAssertEqual(PhotoPaths.restorable("IMG_1.HEIC"), false)
        XCTAssertEqual(PhotoPaths.restorable("clip.MOV"), true)
        XCTAssertEqual(PhotoPaths.restorable("video.mp4"), true)
        XCTAssertNil(PhotoPaths.restorable("notes.pdf"))
        XCTAssertNil(PhotoPaths.restorable("README"))
    }
}
