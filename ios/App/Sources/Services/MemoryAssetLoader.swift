import AVFoundation
import Foundation
import UniformTypeIdentifiers

/// Plays decrypted video from memory: AVFoundation asks for byte ranges of a
/// private `u2u-memory:` URL and gets slices of `data`. No plaintext file is
/// written for playback (the rule for every decrypted attachment: memory
/// only, unless a system component cannot work without a file).
///
/// Immutable and `Sendable`: AVFoundation calls it on its own serial queue.
final class MemoryAssetLoader: NSObject, AVAssetResourceLoaderDelegate, Sendable {
    static let scheme = "u2u-memory"
    /// The largest slice answered at once (a request to the end of a 64 MiB
    /// clip is served in pieces, not one copy).
    static let chunkBytes = 1 << 20

    let data: Data
    /// The uniform type of the clip (`public.mpeg-4`, `com.apple.quicktime-movie`).
    let uniformType: String
    let url: URL
    private let queue = DispatchQueue(label: "us2us.memory-asset", qos: .userInitiated)

    init(data: Data, contentType: String) {
        // Offsets below index the clip from zero.
        self.data = data.startIndex == 0 ? data : Data(data)
        let type = UTType(mimeType: contentType).flatMap { $0.conforms(to: .movie) ? $0 : nil } ?? .mpeg4Movie
        uniformType = type.identifier
        let ext = type.preferredFilenameExtension ?? "mp4"
        url = URL(string: "\(Self.scheme):///\(UUID().uuidString).\(ext)") ?? URL(fileURLWithPath: "/")
        super.init()
    }

    /// An asset whose bytes come from this loader (keep the loader alive as
    /// long as the asset: the resource loader holds its delegate weakly).
    func makeAsset() -> AVURLAsset {
        let asset = AVURLAsset(url: url)
        asset.resourceLoader.setDelegate(self, queue: queue)
        return asset
    }

    /// The bytes to answer for a request: from `offset`, `length` of them
    /// (or everything up to the end), clamped to the clip; `nil` when the
    /// request starts outside it.
    static func byteRange(offset: Int64, length: Int, toEnd: Bool, total: Int) -> Range<Int>? {
        guard offset >= 0, offset < Int64(total) else { return nil }
        let start = Int(offset)
        let end = toEnd ? total : min(total, start + max(0, length))
        guard end > start else { return nil }
        return start..<end
    }

    // MARK: AVAssetResourceLoaderDelegate

    func resourceLoader(
        _ resourceLoader: AVAssetResourceLoader, shouldWaitForLoadingOfRequestedResource loadingRequest: AVAssetResourceLoadingRequest
    ) -> Bool {
        guard loadingRequest.request.url?.scheme == Self.scheme else { return false }
        if let information = loadingRequest.contentInformationRequest {
            information.contentType = uniformType
            information.contentLength = Int64(data.count)
            information.isByteRangeAccessSupported = true
        }
        if let request = loadingRequest.dataRequest {
            guard
                let range = Self.byteRange(
                    offset: request.requestedOffset, length: request.requestedLength,
                    toEnd: request.requestsAllDataToEndOfResource, total: data.count)
            else {
                loadingRequest.finishLoading(with: NSError(domain: NSURLErrorDomain, code: NSURLErrorResourceUnavailable))
                return true
            }
            var start = range.lowerBound
            while start < range.upperBound {
                if loadingRequest.isCancelled { return true }
                let end = min(range.upperBound, start + Self.chunkBytes)
                request.respond(with: data.subdata(in: start..<end))
                start = end
            }
        }
        loadingRequest.finishLoading()
        return true
    }
}
