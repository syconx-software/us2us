import Foundation
import MapKit
import Testing

@testable import us2us

// 5.2 › Konum: the links handed to Apple Maps and Google Maps, the live
// map's framing, the live service's tick bookkeeping and snapshot, the
// fixtures' position streams, the live map's state and the conversation's
// location taps.

private let moda = MapPlace(name: "Moda İskelesi", latitude: 40.9846, longitude: 29.0249)

@Suite("Location · map links")
struct MapLinkTests {
    @Test func coordinatesAlwaysUseAPoint() {
        #expect(MapLinks.coordinate(40.9846) == "40.984600")
        #expect(MapLinks.coordinate(-0.5) == "-0.500000")
        #expect(MapLinks.coordinatePair(moda) == "40.984600,29.024900")
        #expect(MapLinks.displayCoordinates(moda) == "40.98460, 29.02490")
    }

    @Test func appleMapsPinsThePlaceWithItsName() throws {
        let url = try #require(MapLinks.url(for: .apple, place: moda, googleMapsAppInstalled: true))
        #expect(url.absoluteString == "https://maps.apple.com/?ll=40.984600,29.024900&q=Moda%20%C4%B0skelesi")
        let components = try #require(URLComponents(url: url, resolvingAgainstBaseURL: false))
        #expect(
            components.queryItems
                == [URLQueryItem(name: "ll", value: "40.984600,29.024900"), URLQueryItem(name: "q", value: "Moda İskelesi")])
    }

    @Test func withoutANameAppleMapsDropsAPinOnly() throws {
        let url = try #require(MapLinks.appleMaps(MapPlace(name: "  ", latitude: 41.0082, longitude: 28.9784)))
        #expect(url.absoluteString == "https://maps.apple.com/?ll=41.008200,28.978400")
    }

    @Test func reservedCharactersOfANameAreEncoded() throws {
        let url = try #require(MapLinks.appleMaps(MapPlace(name: "Kafe & Bar + 1", latitude: 1, longitude: 2)))
        #expect(url.absoluteString.hasSuffix("&q=Kafe%20%26%20Bar%20%2B%201"))
        let components = try #require(URLComponents(url: url, resolvingAgainstBaseURL: false))
        #expect(components.queryItems?.last == URLQueryItem(name: "q", value: "Kafe & Bar + 1"))
    }

    @Test func googleMapsOpensTheAppWhenItIsInstalled() throws {
        let url = try #require(MapLinks.url(for: .google, place: moda, googleMapsAppInstalled: true))
        #expect(url.scheme == MapLinks.googleMapsScheme)
        #expect(url.absoluteString == "comgooglemaps://?q=40.984600,29.024900&center=40.984600,29.024900&zoom=16")
    }

    @Test func otherwiseGoogleMapsOpensInTheBrowser() throws {
        let url = try #require(MapLinks.url(for: .google, place: moda, googleMapsAppInstalled: false))
        #expect(url.absoluteString == "https://www.google.com/maps/search/?api=1&query=40.984600,29.024900")
        // Only the coordinates go to Google, never the sender's name for the place.
        #expect(!url.absoluteString.contains("Moda"))
    }
}

@Suite("Location · live map framing")
struct LiveMapFramingTests {
    private let pier = LivePosition(latitude: 40.9846, longitude: 29.0249)
    private let street = LivePosition(latitude: 40.9872, longitude: 29.0262)

    @Test func nothingToFrameWithoutPoints() {
        #expect(LiveMapFraming.region(for: []) == nil)
    }

    @Test func bothPeopleAreInViewWithRoomAround() throws {
        let region = try #require(LiveMapFraming.region(for: [pier, street]))
        #expect(abs(region.center.latitude - 40.9859) < 1e-9)
        #expect(abs(region.center.longitude - 29.02555) < 1e-9)
        for point in [pier, street] {
            #expect(abs(point.latitude - region.center.latitude) < region.span.latitudeDelta / 2)
            #expect(abs(point.longitude - region.center.longitude) < region.span.longitudeDelta / 2)
        }
        // The height keeps room for the header and the bottom card.
        let height = street.latitude - pier.latitude
        #expect(region.span.latitudeDelta >= height * LiveMapFraming.latitudeMargin - 1e-12)
    }

