import Foundation
import Testing

@testable import us2us

/// The preset profile avatars (DesignSystem/Avatars): stable ids, both
/// languages everywhere, a distinct look for every preset, and how a stored
/// `AvatarChoice` is read back.
@Suite("Brand avatar presets")
struct AvatarPresetTests {
    /// The published id table. Rows may be appended; a row is never changed
    /// or removed — a retired preset moves to `retiredIDs`.
    private static let idTable: [(id: UInt32, key: String)] = [
        (1, "cup.classic"), (2, "cup.headphones"), (3, "cup.beanie"), (4, "cup.glasses"), (5, "cup.flower"),
        (6, "cup.cap"), (7, "cup.scarf"), (8, "cup.ghost"), (9, "cup.cool"), (10, "cup.party"),
        (11, "cup.stringHeart"), (12, "cup.sleepy"), (13, "cup.wink"), (14, "cup.crown"), (15, "cup.bow"),
        (16, "cup.coffee"), (17, "cup.curious"), (18, "cup.twin"), (19, "cup.catEars"), (20, "cup.chef"),
        (21, "cup.antennae"), (22, "cup.laugh"),
    ]

    private static let all = BrandAvatarCatalog.all

    // MARK: Ids

    @Test("ids are unique and match the published table")
    func idsAreUniqueAndStable() {
        #expect(Set(Self.all.map(\.id)).count == Self.all.count)
        #expect(Set(Self.all.map(\.key)).count == Self.all.count)
        #expect(Self.all.count == Self.idTable.count)
        for (preset, row) in zip(Self.all, Self.idTable) {
            #expect(preset.id == row.id, "\(row.key)")
            #expect(preset.key == row.key, "\(row.id)")
        }
        #expect((16...24).contains(Self.all.count))
    }

    @Test("retired ids are never handed out again, and 0 is never an id")
    func retiredIDsStayRetired() {
        for preset in Self.all {
            #expect(!BrandAvatarCatalog.retiredIDs.contains(preset.id), "\(preset.key)")
            #expect(preset.id != 0, "\(preset.key)")
        }
    }

    @Test("lookups by id")
    func lookups() {
        #expect(BrandAvatarCatalog.preset(id: 2)?.key == "cup.headphones")
        #expect(BrandAvatarCatalog.preset(id: 8)?.accessory == .ghostSheet)
        #expect(BrandAvatarCatalog.preset(id: 0) == nil)
        #expect(BrandAvatarCatalog.preset(id: 999) == nil)
    }

    // MARK: Strings

    @Test("every preset has a Turkish and an English label")
    func labels() {
        for preset in Self.all {
            #expect(!preset.label.turkish.isEmpty, "\(preset.key)")
            #expect(!preset.label.english.isEmpty, "\(preset.key)")
            #expect(preset.label(.turkish) == preset.label.turkish)
            #expect(preset.label(.english) == preset.label.english)
        }
    }

    @Test("labels are distinct, so VoiceOver tells the presets apart")
    func distinctLabels() {
        #expect(Set(Self.all.map(\.label.turkish)).count == Self.all.count)
        #expect(Set(Self.all.map(\.label.english)).count == Self.all.count)
    }

    @Test("no empty or padded string anywhere")
    func noEmptyStrings() {
        func check(_ string: String, _ what: String) {
            #expect(!string.isEmpty, "\(what)")
            #expect(string == string.trimmingCharacters(in: .whitespacesAndNewlines), "\(what): “\(string)”")
            #expect(!string.contains("  "), "\(what): “\(string)”")
        }
        for preset in Self.all {
            check(preset.key, "\(preset.id) key")
            check(preset.label.turkish, "\(preset.key) label tr")
            check(preset.label.english, "\(preset.key) label en")
        }
    }

    // MARK: Looks

    @Test("no two presets look the same")
    func distinctLooks() {
        struct Look: Hashable {
            let backdrop: BrandAvatarBackdrop
            let expression: BrandAvatarExpression
            let accessory: BrandAvatarAccessory
            let cup: BrandAvatarCup
        }
        let looks = Self.all.map {
            Look(backdrop: $0.backdrop, expression: $0.expression, accessory: $0.accessory, cup: $0.cup)
        }
        #expect(Set(looks).count == Self.all.count)
    }

    @Test("every accessory is worn and the backdrops are shared out")
    func coverage() {
        let worn = Set(Self.all.map(\.accessory))
        for accessory in BrandAvatarAccessory.allCases {
            #expect(worn.contains(accessory), "\(accessory) is drawn but no preset wears it")
        }
        for backdrop in BrandAvatarBackdrop.allCases {
            let count = Self.all.filter { $0.backdrop == backdrop }.count
            #expect((1...4).contains(count), "\(backdrop): \(count)")
        }
    }

    @Test("the four Nocturne backdrops are the initial avatars' palette tokens")
    func nocturneBackdrops() {
        #expect(BrandAvatarBackdrop.violet.token == U2ColorTokens.accent800)
        #expect(BrandAvatarBackdrop.plum.token == U2ColorTokens.accent700)
        #expect(BrandAvatarBackdrop.ink.token == U2ColorTokens.accent900)
        #expect(BrandAvatarBackdrop.slate.token == U2ColorTokens.neutral800)
    }

    @Test("small avatars leave the extras out; 40 pt and up draw everything")
    func detail() {
        #expect(BrandAvatarDetail(diameter: 28) == .compact)
        #expect(BrandAvatarDetail(diameter: 38) == .compact)
        #expect(BrandAvatarDetail(diameter: 40) == .full)
        #expect(BrandAvatarDetail(diameter: 96) == .full)
    }

    // MARK: Choice

    @Test(
        "a choice survives storage",
        arguments: [AvatarChoice.initials, .photo, .preset(1), .preset(22), .preset(999)])
    func storageRoundTrip(_ choice: AvatarChoice) throws {
        #expect(AvatarChoice(storageValue: choice.storageValue) == choice)
        let data = try JSONEncoder().encode(choice)
        #expect(try JSONDecoder().decode(AvatarChoice.self, from: data) == choice)
    }

    @Test("the stored form is pinned")
    func storageValues() throws {
        #expect(AvatarChoice.initials.storageValue == "initials")
        #expect(AvatarChoice.photo.storageValue == "photo")
        #expect(AvatarChoice.preset(7).storageValue == "preset:7")
        #expect(String(decoding: try JSONEncoder().encode(AvatarChoice.preset(7)), as: UTF8.self) == "\"preset:7\"")
        #expect(AvatarChoice.default == .initials)
    }

    @Test(
        "an empty, unknown or malformed value is the initial",
        arguments: [
            "", " ", "preset", "preset:", "preset:x", "preset:0", "preset:-1", "preset:99999999999", "Photo", "emoji:🫶",
        ])
    func malformedValues(_ stored: String) {
        #expect(AvatarChoice(storageValue: stored) == .initials)
    }

    @Test("a preset this build does not know is kept, and drawn as the initial")
    func unknownPreset() {
        let newer = AvatarChoice(storageValue: "preset:999")
        #expect(newer == .preset(999))
        #expect(newer.preset == nil)
        #expect(newer.drawn == .initials)
        #expect(newer.storageValue == "preset:999")
        #expect(AvatarChoice.preset(2).drawn == .preset(2))
        #expect(AvatarChoice.preset(2).preset?.key == "cup.headphones")
        #expect(AvatarChoice.photo.drawn == .photo)
        #expect(AvatarChoice.initials.preset == nil)
    }
}
