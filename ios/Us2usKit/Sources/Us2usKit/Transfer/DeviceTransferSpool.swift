import CryptoKit
import Foundation

/// What a new phone received of the history, kept until a vault can take it: the
/// new secret code is chosen only after the transfer (PROTOCOL.md §5.6). Encrypted
/// under a key that lives in memory only, so a spool outlives neither the process
/// nor a crash in a form anyone can read. Its files go as they are read, so the
/// phone never holds the history twice over.
public final class DeviceTransferSpool: DeviceTransferHistorySink, @unchecked Sendable {
    /// One file's size, before the next one starts.
    static let segmentBytes: UInt64 = 32 * 1024 * 1024

    private let directory: URL
    private let attributes: [FileAttributeKey: Any]?
    private let key: Data
    private let lock = NSLock()
    private var sealing: DeviceTransferCipher
    private var handle: FileHandle?
    private var segment = 0
    private var segmentWritten: UInt64 = 0
    private var recordBytes: UInt64 = 0

    /// - Parameter directory: created here; its files are this spool's alone.
    public init(directory: URL, attributes: [FileAttributeKey: Any]? = nil) throws {
        self.directory = directory
        self.attributes = attributes
        key = SecureRandom.bytes(32)
        sealing = DeviceTransferCipher(key: key)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true, attributes: attributes)
    }

    public func canHold(bytes: UInt64) -> Bool {
        let (needed, overflow) = bytes.addingReportingOverflow(DeviceTransferLimits.spareBytes)
        guard !overflow else { return false }
        guard
            let values = try? directory.resourceValues(forKeys: [.volumeAvailableCapacityForImportantUsageKey]),
            let free = values.volumeAvailableCapacityForImportantUsage
        else { return true }
        return free >= 0 && UInt64(free) >= needed
    }

    public func append(_ record: Data) throws {
        try lock.withLock {
            let frame = try sealing.seal(.record, record)
            if handle == nil || segmentWritten >= Self.segmentBytes {
                try handle?.close()
                segment += 1
                segmentWritten = 0
                let url = file(segment)
                guard FileManager.default.createFile(atPath: url.path, contents: nil, attributes: attributes) else {
                    throw CocoaError(.fileWriteUnknown)
                }
                handle = try FileHandle(forWritingTo: url)
            }
            try handle?.write(contentsOf: frame)
            segmentWritten += UInt64(frame.count)
            recordBytes += UInt64(record.count)
        }
    }

    public func finish() throws {
        try lock.withLock {
            try handle?.synchronize()
            try handle?.close()
            handle = nil
        }
    }

    /// Reads the records back, in order, removing each file once it is read.
    public func reader() -> DeviceTransferSpoolReader {
        let (segments, bytes) = lock.withLock { (segment, recordBytes) }
        return DeviceTransferSpoolReader(key: key, files: segments == 0 ? [] : (1...segments).map(file), totalBytes: bytes)
    }

    /// Everything goes, also what was not read.
    public func remove() {
        lock.withLock {
            try? handle?.close()
            handle = nil
        }
        try? FileManager.default.removeItem(at: directory)
    }

    private func file(_ number: Int) -> URL {
        directory.appendingPathComponent("history-\(number)")
    }
}

public final class DeviceTransferSpoolReader {
    private var cipher: DeviceTransferCipher
    private var files: ArraySlice<URL>
    private var handle: FileHandle?
    private var current: URL?
    private var readBytes: UInt64 = 0
    private let totalBytes: UInt64

    init(key: Data, files: [URL], totalBytes: UInt64) {
        cipher = DeviceTransferCipher(key: key)
        self.files = files[...]
        self.totalBytes = totalBytes
    }

    /// How much of the history has been read (0…1).
    public var fraction: Double {
        totalBytes == 0 ? 1 : min(1, Double(readBytes) / Double(totalBytes))
    }

    /// The next record; `nil` at the end.
    public func next() throws -> Data? {
        while true {
            if handle == nil {
                guard let url = files.popFirst() else { return nil }
                handle = try FileHandle(forReadingFrom: url)
                current = url
            }
            guard let length = try handle?.read(upToCount: 4), !length.isEmpty else {
                try handle?.close()
                handle = nil
                if let current { try? FileManager.default.removeItem(at: current) }
                current = nil
                continue
            }
            guard length.count == 4 else { throw DeviceTransferError.closed }
            let count = length.reduce(0) { ($0 << 8) | Int($1) }
            guard count <= DeviceTransferLimits.maxFrameBytes, let sealed = try handle?.read(upToCount: count),
                sealed.count == count
            else { throw DeviceTransferError.closed }
            let opened = try cipher.open(sealed)
            guard opened.type == .record else { throw DeviceTransferError.badMessage }
            readBytes += UInt64(opened.body.count)
            return opened.body
        }
    }
}