    @Test func aSinglePointShowsItsStreet() throws {
        let region = try #require(LiveMapFraming.region(for: [pier]))
        #expect(region.center.latitude == pier.latitude)
        #expect(region.center.longitude == pier.longitude)
        let high = region.span.latitudeDelta * 111_320
        let wide = region.span.longitudeDelta * 111_320 * cos(pier.latitude * .pi / 180)
        #expect(abs(high - LiveMapFraming.minimumSpanMeters) < 0.5)
        #expect(abs(wide - LiveMapFraming.minimumSpanMeters) < 0.5)
    }

    @Test func pointsEitherSideOfTheDateLineStayNeighbours() throws {
        let region = try #require(
            LiveMapFraming.region(for: [
                LivePosition(latitude: -16.5, longitude: 179.999), LivePosition(latitude: -16.5, longitude: -179.999),
            ]))
        #expect(abs(abs(region.center.longitude) - 180) < 0.01)
        #expect(region.span.longitudeDelta < 1)
    }

    @Test func positionsThatAreNoPlaceAreLeftOut() throws {
        let region = try #require(
            LiveMapFraming.region(for: [
                pier, LivePosition(latitude: .nan, longitude: 10), LivePosition(latitude: 95, longitude: .infinity),
            ]))
        #expect(region.center.latitude == pier.latitude)
        #expect(region.center.longitude == pier.longitude)
        #expect(LiveMapFraming.region(for: [LivePosition(latitude: .infinity, longitude: 0)]) == nil)
    }

    @Test func accuracyCirclesOnlyWhenTheyMeanSomething() {
        #expect(!LiveMapFraming.showsAccuracy(0))
        #expect(LiveMapFraming.showsAccuracy(12))
        #expect(!LiveMapFraming.showsAccuracy(5_000))
        #expect(!LiveMapFraming.showsAccuracy(.nan))
    }
}

@Suite("Location · live service mapping")
struct LiveShareMappingTests {
    private let start = Date(timeIntervalSince1970: 1_791_000_000)

    private func id(_ byte: UInt8) -> Data { Data(repeating: byte, count: 16) }

    private func record(
        _ byte: UInt8, outgoing: Bool = false, sequence: UInt32 = 0, heading: Float = -1, stopped: Bool = false,
        minutes: Double = 60
    ) -> LiveLocationRecord {
        LiveLocationRecord(
            id: id(byte), outgoing: outgoing, sentAt: start, latitude: 40.9846, longitude: 29.0249, accuracy: 15,
            heading: heading, expiresAt: start.addingTimeInterval(minutes * 60), sequence: sequence, stopped: stopped)
    }

    @Test func tickTimesFollowTheSequence() {
        let seen = start.addingTimeInterval(120)
        var ticks = LiveMapping.liveTicks([record(1), record(2, sequence: 4)], previous: [:], now: seen)
        #expect(ticks[id(1)]?.at == start, "a share at its start began when it was sent")
        #expect(ticks[id(2)]?.at == nil, "a share first seen mid-way has no known time")
        let moved = seen.addingTimeInterval(20)
        ticks = LiveMapping.liveTicks([record(1, sequence: 1), record(2, sequence: 4)], previous: ticks, now: moved)
        #expect(ticks[id(1)]?.at == moved)
        #expect(ticks[id(2)]?.sequence == 4)
        #expect(ticks[id(2)]?.at == nil, "an unchanged sequence keeps what was known")
        ticks = LiveMapping.liveTicks([record(2, sequence: 4)], previous: ticks, now: moved)
        #expect(ticks.count == 1, "shares that are gone are forgotten")
    }

