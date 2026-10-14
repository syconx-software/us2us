import CoreLocation
import MapKit
import SwiftUI
import UIKit

/// 5.2 › Canlı konum haritası (proposed; the design draws only the bubbles):
/// a tap on an active live-location bubble opens this full-screen map
/// (fade cover, as 5.6 — no swipe dismissal, which would fight the map).
///
/// * The contact's latest live position and the user's own: this device's
///   position (when-in-use; shown here only, never sent), else the position
///   last shared. The contact's marker goes when their share ends (PROTOCOL
///   §8.3); without location access only the contact shows, with a gentle hint.
/// * Header (C8 strip, `bg·80 %` blur, dotted rule): close · username over
///   “Canlı konum · Son konum 18:51” · “Konumuma git” · “İkinizi de göster”.
/// * Bottom card in the 5.3 menu material: the contact (remaining time,
///   “Haritada aç” → the floating chooser), the user (sharing + “Paylaşımı
///   durdur”, this device only, or the hint), and a footnote that the map
///   itself loads from Apple.
/// * The camera keeps both in view and follows every tick until the user
///   moves the map; Reduce Motion: it jumps instead of gliding. This
///   device's own position, while it is not shared, is taken in only once
///   the user asks (“Konumuma git”, “İkinizi de göster”): until then the map
///   loads no tiles around the user unasked.
/// * The positions are in memory only; nothing here is stored or logged.
struct LiveLocationMapView: View {
    let onClose: () -> Void

