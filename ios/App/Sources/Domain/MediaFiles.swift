import Foundation
import ImageIO
import UniformTypeIdentifiers

/// What a file message holds, from its name (and content type): the glyph
/// its tile shows and where a tap opens it (5.2 › Transitions: file → Quick
/// Look, video → player).
enum MediaFileKind: Equatable, Sendable {
    case pdf
    case image
    case movie
    case audio
    case other

    init(fileName: String, contentType: String? = nil) {
        let byName = UTType(filenameExtension: (fileName as NSString).pathExtension)
        let byMime = contentType.flatMap { UTType(mimeType: $0) }
        let types = [byName, byMime].compactMap(\.self)
        if types.contains(where: { $0.conforms(to: .pdf) }) {
            self = .pdf
        } else if types.contains(where: { $0.conforms(to: .movie) }) {
            self = .movie
        } else if types.contains(where: { $0.conforms(to: .audio) }) {
            self = .audio
        } else if types.contains(where: { $0.conforms(to: .image) }) {
            self = .image
        } else {
            self = .other
        }
    }

    /// An image file the app's own photo viewer shows (a format ImageIO
    /// reads) instead of Quick Look: Quick Look's long press would hand the
    /// picture to other apps (subject lifting, Live Text) without telling
    /// the sender, also for a disappearing message.
    static func opensInPhotoViewer(fileName: String) -> Bool {
        guard let type = UTType(filenameExtension: (fileName as NSString).pathExtension), type.conforms(to: .image)
        else { return false }
        let readable = CGImageSourceCopyTypeIdentifiers() as? [String] ?? []
        return readable.contains(type.identifier)
    }
}

/// A file name from the other side made safe to put on disk and to show in
/// Quick Look and the Files app: the sender chooses it, so it may try to
/// leave the folder (`../`), hide its real extension behind a right-to-left
/// override (`fatura‮fdp.exe` shows as “fatura exe.pdf”) or be empty.
enum FileNameSanitizer {
    /// The longest name kept (the extension always survives).
    static let maxLength = 120

    static func sanitized(_ name: String?, contentType: String? = nil) -> String {
        // Only the last path component, whichever separator the sender used.
        var candidate = (name ?? "").split(whereSeparator: { $0 == "/" || $0 == "\\" }).last.map(String.init) ?? ""
        // No control, format (bidi overrides and isolates, zero-width marks)
        // or line/paragraph separators; no ':' (a path separator for Files).
        let scalars = candidate.unicodeScalars.filter { scalar in
            switch scalar.properties.generalCategory {
            case .control, .format, .lineSeparator, .paragraphSeparator, .surrogate, .privateUse, .unassigned:
                return false
            default:
                return scalar != ":"
            }
        }
        candidate = String(String.UnicodeScalarView(scalars))
        // No leading dots (hidden files, “..”) or surrounding blanks.
        while let first = candidate.first, first == "." || first.isWhitespace { candidate.removeFirst() }
        while let last = candidate.last, last.isWhitespace { candidate.removeLast() }

        var base = (candidate as NSString).deletingPathExtension
        var ext = (candidate as NSString).pathExtension
        if ext.isEmpty, let preferred = contentType.flatMap({ UTType(mimeType: $0)?.preferredFilenameExtension }) {
            ext = preferred
        }
        if base.isEmpty { base = "file" }
        ext = String(ext.prefix(16))
        let room = max(1, maxLength - (ext.isEmpty ? 0 : ext.count + 1))
        if base.count > room { base = String(base.prefix(room)) }
        return ext.isEmpty ? base : "\(base).\(ext)"
    }
}

/// The one place a decrypted file exists on disk outside the runtime's own
/// staging: Quick Look and the document exporter take only file URLs, so a
/// file message is previewed and saved from a named copy here.
///
/// `tmp/preview/<random>/<sanitised name>` — the folders and the file with
/// complete file protection (unreadable while the phone is locked), the root
/// excluded from backup. The copy is deleted when its preview or export
/// closes (`discard`), and everything here when the app goes to the
/// background, when the conversation closes, on “Her şeyi sil” and at every
/// launch (`removeAll`). Photos and videos never come here: they are shown
/// and played from memory.
enum ProtectedPreviewFiles {
    static var root: URL {
        FileManager.default.temporaryDirectory.appendingPathComponent("preview", isDirectory: true)
    }

    /// Writes `payload` (off the main thread); `nil` if it could not be written.
    static func write(_ payload: MediaPayload) async -> URL? {
        await Task.detached(priority: .userInitiated) {
            writeNow(payload.data, name: FileNameSanitizer.sanitized(payload.fileName, contentType: payload.contentType))
        }.value
    }

