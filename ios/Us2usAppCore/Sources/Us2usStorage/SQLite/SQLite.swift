// us2us — AGPL-3.0-only
//
// A deliberately small wrapper over the SQLCipher C API: one connection,
// prepared statements with a cache, typed bindings. Everything here runs
// under the lock of `EncryptedDatabase`; nothing in this file is thread-safe
// on its own.

import Foundation
import SQLCipher
import Us2usSQLCipherKey

/// An SQLite/SQLCipher failure. The message comes from SQLite and never
/// contains bound values (it can name tables, columns or constraints).
public struct SQLiteError: Error, Equatable, Sendable, CustomStringConvertible {
    public let code: Int32
    public let extendedCode: Int32
    public let message: String

    public var description: String { "SQLite error \(extendedCode): \(message)" }

    /// `SQLITE_NOTADB`: wrong key, or not a database at all.
    public var isNotADatabase: Bool { code == SQLITE_NOTADB }
    public var isConstraintViolation: Bool { code == SQLITE_CONSTRAINT }
    public var isBusy: Bool { code == SQLITE_BUSY || code == SQLITE_LOCKED }
}

/// A value bound to or read from a statement.
enum SQLValue: Equatable {
    case null
    case integer(Int64)
    case real(Double)
    case text(String)
    case blob(Data)
}

protocol SQLBindable {
    var sqlValue: SQLValue { get }
}

extension Int64: SQLBindable { var sqlValue: SQLValue { .integer(self) } }
extension Int: SQLBindable { var sqlValue: SQLValue { .integer(Int64(self)) } }
extension Int32: SQLBindable { var sqlValue: SQLValue { .integer(Int64(self)) } }
extension UInt32: SQLBindable { var sqlValue: SQLValue { .integer(Int64(self)) } }
extension UInt64: SQLBindable {
    // Protocol timestamps are ms since 1970 and fit comfortably in Int64.
    var sqlValue: SQLValue { .integer(Int64(clamping: self)) }
}
extension Bool: SQLBindable { var sqlValue: SQLValue { .integer(self ? 1 : 0) } }
extension Double: SQLBindable { var sqlValue: SQLValue { .real(self) } }
extension String: SQLBindable { var sqlValue: SQLValue { .text(self) } }
extension Data: SQLBindable { var sqlValue: SQLValue { .blob(self) } }
extension Optional: SQLBindable where Wrapped: SQLBindable {
    var sqlValue: SQLValue { self?.sqlValue ?? .null }
}
extension SQLValue: SQLBindable { var sqlValue: SQLValue { self } }

/// One result row, addressed by column index or name.
struct Row {
    private let values: [SQLValue]
    private let columns: [String: Int]

    init(values: [SQLValue], columns: [String: Int]) {
        self.values = values
        self.columns = columns
    }

    subscript(_ index: Int) -> SQLValue { values[index] }

    subscript(_ name: String) -> SQLValue {
        guard let index = columns[name] else { preconditionFailure("no column \(name)") }
        return values[index]
    }

    func int64(_ name: String) -> Int64? {
        if case .integer(let value) = self[name] { return value }
        return nil
    }

    func int(_ name: String) -> Int? { int64(name).map(Int.init) }

    func uint64(_ name: String) -> UInt64? { int64(name).map { UInt64(max(0, $0)) } }

    func bool(_ name: String) -> Bool { (int64(name) ?? 0) != 0 }

    func text(_ name: String) -> String? {
        if case .text(let value) = self[name] { return value }
        return nil
    }

    func blob(_ name: String) -> Data? {
        switch self[name] {
        case .blob(let value): return value
        // SQLite returns a zero-length BLOB as NULL only when bound as NULL;
        // keep the distinction explicit.
        default: return nil
        }
    }

    func date(_ name: String) -> Date? {
        int64(name).map { Date(millisecondsSince1970: $0) }
    }
}

/// `SQLITE_TRANSIENT`: SQLite copies bound buffers before the call returns.
private let transient = unsafeBitCast(-1, to: sqlite3_destructor_type.self)