    @State private var model: LiveLocationMapModel
    @State private var camera: MapCameraPosition = .automatic
    @State private var follow: Follow = .both
    /// The contact's share was on the map: once it ends, its row says so.
    @State private var peerWasShown = false
    @State private var headerHeight: CGFloat = 0
    @State private var bottomHeight: CGFloat = 0
    @State private var choice: MapChoice?
    @State private var pendingLaunch: MapLaunch?
    @Environment(\.u2Clock) private var clock
    @Environment(\.locale) private var locale
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.scenePhase) private var scenePhase
    @Environment(\.openURL) private var openURL
    /// The own profile picture (proposed), on the own marker and row.
    @Environment(\.u2OwnAvatar) private var ownPicture

    /// What the camera keeps in view while the user has not moved the map.
    private enum Follow {
        case both
        case me
    }

    /// The own-position stream runs only while the app is active, and starts
    /// again after a permission request.
    private struct PositionTask: Equatable {
        let isActive: Bool
        let generation: Int
    }

    private static let rowPadding = EdgeInsets(top: 12, leading: 14, bottom: 12, trailing: 12)

    init(chatID: ChatID, focus: MessageID, messenger: any MessengerService, onClose: @escaping () -> Void) {
        self.onClose = onClose
        _model = State(initialValue: LiveLocationMapModel(chatID: chatID, focus: focus, messenger: messenger))
    }

    private var positionTask: PositionTask {
        PositionTask(isActive: scenePhase == .active, generation: model.streamGeneration)
    }

    var body: some View {
        U2Screen(background: Color.u2.bg) {
            U2TimeView(interval: 30) { now in
                screen(now)
            }
        }
        .task(id: positionTask) {
            guard positionTask.isActive else { return }
            await model.run()
        }
        // The share went (deleted, the chat closed, the app locked): so does the map.
        .onChange(of: model.isGone, initial: true) { _, isGone in
            if isGone { onClose() }
        }
        .u2Sheet(item: $choice, style: .floating, onDismiss: launchPending) { shown in
            LocationOpenSheet(choice: shown, onChoose: { app in choose(app, for: shown) }, onCancel: { choice = nil })
        }
    }

    private func screen(_ now: Date) -> some View {
        let points = model.points(at: now)
        let peerID = model.peerShare(at: now)?.id
        return ZStack {
            // The header and the card are the map's safe area: Apple's legal
            // label and the framing stay clear of them.
            map(now)
                .safeAreaPadding(EdgeInsets(top: headerHeight, leading: 0, bottom: bottomHeight, trailing: 0))
            VStack(spacing: 0) {
                header(now)
                    .onGeometryChange(for: CGFloat.self) { $0.size.height } action: { headerHeight = $0 }
                Spacer(minLength: 0)
                card(now)
                    .padding(.horizontal, 12)
                    .u2Bottom(36)
                    .onGeometryChange(for: CGFloat.self) { $0.size.height } action: { bottomHeight = $0 }
            }
        }
        .onAppear { moveCamera(to: points, animated: false) }
        .onChange(of: points) { _, new in refit(new) }
        .onChange(of: peerID, initial: true) { _, id in
            if id != nil { peerWasShown = true }
        }
    }

    // MARK: Map

    private func map(_ now: Date) -> some View {
        let peer = model.peerPosition(at: now)
        let own = model.ownPosition(at: now)
        let ownIsLive = model.ownShare(at: now) != nil
        let peerName = model.peerUsername
        let peerPalette = model.peerPalette
        let peerAvatar = model.peerAvatar
        let ownMarkerPicture = ownPicture
        let ownName = model.ownUsername
        let youLabel = StringTable.conversation.string("liveMap.you", locale: locale)
        return Map(position: $camera, interactionModes: [.pan, .zoom]) {
            if let peer {
                if LiveMapFraming.showsAccuracy(peer.accuracy) {
                    MapCircle(center: peer.coordinate, radius: peer.accuracy)
                        .foregroundStyle(Color.u2.accent.opacity(0.12))
                        .stroke(Color.u2.accent.opacity(0.3), lineWidth: 1)
                }
                Annotation(peerName, coordinate: peer.coordinate, anchor: .center) {
                    LiveMapMarker(
                        username: peerName, palette: peerPalette, picture: peerAvatar, pulses: true,
                        label: Text(verbatim: peerName))
                        .accessibilityIdentifier("liveMap.marker.peer")
                }
                .annotationTitles(.hidden)
            }
            if let own {
                if LiveMapFraming.showsAccuracy(own.accuracy) {
                    MapCircle(center: own.coordinate, radius: own.accuracy)
                        .foregroundStyle(Color.u2.accent.opacity(0.12))
                        .stroke(Color.u2.accent.opacity(0.3), lineWidth: 1)
                }
                Annotation(youLabel, coordinate: own.coordinate, anchor: .center) {
                    LiveMapMarker(
                        username: ownName, palette: .d, picture: ownMarkerPicture, pulses: ownIsLive,
                        label: Text(verbatim: youLabel))
                        .accessibilityIdentifier("liveMap.marker.own")
                }
                .annotationTitles(.hidden)
            }
        }
        .mapStyle(.standard(elevation: .flat, emphasis: .muted, pointsOfInterest: .excludingAll, showsTraffic: false))
    }

    private func moveCamera(to points: [LivePosition], animated: Bool) {
        let target: MKCoordinateRegion?
        switch follow {
        case .both: target = LiveMapFraming.region(for: points)
        case .me: target = model.ownPosition(at: clock.now).flatMap { LiveMapFraming.region(for: [$0]) }
        }
        guard let target else { return }
        if animated && !reduceMotion {
            withAnimation(U2Motion.soft(0.6)) { camera = .region(target) }
        } else {
            camera = .region(target)
        }
    }

    /// A tick: the camera follows — unless the user has moved the map.
    private func refit(_ points: [LivePosition]) {
        guard !camera.positionedByUser else { return }
        moveCamera(to: points, animated: true)
    }

    private func fit(_ now: Date) {
        U2Haptics.selection()
        follow = .both
        model.framesDevicePosition = true
        moveCamera(to: model.points(at: now), animated: true)
    }

    private func centerOnMe(_ now: Date) {
        U2Haptics.selection()
        follow = .me
        model.framesDevicePosition = true
        moveCamera(to: model.points(at: now), animated: true)
    }

    // MARK: Header

    private func header(_ now: Date) -> some View {
        let showsCenter = model.ownPosition(at: now) != nil
        return HStack(spacing: 12) {
            U2GlassIconButton(.x, glyphSize: 21, label: Text("a11y.close"), action: onClose)
                .accessibilityIdentifier("liveMap.close")
            VStack(alignment: .leading, spacing: 2) {
                Text(verbatim: model.peerUsername)
                    .u2Font(.monoUsername)
                    .foregroundStyle(Color.u2.text)
                    .lineLimit(1)
                Text(verbatim: subtitle(now))
                    .u2Font(.meta)
                    .foregroundStyle(Color.u2.textSecondary)
                    .lineLimit(1)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .accessibilityElement(children: .combine)
            .accessibilityAddTraits(.isHeader)
            if showsCenter {
                U2GlassIconButton(.navigationArrow, glyphSize: 21, label: Text("liveMap.center", table: .conversation)) {
                    centerOnMe(now)
                }
                .accessibilityIdentifier("liveMap.center")
                .transition(.u2Fade)
            }
            U2GlassIconButton(.scan, glyphSize: 21, label: Text("liveMap.fit", table: .conversation)) {
                fit(now)
            }
            .accessibilityIdentifier("liveMap.fit")
        }
        .padding(.horizontal, 16)
        .u2Top(U2Spacing.headerTopButtons)
        .padding(.bottom, 10)
        .frame(maxWidth: .infinity)
        .background(U2Backdrop(style: .header).ignoresSafeArea(edges: .top))
        .overlay(alignment: .bottom) {
            DottedRule(color: Color.u2.dotRuleHeader)
        }
        .u2Animation(U2Motion.state, value: showsCenter)
    }

    /// “Canlı konum · Son konum 18:51”, “Canlı konum”, or — nothing live any
    /// more — “Canlı konum paylaşımı bitti”.
    private func subtitle(_ now: Date) -> String {
        guard model.isLive(at: now) else { return L10n.string("liveLocation.ended", locale: locale) }
        let live = L10n.string("location.live", locale: locale)
        guard let updated = model.peerShare(at: now)?.position.updatedAt else { return live }
        let time = U2Format.clockTime(updated, locale: locale)
        return live + " · " + StringTable.conversation.string("liveMap.lastUpdate \(time)", locale: locale)
    }

    // MARK: Card

    private func card(_ now: Date) -> some View {
        let peer = model.peerShare(at: now)
        let row = model.ownRow(at: now)
        let showsPeer = peer != nil || peerWasShown
        let showsOwn = row != .hidden
        let shape = RoundedRectangle(cornerRadius: U2Radius.menu, style: .continuous)
        return VStack(spacing: 0) {
            if showsPeer {
                peerRow(peer, now: now)
            }
            if showsPeer && showsOwn {
                DottedRule(color: Color.u2.dotRule)
            }
            if showsOwn {
                ownRow(row, now: now)
            }
            if showsPeer || showsOwn {
                DottedRule(color: Color.u2.dotRule)
            }
            Text("liveMap.tiles", table: .conversation)
                .u2Font(.footnote)
                .foregroundStyle(Color.u2.textSecondary)
                .fixedSize(horizontal: false, vertical: true)
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(EdgeInsets(top: 10, leading: 14, bottom: 12, trailing: 14))
        }
        .frame(maxWidth: .infinity)
        .u2Backdrop(.menu, in: shape)
        .clipShape(shape)
        .u2ShadowMd(cornerRadius: U2Radius.menu)
        .u2Animation(U2Motion.state, value: peer?.id)
        .u2Animation(U2Motion.state, value: row.kind)
    }

    @ViewBuilder private func peerRow(_ peer: LiveShare?, now: Date) -> some View {
        if let peer {
            HStack(spacing: 12) {
                Avatar(username: model.peerUsername, palette: model.peerPalette, size: .header, picture: model.peerAvatar)
                rowText(
                    title: Text(verbatim: model.peerUsername),
                    detail: Text("location.live.remaining \(Self.minutesLeft(until: peer.expiresAt, now: now))"),
                    detailStyle: .monoFileMeta)
                if model.peerPosition(at: now) != nil {
                    U2GlassIconButton(
                        .export, diameter: U2Size.iconButtonSheet, glyphSize: 18,
                        label: Text("location.open.title", table: .conversation)
                    ) {
                        openInMaps(peer)
                    }
                    .accessibilityIdentifier("liveMap.openInMaps")
                }
            }
            .padding(Self.rowPadding)
            .transition(.u2Fade)
        } else {
            // Their share ended while the map was open: the marker is gone.
            HStack(spacing: 6) {
                PhosphorIcon(.navigationArrow, size: 15)
                Text("liveLocation.ended")
                    .u2Font(.bubbleTitleS.weight(400))
                    .lineLimit(2)
                    .fixedSize(horizontal: false, vertical: true)
            }
            .foregroundStyle(Color.u2.textSecondary)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(Self.rowPadding)
            .accessibilityElement(children: .combine)
            .transition(.u2Fade)
        }
    }

    @ViewBuilder private func ownRow(_ row: LiveLocationMapModel.OwnRow, now: Date) -> some View {
        switch row {
        case .sharing(let share):
            HStack(spacing: 12) {
                ownAvatar
                rowText(
                    title: Text("liveMap.sharing", table: .conversation),
                    detail: Text("location.live.remaining \(Self.minutesLeft(until: share.expiresAt, now: now))"),
                    detailStyle: .monoFileMeta)
                Button {
                    stop(share, now: now)
                } label: {
                    Text("liveMap.stop", table: .conversation)
                }
                .buttonStyle(U2InlinePillButtonStyle())
                .accessibilityIdentifier("liveMap.stop")
            }
            .padding(Self.rowPadding)
        case .device:
            HStack(spacing: 12) {
                ownAvatar
                rowText(
                    title: Text("liveMap.you", table: .conversation),
                    detail: Text("liveMap.deviceOnly", table: .conversation), detailStyle: .captionTighter)
            }
            .padding(Self.rowPadding)
        case .locating:
            HStack(spacing: 12) {
                ownAvatar
                rowText(
                    title: Text("liveMap.you", table: .conversation),
                    detail: Text("liveMap.locating", table: .conversation), detailStyle: .captionTighter)
            }
            .padding(Self.rowPadding)
        case .askPermission:
            permissionRow(
                message: Text("liveMap.permission.ask", table: .conversation),
                buttonTitle: Text("liveMap.permission.allow", table: .conversation)
            ) {
                Task { await model.requestAccess() }
            }
        case .denied:
            permissionRow(
                message: Text("liveMap.permission.denied", table: .conversation),
                buttonTitle: Text("liveMap.permission.settings", table: .conversation), perform: openSettings)
        case .hidden:
            EmptyView()
        }
    }

    private var ownAvatar: some View {
        Avatar(username: model.ownUsername, palette: .d, size: .header, picture: ownPicture)
    }

    private func rowText(title: Text, detail: Text, detailStyle: U2TextStyle) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            title
                .u2Font(.rowTitleEmphasis)
                .foregroundStyle(Color.u2.text)
                .lineLimit(1)
            detail
                .u2Font(detailStyle)
                .foregroundStyle(Color.u2.textSecondary)
                .fixedSize(horizontal: false, vertical: true)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .accessibilityElement(children: .combine)
    }

    /// The gentle hint: without location access the map shows only the contact.
    private func permissionRow(message: Text, buttonTitle: Text, perform: @escaping () -> Void) -> some View {
        HStack(spacing: 12) {
            PhosphorIcon(.navigationArrow, size: 20)
                .foregroundStyle(Color.u2.accent300)
                .frame(width: Avatar.Size.header.diameter, height: Avatar.Size.header.diameter)
            message
                .u2Font(.captionTighter)
                .foregroundStyle(Color.u2.textSecondary)
                .fixedSize(horizontal: false, vertical: true)
                .frame(maxWidth: .infinity, alignment: .leading)
            Button(action: perform) { buttonTitle }
                .buttonStyle(U2TextButtonStyle(tone: .accent, height: U2Size.iconButtonSheet, fillsWidth: false))
                .accessibilityIdentifier("liveMap.permission")
        }
        .padding(Self.rowPadding)
    }

    private static func minutesLeft(until end: Date, now: Date) -> Int {
        max(0, Int((end.timeIntervalSince(now) / 60).rounded(.up)))
    }

    // MARK: Actions

    /// “Paylaşımı durdur”: with nothing else live, the map closes too.
    private func stop(_ share: LiveShare, now: Date) {
        U2Haptics.selection()
        let keepsOpen = model.peerShare(at: now) != nil
        let model = model
        Task { await model.stop(share) }
        if !keepsOpen { onClose() }
    }

    private func openInMaps(_ share: LiveShare) {
        guard share.position.isValidCoordinate else { return }
        U2Haptics.selection()
        choice = MapChoice(
            place: MapPlace(name: "", latitude: share.position.latitude, longitude: share.position.longitude),
            hasGoogleMapsApp: MapLauncher.hasGoogleMapsApp, isDisappearing: model.isDisappearing(share))
    }

    private func choose(_ app: MapApp, for shown: MapChoice) {
        U2Haptics.selection()
        pendingLaunch = MapLaunch(app: app, place: shown.place)
        choice = nil
    }

    /// The chooser is down: the chosen app opens now (never over the sheet).
    private func launchPending() {
        guard let launch = pendingLaunch else { return }
        pendingLaunch = nil
        // The app went to the background while the sheet went down: nothing is handed over.
        guard UIApplication.shared.applicationState == .active else { return }
        MapLauncher.open(launch.place, in: launch.app)
    }

    private func openSettings() {
        guard let url = URL(string: UIApplication.openSettingsURLString) else { return }
        openURL(url)
    }
}

/// A person on the live map: the C12 avatar (38) with a 3 pt `bg` ring and
/// the live dot's glow; the 5.2 pulse rings behind while their share runs.
private struct LiveMapMarker: View {
    let username: String
    let palette: AvatarPalette
    var picture: AvatarPicture = .initials
    let pulses: Bool
    let label: Text

    var body: some View {
        ZStack {
            if pulses {
                PulseRings()
            }
            Avatar(username: username, palette: palette, size: .header, picture: picture)
                .u2OuterRing(Color.u2.bg, width: 3, in: Circle())
                .u2BoxShadow(.glowLiveDot, in: Circle())
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(label)
    }
}

extension LivePosition {
    fileprivate var coordinate: CLLocationCoordinate2D {
        CLLocationCoordinate2D(latitude: latitude, longitude: longitude)
    }
}

#Preview("5.2 › Canlı konum haritası") {
    let model = AppModel.preview()
    return LiveLocationMapView(chatID: "mert_k", focus: "m4", messenger: model.messenger, onClose: {})
        .environment(\.locale, model.locale)
        .environment(\.u2Clock, model.clock)
}