    @Test func theSnapshotCarriesBothSides() throws {
        let records = [record(1), record(2, outgoing: true, heading: 90)]
        let ticks = LiveMapping.liveTicks(records, previous: [:], now: start)
        let snapshot = LiveMapping.liveLocationSnapshot(records, ticks: ticks)
        let peer = try #require(snapshot.activeShare(.incoming, at: start))
        #expect(peer.id == LiveMapping.messageID(id(1)))
        #expect(!peer.isOutgoing)
        #expect(peer.position.heading == nil, "a negative heading is unknown")
        #expect(peer.position.accuracy == 15)
        #expect(peer.position.updatedAt == start)
        let own = try #require(snapshot.activeShare(.outgoing, at: start))
        #expect(own.isOutgoing)
        #expect(own.position.heading == 90)
    }

    @Test func endedSharesAreNoLongerShown() {
        let snapshot = LiveMapping.liveLocationSnapshot([record(1, stopped: true), record(2, minutes: 1)], ticks: [:])
        #expect(snapshot.shares.count == 2)
        #expect(snapshot.activeShare(.incoming, at: start)?.id == LiveMapping.messageID(id(2)))
        #expect(snapshot.activeShare(.incoming, at: start.addingTimeInterval(61)) == nil)
        #expect(snapshot.share(LiveMapping.messageID(id(1)))?.isStopped == true)
    }

    @Test func theNewestRunningShareIsTheOneShown() {
        let snapshot = LiveMapping.liveLocationSnapshot([record(1), record(2), record(3, stopped: true)], ticks: [:])
        #expect(snapshot.activeShare(.incoming, at: start)?.id == LiveMapping.messageID(id(2)))
        #expect(LiveLocationSnapshot.empty.activeShare(.incoming, at: start) == nil)
    }
}

@Suite("Location · fixtures")
@MainActor
struct LocationFixtureTests {
    private let now = DesignFixtures.referenceDate

    private func makeService() -> DesignFixturesService {
        DesignFixturesService(hasAccount: true, clock: .frozen(at: DesignFixtures.referenceDate), latency: .zero)
    }

    @Test func myShareHasAPositionUntilItStops() async throws {
        let service = makeService()
        let own = try #require(service.liveLocations(in: "mert_k").activeShare(.outgoing, at: now))
        #expect(own.id == "m4")
        #expect(own.position == DesignFixturesService.ownFixturePosition)
        #expect(service.liveLocations(in: "mert_k").activeShare(.incoming, at: now) == nil)
        await service.stopLiveLocation("m4", in: "mert_k")
        #expect(service.liveLocations(in: "mert_k").activeShare(.outgoing, at: now) == nil)
        #expect(service.liveLocations(in: "deniz").shares.isEmpty)
    }

    @Test func theContactsDemoShareStartsAtThePier() throws {
        let service = makeService()
        let id = service.startDemoLiveLocation(in: "mert_k")
        let peer = try #require(service.liveLocations(in: "mert_k").activeShare(.incoming, at: now))
        #expect(peer.id == id)
        #expect(peer.position == DesignFixturesService.peerFixturePosition)
        #expect(peer.expiresAt == now.addingTimeInterval(38 * 60))
        #expect(service.startDemoLiveLocation(in: "mert_k") == id, "it starts once")
        #expect(service.messages(in: "mert_k").filter { $0.id == id }.count == 1)
    }

    @Test func aNewLiveShareStartsWhereIAm() async throws {
        let service = makeService()
        await service.send(attachment: .liveLocation(duration: 3_600), in: "deniz")
        let share = try #require(service.liveLocations(in: "deniz").activeShare(.outgoing, at: now))
        #expect(share.position == DesignFixturesService.ownFixturePosition)
    }

    /// The first state a fresh own-position stream reports (the stream ends with the loop).
    private func firstUpdate(of service: DesignFixturesService) async -> OwnPositionState? {
        for await state in service.ownPositionUpdates() {
            return state
        }
        return nil
    }

    @Test func thePhonesPositionStreams() async {
        let service = makeService()
        let first = await firstUpdate(of: service)
        #expect(first == .position(DesignFixturesService.ownFixturePosition))
    }

