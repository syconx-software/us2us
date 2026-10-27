import Foundation
import XCTest

/// Real screens + real local relay. Uses a fresh simulator: never run against
/// an existing user's container. The API-only applicant does not exchange messages.
/// TEST_RUNNER_U2COMMUNITY_RELAY, TEST_RUNNER_U2COMMUNITY_PEER_TOKEN and
/// TEST_RUNNER_U2COMMUNITY_PEER_ID are required; absent setup is an explicit skip.
@MainActor
final class CommunityFlowUITests: XCTestCase {
    private var relay = ""
    private var token = ""
    private var peerID = ""

    override func setUp() async throws {
        continueAfterFailure = false
        let env = ProcessInfo.processInfo.environment
        guard let relay = env["U2COMMUNITY_RELAY"], let token = env["U2COMMUNITY_PEER_TOKEN"],
              let peer = env["U2COMMUNITY_PEER_ID"] else {
            throw XCTSkip("requires an isolated local relay and API-only applicant; see scripts/community-ui-peer.mjs")
        }
        let url = try XCTUnwrap(URL(string: relay))
        XCTAssertEqual(url.scheme, "http")
        XCTAssertTrue(["127.0.0.1", "localhost", "::1"].contains(url.host ?? ""))
        self.relay = relay
        self.token = token
        peerID = peer
    }

    private func node(_ app: XCUIApplication, _ id: String) -> XCUIElement {
        app.descendants(matching: .any)[id].firstMatch
    }

    private func tap(_ element: XCUIElement, file: StaticString = #filePath, line: UInt = #line) {
        XCTAssertTrue(element.waitForExistence(timeout: 20), file: file, line: line)
        XCTAssertTrue(element.wait(for: \.isHittable, toEqual: true, timeout: 10), file: file, line: line)
        element.tap()
    }

    private func api(_ path: String, method: String = "GET", status: Int = 200) async throws -> [String: Any] {
        var request = URLRequest(url: try XCTUnwrap(URL(string: relay + path)))
        request.httpMethod = method
        request.setValue(token, forHTTPHeaderField: "x-auth-token")
        if method == "POST" {
            request.setValue("application/json", forHTTPHeaderField: "content-type")
            request.httpBody = Data("{}".utf8)
        }
        let (data, response) = try await URLSession.shared.data(for: request)
        XCTAssertEqual((response as? HTTPURLResponse)?.statusCode, status)
        return (try? JSONSerialization.jsonObject(with: data) as? [String: Any]) ?? [:]
    }

    private func openDirectory(_ app: XCUIApplication) {
        tap(app.buttons["chats.new"])
        tap(node(app, "newChat.communities"))
        XCTAssertTrue(node(app, "communities.create").waitForExistence(timeout: 10))
    }

    private func create(_ name: String, public isPublic: Bool, in app: XCUIApplication) {
        tap(node(app, "communities.create"))
        let field = app.textFields["communities.create.name"]
        tap(field)
        field.typeText(name)
        if isPublic { tap(app.switches.firstMatch) }
        let submit = node(app, "communities.create.confirm")
        if !submit.isHittable { app.swipeUp() }
        tap(submit)
        XCTAssertTrue(submit.waitForNonExistence(timeout: 20))
    }

    private func enroll() -> (XCUIApplication, String) {
        let app = XCUIApplication()
        app.launchArguments = ["-U2RelayURL", relay, "-U2Language", "tr", "-U2Theme", "dark", "-U2ResetPreferences", "YES"]
        app.launch()
        // A fresh simulator/container is a required part of this test's isolation.
        tap(app.buttons["welcome.start"])
        tap(app.buttons["welcome.page2.cta"])
        tap(app.buttons["welcome.page3.cta"])
        let name = "cui_" + UUID().uuidString.prefix(8).lowercased()
        let username = app.textFields.firstMatch
        tap(username)
        username.typeText(name)
        let next = app.buttons["onboarding.username.continue"]
        XCTAssertTrue(next.wait(for: \.isEnabled, toEqual: true, timeout: 15))
        if next.isHittable { next.tap() } else { username.typeText("\n") }
        XCTAssertTrue(app.buttons["2"].waitForExistence(timeout: 10))
        for digit in "246813" { app.buttons[String(digit)].tap() }
        XCTAssertTrue(app.staticTexts["Kodu tekrar gir"].waitForExistence(timeout: 10))
        for digit in "246813" { app.buttons[String(digit)].tap() }
        tap(node(app, "onboarding.acknowledge"))
        tap(app.buttons["onboarding.finish"])
        XCTAssertTrue(app.buttons["chats.new"].waitForExistence(timeout: 30))

        return (app, name)
    }

