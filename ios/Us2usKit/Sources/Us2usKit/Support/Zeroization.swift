import Foundation

extension Data {
    /// Overwrites the bytes with zeros in place, for key material that is no
    /// longer needed (KDF intermediates, the loaded device secret).
    ///
    /// Best effort, like `SecretKeyMaterial` in the app core: if the buffer is
    /// shared with another `Data`, copy-on-write zeroes a fresh copy and the
    /// other value keeps its bytes, and copies made by callees (CommonCrypto,
    /// CryptoKit, Keychain `CFData`) are out of reach. Use it on values this
    /// code created and owns.
    mutating func zeroize() {
        let count = self.count
        guard count > 0 else { return }
        withUnsafeMutableBytes { raw in
            if let base = raw.baseAddress { memset_s(base, count, 0, count) }
        }
    }
}