final class Statement {
    let handle: OpaquePointer
    private let connection: OpaquePointer
    private lazy var columnIndex: [String: Int] = {
        var map: [String: Int] = [:]
        for index in 0..<Int(sqlite3_column_count(handle)) {
            map[String(cString: sqlite3_column_name(handle, Int32(index)))] = index
        }
        return map
    }()

    init(connection: OpaquePointer, sql: String) throws(SQLiteError) {
        var statement: OpaquePointer?
        let status = sqlite3_prepare_v3(connection, sql, -1, UInt32(SQLITE_PREPARE_PERSISTENT), &statement, nil)
        guard status == SQLITE_OK, let statement else {
            throw SQLiteConnection.error(connection, status)
        }
        self.handle = statement
        self.connection = connection
    }

    deinit {
        sqlite3_finalize(handle)
    }

    func bind(_ values: [any SQLBindable]) throws(SQLiteError) {
        sqlite3_reset(handle)
        sqlite3_clear_bindings(handle)
        precondition(values.count == Int(sqlite3_bind_parameter_count(handle)), "binding count mismatch")
        for (offset, value) in values.enumerated() {
            let index = Int32(offset + 1)
            let status: Int32
            switch value.sqlValue {
            case .null:
                status = sqlite3_bind_null(handle, index)
            case .integer(let int):
                status = sqlite3_bind_int64(handle, index, int)
            case .real(let double):
                status = sqlite3_bind_double(handle, index, double)
            case .text(let string):
                status = sqlite3_bind_text(handle, index, string, -1, transient)
            case .blob(let data):
                if data.isEmpty {
                    // A zero-length blob still binds as a blob, not as NULL.
                    status = sqlite3_bind_zeroblob(handle, index, 0)
                } else {
                    status = data.withUnsafeBytes { buffer in
                        sqlite3_bind_blob(handle, index, buffer.baseAddress, Int32(buffer.count), transient)
                    }
                }
            }
            guard status == SQLITE_OK else { throw SQLiteConnection.error(connection, status) }
        }
    }

    /// Advances; `true` while a row is available.
    func step() throws(SQLiteError) -> Bool {
        let status = sqlite3_step(handle)
        switch status {
        case SQLITE_ROW: return true
        case SQLITE_DONE: return false
        default:
            let error = SQLiteConnection.error(connection, status)
            sqlite3_reset(handle)
            throw error
        }
    }

    func reset() {
        sqlite3_reset(handle)
        // Drop references to bound buffers (they may hold ciphertext or keys).
        sqlite3_clear_bindings(handle)
    }

    func currentRow() -> Row {
        let count = Int(sqlite3_column_count(handle))
        var values: [SQLValue] = []
        values.reserveCapacity(count)
        for index in 0..<count {
            let column = Int32(index)
            switch sqlite3_column_type(handle, column) {
            case SQLITE_INTEGER:
                values.append(.integer(sqlite3_column_int64(handle, column)))
            case SQLITE_FLOAT:
                values.append(.real(sqlite3_column_double(handle, column)))
            case SQLITE_TEXT:
                values.append(.text(String(cString: sqlite3_column_text(handle, column))))
            case SQLITE_BLOB:
                let length = Int(sqlite3_column_bytes(handle, column))
                if let bytes = sqlite3_column_blob(handle, column), length > 0 {
                    values.append(.blob(Data(bytes: bytes, count: length)))
                } else {
                    values.append(.blob(Data()))
                }
            default:
                values.append(.null)
            }
        }
        return Row(values: values, columns: columnIndex)
    }
}

/// One open SQLCipher connection.
final class SQLiteConnection {
    let handle: OpaquePointer
    private var cache: [String: Statement] = [:]
    private var isClosed = false

    init(path: String) throws(SQLiteError) {
        var db: OpaquePointer?
        let flags = SQLITE_OPEN_READWRITE | SQLITE_OPEN_CREATE | SQLITE_OPEN_FULLMUTEX
        let status = sqlite3_open_v2(path, &db, flags, nil)
        guard status == SQLITE_OK, let db else {
            let error =
                db.map { Self.error($0, status) }
                ?? SQLiteError(code: status, extendedCode: status, message: "open failed")
            if let db { sqlite3_close_v2(db) }
            throw error
        }
        sqlite3_extended_result_codes(db, 1)
        self.handle = db
    }