    func testJoinRequestsAppearFirstAndOpenApproveReject() async throws {
        let (app, name) = enroll()
        let publicName = "İstekler " + name
        openDirectory(app)
        create(publicName, public: true, in: app)
        let query = try XCTUnwrap(publicName.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed))
        let found = try await api("/v1/communities?query=" + query)
        let groups = try XCTUnwrap(found["communities"] as? [[String: Any]])
        let id = try XCTUnwrap(groups.first { $0["name"] as? String == publicName }?["id"] as? String)
        tap(node(app, "communities.back"))
        let inboxID = "chats.communityRequests." + id
        XCTAssertFalse(node(app, inboxID).exists)
        _ = try await api("/v1/communities/" + id + "/requests", method: "POST", status: 202)
        let inbox = node(app, inboxID)
        XCTAssertTrue(inbox.waitForExistence(timeout: 30))
        let chat = node(app, "chat.community." + id)
        XCTAssertTrue(chat.exists)
        XCTAssertLessThan(inbox.frame.maxY, chat.frame.minY)
        XCTAssertTrue(inbox.label.contains(publicName))
        tap(inbox)
        let pending = node(app, "manage.request." + peerID)
        XCTAssertTrue(pending.waitForExistence(timeout: 20))
        tap(pending.buttons["Reddet"])
        XCTAssertTrue(pending.waitForNonExistence(timeout: 20))
        tap(node(app, "sheet.close"))
        XCTAssertTrue(inbox.waitForNonExistence(timeout: 20))
        _ = try await api("/v1/communities/" + id, status: 404)