    @Test func aRefusalStaysRefusedAndAQuestionIsAnswered() async {
        let service = makeService()
        service.ownPositionOverride = .denied
        let refused = await firstUpdate(of: service)
        #expect(refused == .denied)
        let stillRefused = await service.requestOwnPositionAccess()
        #expect(!stillRefused)

        service.ownPositionOverride = .needsPermission
        let question = await firstUpdate(of: service)
        #expect(question == .needsPermission)
        let granted = await service.requestOwnPositionAccess()
        #expect(granted)
        let position = await firstUpdate(of: service)
        #expect(position == .position(DesignFixturesService.ownFixturePosition))
    }
}

@Suite("Location · live map")
@MainActor
struct LiveLocationMapModelTests {
    private let now = DesignFixtures.referenceDate

    private func makeService() -> DesignFixturesService {
        DesignFixturesService(hasAccount: true, clock: .frozen(at: DesignFixtures.referenceDate), latency: .zero)
    }

    @Test func bothPeopleAreOnTheMap() {
        let service = makeService()
        service.startDemoLiveLocation(in: "mert_k")
        let map = LiveLocationMapModel(chatID: "mert_k", focus: "m4", messenger: service)
        #expect(map.peerUsername == "mert_k")
        #expect(map.peerPosition(at: now) == DesignFixturesService.peerFixturePosition)
        #expect(map.ownPosition(at: now) == DesignFixturesService.ownFixturePosition, "the position last shared, until the phone reports")
        #expect(map.points(at: now).count == 2)
        guard case .sharing(let share) = map.ownRow(at: now) else {
            Issue.record("selin_n's own share is running")
            return
        }
        #expect(share.id == "m4")
        let phone = LivePosition(latitude: 40.9875, longitude: 29.0270, accuracy: 8)
        map.receive(.position(phone))
        #expect(map.ownPosition(at: now) == phone, "this device's own position comes first")
        #expect(map.isLive(at: now))
        #expect(!map.isGone)
    }

    @Test func withoutLocationAccessOnlyTheContactShows() async {
        let service = makeService()
        let peer = service.startDemoLiveLocation(in: "mert_k")
        await service.stopLiveLocation("m4", in: "mert_k")
        let map = LiveLocationMapModel(chatID: "mert_k", focus: peer, messenger: service)
        map.receive(.denied)
        #expect(map.ownPosition(at: now) == nil)
        #expect(map.points(at: now) == [DesignFixturesService.peerFixturePosition])
        #expect(map.ownRow(at: now) == .denied)
        map.receive(.needsPermission)
        #expect(map.ownRow(at: now) == .askPermission)
        map.receive(.locating)
        #expect(map.ownRow(at: now) == .locating)
        map.receive(.position(DesignFixturesService.ownFixturePosition))
        #expect(map.ownRow(at: now) == .device)
        map.receive(.unavailable)
        #expect(map.ownRow(at: now) == .hidden)
    }

    @Test func thisPhonesPositionIsFramedOnlyWhenAskedFor() async {
        let service = makeService()
        let peer = service.startDemoLiveLocation(in: "mert_k")
        await service.stopLiveLocation("m4", in: "mert_k")
        let map = LiveLocationMapModel(chatID: "mert_k", focus: peer, messenger: service)
        let phone = LivePosition(latitude: 40.9875, longitude: 29.0270, accuracy: 8)
        map.receive(.position(phone))
        #expect(map.ownPosition(at: now) == phone, "the marker is drawn")
        #expect(map.points(at: now) == [DesignFixturesService.peerFixturePosition], "no tiles around the user unasked")
        map.framesDevicePosition = true
        #expect(map.points(at: now) == [DesignFixturesService.peerFixturePosition, phone])
    }

    @Test func anEndedShareLeavesTheMap() async {
        let service = makeService()
        let peer = service.startDemoLiveLocation(in: "mert_k")
        let map = LiveLocationMapModel(chatID: "mert_k", focus: peer, messenger: service)
        await service.stopLiveLocation(peer, in: "mert_k")
        await service.stopLiveLocation("m4", in: "mert_k")
        #expect(map.peerShare(at: now) == nil, "receivers stop showing it (PROTOCOL §8.3)")
        #expect(map.peerPosition(at: now) == nil)
        #expect(!map.isLive(at: now))
        #expect(!map.isGone, "its bubble is still there")
    }