    deinit {
        close()
    }

    static func error(_ db: OpaquePointer, _ status: Int32) -> SQLiteError {
        SQLiteError(
            code: status & 0xFF, extendedCode: sqlite3_extended_errcode(db),
            message: String(cString: sqlite3_errmsg(db)))
    }

    /// Keys the connection with a raw 256-bit key. SQLCipher's raw-key syntax
    /// `x'<hex>'` skips its passphrase KDF: the key is already uniformly
    /// random (HKDF output, PROTOCOL.md §5.3). The ASCII buffer is wiped
    /// right after the call.
    func key(rawKey: Data) throws(SQLiteError) {
        precondition(rawKey.count == 32, "SQLCipher raw keys are 32 bytes")
        let hexDigits = Array("0123456789ABCDEF".utf8)
        var text = [UInt8](repeating: 0, count: 3 + 64)
        text[0] = UInt8(ascii: "x")
        text[1] = UInt8(ascii: "'")
        for (offset, byte) in rawKey.enumerated() {
            text[2 + offset * 2] = hexDigits[Int(byte >> 4)]
            text[3 + offset * 2] = hexDigits[Int(byte & 0x0F)]
        }
        text[66] = UInt8(ascii: "'")
        let status = text.withUnsafeBytes { buffer in
            us2us_sqlcipher_key(handle, buffer.baseAddress, Int32(buffer.count))
        }
        _ = text.withUnsafeMutableBytes { buffer in
            memset_s(buffer.baseAddress, buffer.count, 0, buffer.count)
        }
        guard status == SQLITE_OK else { throw Self.error(handle, status) }
    }

    /// Runs one or more statements without bindings or results.
    func execute(_ sql: String) throws(SQLiteError) {
        let status = sqlite3_exec(handle, sql, nil, nil, nil)
        guard status == SQLITE_OK else { throw Self.error(handle, status) }
    }

    func statement(_ sql: String) throws(SQLiteError) -> Statement {
        if let cached = cache[sql] { return cached }
        let statement = try Statement(connection: handle, sql: sql)
        cache[sql] = statement
        return statement
    }

    func run(_ sql: String, _ values: [any SQLBindable] = []) throws(SQLiteError) {
        let statement = try statement(sql)
        defer { statement.reset() }
        try statement.bind(values)
        while try statement.step() {}
    }

    func query(_ sql: String, _ values: [any SQLBindable] = []) throws(SQLiteError) -> [Row] {
        let statement = try statement(sql)
        defer { statement.reset() }
        try statement.bind(values)
        var rows: [Row] = []
        while try statement.step() {
            rows.append(statement.currentRow())
        }
        return rows
    }

    func queryOne(_ sql: String, _ values: [any SQLBindable] = []) throws(SQLiteError) -> Row? {
        let statement = try statement(sql)
        defer { statement.reset() }
        try statement.bind(values)
        return try statement.step() ? statement.currentRow() : nil
    }

    /// The first column of the first row.
    func scalar(_ sql: String, _ values: [any SQLBindable] = []) throws(SQLiteError) -> SQLValue? {
        try queryOne(sql, values)?[0]
    }

    var changes: Int { Int(sqlite3_changes(handle)) }

    var lastInsertRowId: Int64 { sqlite3_last_insert_rowid(handle) }

    var isInTransaction: Bool { sqlite3_get_autocommit(handle) == 0 }

    func close() {
        guard !isClosed else { return }
        isClosed = true
        cache.removeAll()
        sqlite3_close_v2(handle)
    }
}

extension Date {
    init(millisecondsSince1970 milliseconds: Int64) {
        self.init(timeIntervalSince1970: TimeInterval(milliseconds) / 1000)
    }

    /// Milliseconds since the epoch as stored in the database.
    var storageMilliseconds: Int64 { Int64((timeIntervalSince1970 * 1000).rounded(.down)) }
}