        _ = try await api("/v1/communities/" + id + "/requests", method: "POST", status: 202)
        XCTAssertTrue(inbox.waitForExistence(timeout: 30))
        tap(inbox)
        XCTAssertTrue(pending.waitForExistence(timeout: 20))
        // Lock closes management; unlocking reloads the inbox, without reopening the sheet.
        XCUIDevice.shared.press(.home)
        app.activate()
        XCTAssertTrue(app.staticTexts["Gizli kodunu gir"].waitForExistence(timeout: 10))
        for digit in "246813" { app.buttons[String(digit)].tap() }
        XCTAssertTrue(inbox.waitForExistence(timeout: 30))
        XCTAssertFalse(pending.exists)
        tap(inbox)
        XCTAssertTrue(pending.waitForExistence(timeout: 20))
        tap(pending.buttons["Onayla"])
        XCTAssertTrue(pending.waitForNonExistence(timeout: 20))
        tap(node(app, "sheet.close"))
        XCTAssertTrue(inbox.waitForNonExistence(timeout: 20))
        _ = try await api("/v1/communities/" + id)
        XCTAssertTrue(chat.exists, "approval changes no conversation route")
        let shot = XCTAttachment(screenshot: app.screenshot())
        shot.name = "owner-request-inbox-cleared"
        shot.lifetime = .keepAlways
        add(shot)
    }

    func testCreationDiscoveryMediaApprovalAndRelock() async throws {
        let (app, name) = enroll()

        let publicName = "UI Açık " + name
        let privateName = "UI Gizli " + name
        openDirectory(app)
        create(publicName, public: true, in: app)
        create(privateName, public: false, in: app)

        // Search from a different account confirms public visibility and that
        // the private name is not disclosed, even when searched exactly.
        let query = try XCTUnwrap(publicName.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed))
        let found = try await api("/v1/communities?query=" + query)
        let groups = try XCTUnwrap(found["communities"] as? [[String: Any]])
        let group = try XCTUnwrap(groups.first { $0["name"] as? String == publicName })
        let id = try XCTUnwrap(group["id"] as? String)
        let privateQuery = try XCTUnwrap(privateName.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed))
        let hidden = try await api("/v1/communities?query=" + privateQuery)
        XCTAssertTrue((hidden["communities"] as? [[String: Any]])?.isEmpty == true)

        let search = app.textFields["communities.search"]
        if !search.isHittable { app.swipeUp() }
        tap(search)
        search.typeText(publicName + "\n")
        XCTAssertTrue(app.staticTexts[publicName].firstMatch.waitForExistence(timeout: 10))
        // Search can leave the joined section above the viewport. Scroll the
        // directory itself, rather than swiping over the keyboard/window.
        let joined = app.buttons["community." + id]
        for _ in 0..<4 where !joined.isHittable { app.scrollViews["communities.scroll"].swipeDown() }
        tap(joined)
        XCTAssertTrue(node(app, "community.manage").waitForExistence(timeout: 10))
        XCTAssertTrue(app.staticTexts.matching(NSPredicate(format: "label == %@", publicName))
            .allElementsBoundByIndex.contains(where: { $0.isHittable }))
        let input = app.textViews.firstMatch.exists ? app.textViews.firstMatch : app.textFields.firstMatch
        tap(input)
        input.typeText("UI testi önceki mesaj")
        tap(app.buttons["Gönder"])
        XCTAssertTrue(app.staticTexts["UI testi önceki mesaj"].firstMatch.waitForExistence(timeout: 10))

        // The community service is replaced on unlock while the screen stays
        // mounted. Its conversation must reopen on the new service.
        XCUIDevice.shared.press(.home)
        app.activate()
        XCTAssertTrue(app.staticTexts["Gizli kodunu gir"].waitForExistence(timeout: 10))
        for digit in "246813" { app.buttons[String(digit)].tap() }
        XCTAssertTrue(app.staticTexts["UI testi önceki mesaj"].firstMatch.waitForExistence(timeout: 15))

        // Native photo picker: the runner seeds one image with simctl addmedia.
        tap(app.buttons["Ekle"].firstMatch)
        tap(node(app, "menu.photo"))
        let asset = app.images.matching(NSPredicate(format:
            "identifier == 'PXGGridLayout-Info' OR identifier BEGINSWITH 'PickerAssetCell'")).firstMatch
        XCTAssertTrue(asset.waitForExistence(timeout: 15), "seed the fresh simulator with one photo")
        XCTAssertFalse(app.staticTexts["Gizli kodunu gir"].exists, "the app's own picker must not show the lock")
        // A real Home/background transition while Photos is open must still cancel
        // the picker and lock. Unlocking must restore the same community's history.
        XCUIDevice.shared.press(.home)
        app.activate()
        XCTAssertTrue(app.staticTexts["Gizli kodunu gir"].waitForExistence(timeout: 10))
        for digit in "246813" { app.buttons[String(digit)].tap() }
        XCTAssertTrue(app.staticTexts["UI testi önceki mesaj"].firstMatch.waitForExistence(timeout: 15))
        XCTAssertTrue(asset.waitForNonExistence(timeout: 10), "backgrounding cancels the outstanding picker")
        tap(app.buttons["Ekle"].firstMatch)
        tap(node(app, "menu.photo"))
        XCTAssertTrue(asset.waitForExistence(timeout: 15))
        XCTAssertFalse(app.staticTexts["Gizli kodunu gir"].exists)
        tap(asset)
        let addPhoto = app.buttons["Add"] // PHPicker's identifier; the Turkish label is “Bitti”.
        XCTAssertTrue(addPhoto.waitForExistence(timeout: 10))
        if addPhoto.isHittable { addPhoto.tap() } else {
            // The remote Photos toolbar on iOS 27 reports a false hit-test
            // result. Tap the centre of its reported accessibility frame.
            addPhoto.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap()
        }
        XCTAssertTrue(asset.waitForNonExistence(timeout: 10))
        XCTAssertFalse(app.staticTexts["Gizli kodunu gir"].exists, "returning from the picker must keep the session unlocked")
        XCTAssertTrue(app.staticTexts["UI testi önceki mesaj"].firstMatch.waitForExistence(timeout: 15))
        let messages = app.descendants(matching: .any).matching(NSPredicate(format: "identifier BEGINSWITH 'message.'"))
        XCTAssertGreaterThanOrEqual(messages.count, 2)

        // Approval happens through the owner UI; the API applicant sees no
        // roster before approval, and can retrieve it afterwards.
        _ = try await api("/v1/communities/" + id + "/requests", method: "POST", status: 202)
        _ = try await api("/v1/communities/" + id, status: 404)
        tap(node(app, "community.manage"))
        let pending = node(app, "manage.request." + peerID)
        XCTAssertTrue(pending.waitForExistence(timeout: 30))
        tap(pending.buttons["Onayla"])
        XCTAssertTrue(pending.waitForNonExistence(timeout: 20))
        _ = try await api("/v1/communities/" + id)

        let shot = XCTAttachment(screenshot: app.screenshot())
        shot.name = "community-approved"
        shot.lifetime = .keepAlways
        add(shot)
    }
}
