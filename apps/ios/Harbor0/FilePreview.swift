import SwiftUI
import QuickLook
import AVKit

/// Keep the downloaded file alive until the presentation has fully dismissed.
struct FilePreview: UIViewControllerRepresentable {
    let url: URL
    let close: () -> Void

    func makeCoordinator() -> Coordinator { Coordinator(url: url, close: close) }
    func makeUIViewController(context: Context) -> UINavigationController {
        let preview = QLPreviewController()
        preview.dataSource = context.coordinator
        let done = UIBarButtonItem(barButtonSystemItem: .done, target: context.coordinator, action: #selector(Coordinator.done))
        done.accessibilityIdentifier = "closePreview"
        preview.navigationItem.leftBarButtonItem = done
        return UINavigationController(rootViewController: preview)
    }
    func updateUIViewController(_ controller: UINavigationController, context: Context) {}

    final class Coordinator: NSObject, QLPreviewControllerDataSource {
        let url: URL
        let close: () -> Void
        init(url: URL, close: @escaping () -> Void) { self.url = url; self.close = close }
        func numberOfPreviewItems(in controller: QLPreviewController) -> Int { 1 }
        func previewController(_ controller: QLPreviewController, previewItemAt index: Int) -> QLPreviewItem { url as NSURL }
        @objc func done() { close() }
    }
}

/// A video or audio file played as it is read rather than downloaded first (Android `StreamPreview`, web
/// `media-stream.ts`): AVFoundation asks for the ranges it needs as it plays and seeks. An encrypted file is read
/// through `EncryptedVideoLoader`, which downloads and decrypts only the chunks those ranges touch.
@MainActor
final class MediaStream: Identifiable {
    let id = UUID()
    let item: DriveItem
    let player: AVPlayer
    private let loader: EncryptedVideoLoader?
    /// Formats AVFoundation plays; others still open in Quick Look once downloaded.
    private static let playable = Set("mp4 m4v mov mp3 m4a aac wav aif aiff caf".split(separator: " ").map(String.init))

    static func canStream(_ item: DriveItem) -> Bool {
        playable.contains((item.name as NSString).pathExtension.lowercased())
    }

    init(item: DriveItem, link: DownloadResponse, keys: AccountKeys?, renew: @escaping @Sendable () async throws -> URL) {
        self.item = item
        let ext = (item.name as NSString).pathExtension.lowercased()
        let given = item.mimeType ?? ""
        // Storage sends it as a download, with no extension: say what it is, or AVFoundation can't read it.
        let mime = given.hasPrefix("video/") || given.hasPrefix("audio/") ? given : guessMime(item.name)
        let asset: AVURLAsset
        if let keys {
            let loader = EncryptedVideoLoader(url: link.downloadUrl, keys: keys, mime: mime, renew: renew)
            asset = AVURLAsset(url: URL(string: "\(EncryptedVideoLoader.scheme)://preview/\(item.id).\(ext.isEmpty ? "mp4" : ext)")!)
            asset.resourceLoader.setDelegate(loader, queue: loader.queue)
            self.loader = loader
        } else {
            asset = AVURLAsset(url: link.downloadUrl, options: [AVURLAssetOverrideMIMETypeKey: mime])
            loader = nil
        }
        player = AVPlayer(playerItem: AVPlayerItem(asset: asset))
    }
}

/// The system player for a `MediaStream`, with Done, as `FilePreview`.
struct MediaPlayerView: UIViewControllerRepresentable {
    let stream: MediaStream
    let close: () -> Void

    func makeCoordinator() -> Coordinator { Coordinator(close: close) }
    func makeUIViewController(context: Context) -> UINavigationController {
        let player = AVPlayerViewController()
        player.player = stream.player
        player.title = stream.item.name
        let done = UIBarButtonItem(barButtonSystemItem: .done, target: context.coordinator, action: #selector(Coordinator.done))
        done.accessibilityIdentifier = "closePreview"
        player.navigationItem.leftBarButtonItem = done
        stream.player.play()
        return UINavigationController(rootViewController: player)
    }
    func updateUIViewController(_ controller: UINavigationController, context: Context) {}
    static func dismantleUIViewController(_ controller: UINavigationController, coordinator: Coordinator) {
        (controller.viewControllers.first as? AVPlayerViewController)?.player?.pause()
    }

    final class Coordinator: NSObject {
        let close: () -> Void
        init(close: @escaping () -> Void) { self.close = close }
        @objc func done() { close() }
    }
}
