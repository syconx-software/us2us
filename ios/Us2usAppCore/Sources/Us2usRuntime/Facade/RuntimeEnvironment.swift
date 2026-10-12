import Foundation
import Us2usKit
import Us2usStorage

/// Everything ``AppRuntime`` depends on that is not tied to the main actor.
public struct RuntimeEnvironment: Sendable {
    public var directories: AppDirectories
    public var secureItems: any SecureItemStore
    public var biometrics: any BiometricKeyProtecting
    public var backends: any BackendFactory
    public var clock: any RuntimeClock
    public var vault: VaultController.Configuration
    public var engine: MessagingEngine.Configuration
    public var reconnectBackoff: Backoff
    /// §5.4: the connection stays this long after the app leaves the foreground.
    public var gracePeriod: TimeInterval
    /// Watch the network path to reconnect at once when it returns.
    public var watchesNetworkPath: Bool

    public init(
        directories: AppDirectories, secureItems: any SecureItemStore, biometrics: any BiometricKeyProtecting,
        backends: any BackendFactory, clock: any RuntimeClock = SystemClock(),
        vault: VaultController.Configuration = VaultController.Configuration(),
        engine: MessagingEngine.Configuration = MessagingEngine.Configuration(), reconnectBackoff: Backoff = Backoff(),
        gracePeriod: TimeInterval = 30, watchesNetworkPath: Bool = true
    ) {
        self.directories = directories
        self.secureItems = secureItems
        self.biometrics = biometrics
        self.backends = backends
        self.clock = clock
        self.vault = vault
        self.engine = engine
        self.reconnectBackoff = reconnectBackoff
        self.gracePeriod = gracePeriod
        self.watchesNetworkPath = watchesNetworkPath
    }

    /// The app's environment: files under Application Support (complete
    /// protection, no backups), the Keychain (`WhenUnlockedThisDeviceOnly`,
    /// never synchronized), the Secure Enclave, and the production relay.
    public static func production(relayURL: URL = RelayClient.productionURL) throws -> RuntimeEnvironment {
        RuntimeEnvironment(
            directories: try AppDirectories.standard(), secureItems: KeychainItemStore(),
            biometrics: SecureEnclaveBiometricProtector(),
            backends: RelayBackendFactory(relayURL: try NetworkPolicy.validatedRelayURL(relayURL)))
    }
}

/// Where the vault and the UI stand.
public enum LockState: Sendable, Equatable {
    /// No vault on this phone: onboarding (create an account or restore a backup).
    case needsSetup
    /// The lock screen: the code or Face ID is required. Shown at every launch
    /// and after every return from the background (owner decision).
    case locked
    /// An unlock, registration or restore is running.
    case working
    case unlocked
}

/// An in-app banner for a message in another conversation (no push exists).
public struct MessageBanner: Sendable, Equatable, Identifiable {
    public let id: UUID
    public let peer: AccountId
    /// The contact's name.
    public let title: String
    /// Always the generic "New message"; content is not shown in banners.
    public let text: String
}

public enum RuntimeError: Error, Equatable, Sendable {
    /// The action needs an unlocked vault.
    case locked
    /// A vault already exists (onboarding is over).
    case alreadySetUp
    /// The username is invalid.
    case invalidUsername(UsernameError)
    /// The backup file could not be read.
    case unreadableBackup
    /// The relay revoked this installation's token (restored on another phone).
    case revoked
    /// The relay refuses the backup's token: the backup was restored on
    /// another phone since, or its account was deleted. Restoring it can
    /// never succeed.
    case backupRevoked
}