    /// Writes `data` as `name` (already sanitised) into a fresh folder.
    static func writeNow(_ data: Data, name: String) -> URL? {
        let manager = FileManager.default
        let folder = root.appendingPathComponent(UUID().uuidString, isDirectory: true)
        do {
            try manager.createDirectory(
                at: root, withIntermediateDirectories: true, attributes: [.protectionKey: FileProtectionType.complete])
            var values = URLResourceValues()
            values.isExcludedFromBackup = true
            var rootURL = root
            try? rootURL.setResourceValues(values)
            try manager.createDirectory(
                at: folder, withIntermediateDirectories: false, attributes: [.protectionKey: FileProtectionType.complete])
            let url = folder.appendingPathComponent(name, isDirectory: false)
            try data.write(to: url, options: [.atomic, .completeFileProtection])
            return url
        } catch {
            try? manager.removeItem(at: folder)
            return nil
        }
    }

    /// Deletes one copy and its folder (only inside the preview folder).
    static func discard(_ url: URL) {
        let folder = url.deletingLastPathComponent().standardizedFileURL
        guard folder.deletingLastPathComponent().path == root.standardizedFileURL.path else { return }
        try? FileManager.default.removeItem(at: folder)
    }

    /// Deletes every copy.
    static func removeAll() {
        try? FileManager.default.removeItem(at: root)
    }

    /// The copies that exist right now (tests).
    static var existingFiles: [URL] {
        let manager = FileManager.default
        let folders = (try? manager.contentsOfDirectory(at: root, includingPropertiesForKeys: nil)) ?? []
        return folders.flatMap { (try? manager.contentsOfDirectory(at: $0, includingPropertiesForKeys: nil)) ?? [] }
    }
}

/// Where a voice message is recorded: the recorder (`AVAudioRecorder`)
/// writes only to a file, so it gets one here, built like the preview
/// copies above — `tmp/voice/<random>/voice.m4a`, the folders with
/// complete file protection (unreadable while the phone is locked), the
/// root excluded from backup.
///
/// The recording lives on disk only while it is being recorded: when it
/// stops, its bytes are read into memory and the file goes with its folder
/// at once (`consume`); a cancelled recording is deleted (`discard`), and
/// everything here when the app goes to the background, on “Her şeyi sil”
/// and at every launch (`removeAll`). Received voice messages never come
/// here: they are played from memory.
enum VoiceRecordingFiles {
    static var root: URL {
        FileManager.default.temporaryDirectory.appendingPathComponent("voice", isDirectory: true)
    }

    /// A file URL in a fresh protected folder (nothing written yet); `nil`
    /// if the folder could not be made.
    static func makeURL() -> URL? {
        let manager = FileManager.default
        let folder = root.appendingPathComponent(UUID().uuidString, isDirectory: true)
        do {
            try manager.createDirectory(
                at: root, withIntermediateDirectories: true, attributes: [.protectionKey: FileProtectionType.complete])
            var values = URLResourceValues()
            values.isExcludedFromBackup = true
            var rootURL = root
            try? rootURL.setResourceValues(values)
            try manager.createDirectory(
                at: folder, withIntermediateDirectories: false, attributes: [.protectionKey: FileProtectionType.complete])
            return folder.appendingPathComponent("voice.m4a", isDirectory: false)
        } catch {
            try? manager.removeItem(at: folder)
            return nil
        }
    }

    /// Reads a finished recording into memory and deletes it, with its
    /// folder, right away. `nil` when there is nothing (or nothing readable).
    static func consume(_ url: URL) -> Data? {
        defer { discard(url) }
        guard let data = try? Data(contentsOf: url, options: .uncached), !data.isEmpty else { return nil }
        return data
    }

    /// Deletes one recording and its folder (only inside the voice folder).
    static func discard(_ url: URL) {
        let folder = url.deletingLastPathComponent().standardizedFileURL
        guard folder.deletingLastPathComponent().path == root.standardizedFileURL.path else { return }
        try? FileManager.default.removeItem(at: folder)
    }

    /// Deletes every recording left behind (a crash or a kill mid-recording).
    static func removeAll() {
        try? FileManager.default.removeItem(at: root)
    }

    /// The recordings on disk right now (tests).
    static var existingFiles: [URL] {
        let manager = FileManager.default
        let folders = (try? manager.contentsOfDirectory(at: root, includingPropertiesForKeys: nil)) ?? []
        return folders.flatMap { (try? manager.contentsOfDirectory(at: $0, includingPropertiesForKeys: nil)) ?? [] }
    }
}