    @Test func theMapClosesWhenItsShareGoes() async {
        let service = makeService()
        let map = LiveLocationMapModel(chatID: "mert_k", focus: "m4", messenger: service)
        #expect(!map.isGone)
        await service.deleteForMe("m4", in: "mert_k")
        #expect(map.isGone)
    }
}

@Suite("Location · conversation")
@MainActor
struct LocationConversationTests {
    private func makeModel() -> (ConversationViewModel, DesignFixturesService) {
        let service = DesignFixturesService(hasAccount: true, clock: .frozen(at: DesignFixtures.referenceDate), latency: .zero)
        return (ConversationViewModel(chatID: "mert_k", messenger: service), service)
    }

    @Test func aPlaceOpensTheChooser() throws {
        let (model, _) = makeModel()
        model.openLocation(try #require(model.message("m3")))
        let choice = try #require(model.locationChoice)
        #expect(choice.place == moda)
        #expect(!choice.isDisappearing)
        model.chooseMapApp(.google)
        #expect(model.locationChoice == nil, "the sheet goes down before the app opens")
        model.closeLocationChoice()
    }

    @Test func aDisappearingPlaceSaysTheMapsAppMayKeepIt() throws {
        let (model, service) = makeModel()
        service.setDisappearing(.oneHour, of: "m3", in: "mert_k")
        model.openLocation(try #require(model.message("m3")))
        let choice = try #require(model.locationChoice)
        #expect(choice.isDisappearing)
        let note = StringTable.conversation.string("location.open.disappearing", locale: Locale(identifier: "tr"))
        #expect(note.hasPrefix("Süreli mesaj"))
        model.closeLocationChoice()
    }

    @Test func onlyAPlaceOpensTheChooser() throws {
        let (model, _) = makeModel()
        model.openLocation(try #require(model.message("m4")))
        model.openLocation(try #require(model.message("m2")))
        #expect(model.locationChoice == nil)
    }

    @Test func aRunningLiveShareOpensTheMapAndAnEndedOneDoesNot() async throws {
        let (model, service) = makeModel()
        model.openLiveMap(try #require(model.message("m3")))
        #expect(model.liveMap == nil)
        model.openLiveMap(try #require(model.message("m4")))
        #expect(model.liveMap?.id == "m4")
        model.closeLocationScreens()
        #expect(model.liveMap == nil)
        await service.stopLiveLocation("m4", in: "mert_k")
        model.openLiveMap(try #require(model.message("m4")))
        #expect(model.liveMap == nil)
    }

    #if DEBUG
        @Test func theHarnessOpensEveryLocationState() {
            #expect(ConversationHarness.Screen(rawValue: "5.2-place") == .place)
            #expect(ConversationHarness.Screen(rawValue: "5.2-map") == .liveMap)
            #expect(ConversationHarness.Screen(rawValue: "5.2-map-peer") == .liveMapPeer)
            #expect(ConversationHarness.Screen(rawValue: "5.2-map-ask") == .liveMapAsk)
            #expect(ConversationHarness.Screen(rawValue: "5.2-map-denied") == .liveMapDenied)
        }
    #endif

    @Test func everyLocationStringHasTurkishAndEnglish() throws {
        let url = URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
            .appendingPathComponent("Sources/Features/Conversation/Conversation.xcstrings")
        let object = try JSONSerialization.jsonObject(with: Data(contentsOf: url))
        let catalog = try #require(object as? [String: Any])
        let strings = try #require(catalog["strings"] as? [String: [String: Any]])
        let keys = strings.keys.filter {
            $0.hasPrefix("location.open.") || $0.hasPrefix("liveMap.") || $0 == "a11y.liveLocation.map"
        }
        #expect(keys.count == 22)
        for key in keys {
            let localizations = strings[key]?["localizations"] as? [String: Any]
            #expect(localizations?["tr"] != nil, "\(key) has no Turkish")
            #expect(localizations?["en"] != nil, "\(key) has no English")
        }
    }
}
