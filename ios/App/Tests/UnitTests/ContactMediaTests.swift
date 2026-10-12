import Foundation
import Testing

@testable import us2us

// The contact screen (5.1 › the person in the header): its tabs, the
// photo / video grid and the file list a page at a time, newest first; saving
// from the viewers (the one-time explanation, what may never leave the app,
// telling the sender) and the “Kaydetti” chip.

@MainActor
private func fixtures() -> DesignFixturesService {
    DesignFixturesService(hasAccount: true, clock: .frozen(at: DesignFixtures.referenceDate), latency: .zero)
}

/// Waits (briefly) for an asynchronous state change.
@MainActor
private func eventually(_ condition: @MainActor () -> Bool) async {
    for _ in 0..<100 where !condition() {
        try? await Task.sleep(for: .milliseconds(10))
    }
}

private let tr = Locale(identifier: "tr")
private let en = Locale(identifier: "en")

@Suite("Contact screen · tabs")
@MainActor
struct ContactTabsTests {
    private func makeModel(_ chat: ChatID = "deniz") -> (ContactMediaModel, DesignFixturesService) {
        let service = fixtures()
        return (ContactMediaModel(chatID: chat, messenger: service, saveNotice: InMemorySaveNoticeMemory()), service)
    }

    @Test func opensOnVerificationAndLoadsNothingYet() {
        let (model, _) = makeModel()
        #expect(model.tab == .verification)
        #expect(ContactMediaModel.Tab.allCases == [.verification, .photos, .documents], "Doğrulama first")
        #expect(!model.page(.visual).hasLoaded)
        #expect(!model.page(.documents).hasLoaded)
        #expect(model.gallery == nil)
    }

    @Test func aTabLoadsItsListTheFirstTimeItShows() async {
        let (model, _) = makeModel()
        model.show(.photos)
        #expect(model.tab == .photos)
        await eventually { model.page(.visual).hasLoaded }
        #expect(model.page(.visual).hasLoaded)
        #expect(!model.page(.documents).hasLoaded, "the other list waits until its tab shows")
        model.show(.documents)
        await eventually { model.page(.documents).hasLoaded }
        #expect(model.page(.documents).hasLoaded)
        model.show(.verification)
        #expect(model.tab == .verification)
        #expect(ContactMediaModel.Tab.verification.mediaKind == nil)
    }

    @Test func eachTabListsOnlyItsKind() async {
        let (model, _) = makeModel("mert_k")
        await model.loadFirstPageIfNeeded(.visual)
        await model.loadFirstPageIfNeeded(.documents)
        let visual = model.items(.visual)
        let documents = model.items(.documents)
        // mert_k: the 5.2 video and 12 older photos and videos; the view-once
        // photo (m6), stickers, the place and the deleted message never.
        #expect(visual.count == 13)
        #expect(visual.contains { $0.id == "m2" })
        #expect(!visual.contains { $0.id == "m6" }, "never view-once")
        #expect(!visual.contains { $0.id == "m5" }, "never a deleted message")
        #expect(visual.allSatisfy { message in
            if case .photo = message.content { return true }
            if case .video = message.content { return true }
            return false
        })
        // The 5.2 PDF and 9 older files.
        #expect(documents.count == 10)
        #expect(documents.first?.id == "m1", "the newest first")
        #expect(documents.allSatisfy { if case .file = $0.content { true } else { false } })
    }

    @Test func aChatWithoutMediaShowsItsEmptyState() async {
        let (model, _) = makeModel("kaan")
        await model.loadFirstPageIfNeeded(.visual)
        await model.loadFirstPageIfNeeded(.documents)
        #expect(model.page(.visual).hasLoaded)
        #expect(model.items(.visual).isEmpty)
        #expect(!model.page(.visual).hasMore)
        #expect(model.items(.documents).isEmpty)
    }

    @Test func aTileOpensTheGalleryOverTheWholeGrid() async throws {
        let (model, _) = makeModel()
        await model.loadFirstPageIfNeeded(.visual)
        let items = model.items(.visual)
        let tile = try #require(items.dropFirst(3).first)
        model.openVisual(tile)
        let gallery = try #require(model.gallery)
        #expect(gallery.startID == tile.id)
        #expect(gallery.pages == .visual)
        #expect(model.galleryPages(gallery).map(\.id) == items.map(\.id), "swipes through the grid, in its order")
        // An older item (not in the conversation) is still found for the viewer.
        #expect(model.message(tile.id)?.id == tile.id)
        model.closeGallery()
        #expect(model.gallery == nil)
    }

    @Test func aVideoFileFromBelgelerPlaysAlone() async throws {
        let (model, _) = makeModel("mert_k")
        await model.loadFirstPageIfNeeded(.documents)
        let movie = try #require(model.items(.documents).first { message in
            if case .file(let name, _, _, _) = message.content { return MediaFileKind(fileName: name) == .movie }
            return false
        })
        model.openDocument(movie)
        let gallery = try #require(model.gallery)
        #expect(gallery.pages == .single)
        #expect(model.galleryPages(gallery).map(\.id) == [movie.id])
    }

    @Test func anItemDeletedMeanwhileLeavesTheList() async {
        let (model, service) = makeModel()
        await model.loadFirstPageIfNeeded(.visual)
        #expect(model.items(.visual).contains { $0.id == "d2" })
        await service.deleteForEveryone("d2", in: "deniz")
        #expect(!model.items(.visual).contains { $0.id == "d2" }, "a deleted photo goes from the grid at once")
    }

    @Test func theListShowsTheCurrentStateOfAnItem() async {
        let (model, service) = makeModel()
        await model.loadFirstPageIfNeeded(.visual)
        service.setMediaAvailability(.downloading(0.5), of: "d2", in: "deniz")
        #expect(model.items(.visual).first { $0.id == "d2" }?.mediaAvailability == .downloading(0.5))
    }

    @Test func disappearingMediaIsNeverListed() async {
        let (model, service) = makeModel()
        await model.loadFirstPageIfNeeded(.visual)
        #expect(model.items(.visual).contains { $0.id == "d2" })
        // Turned disappearing: gone from the grid at once, and from every page read again.
        service.setDisappearing(.fiveMinutes, of: "d2", in: "deniz")
        #expect(!model.items(.visual).contains { $0.id == "d2" }, "a list kept outside the chat never shows it (§8.1)")
        await model.reload(.visual)
        #expect(!model.page(.visual).items.contains { $0.id == "d2" })
        let fetched = await service.media(in: "deniz", kind: .visual, before: nil, limit: 100)
        #expect(!fetched.contains { $0.id == "d2" })
    }

    @Test func aRefreshReadsTheLoadedListsAgain() async throws {
        let (model, service) = makeModel()
        await model.loadFirstPageIfNeeded(.visual)
        await model.loadMore(.visual)
        let loaded = model.page(.visual).items.count
        #expect(loaded > ContactMediaModel.pageSize, "two pages")
        // An older item (outside the open conversation) saved meanwhile.
        let older = try #require(model.page(.visual).items.last)
        await service.markSaved(older.id, in: "deniz")
        #expect(model.items(.visual).last?.savedByMe == false, "the loaded copy is old")
        await model.refreshLoaded()
        #expect(model.page(.visual).items.count == loaded, "as far as it was loaded")
        #expect(model.items(.visual).last?.savedByMe == true)
        #expect(!model.page(.documents).hasLoaded, "a list that never showed is not read")
    }

    @Test func whatOpensClosesWithTheApp() async throws {
        let (model, _) = makeModel()
        await model.loadFirstPageIfNeeded(.visual)
        model.openVisual(try #require(model.items(.visual).first))
        #expect(model.gallery != nil)
        model.closeMedia()
        #expect(model.gallery == nil)
        #expect(model.fileNotice == nil)
    }
}

@Suite("Contact screen · paging")
@MainActor
struct ContactPagingTests {
    private func makeModel() -> (ContactMediaModel, DesignFixturesService) {
        let service = fixtures()
        return (ContactMediaModel(chatID: "deniz", messenger: service, saveNotice: InMemorySaveNoticeMemory()), service)
    }

    @Test func pagesComeNewestFirstWithoutGapsOrRepeats() async {
        let (model, _) = makeModel()
        await model.loadFirstPageIfNeeded(.visual)
        #expect(model.page(.visual).items.count == ContactMediaModel.pageSize)
        #expect(model.page(.visual).hasMore)
        #expect(model.page(.visual).items.first?.id == "d2", "the conversation's photo is the newest")
        await model.loadMore(.visual)
        // deniz: 40 older photos and videos and the 5.1 photo.
        let items = model.page(.visual).items
        #expect(items.count == 41)
        #expect(Set(items.map(\.id)).count == items.count, "no repeats")
        #expect(zip(items, items.dropFirst()).allSatisfy { $0.sentAt >= $1.sentAt }, "newest first")
        #expect(!model.page(.visual).hasMore, "a short page is the last")
        await model.loadMore(.visual)
        #expect(model.page(.visual).items.count == 41, "nothing after the last page")
    }

    @Test func theNextPageLoadsNearTheEndOnly() async {
        let (model, _) = makeModel()
        await model.loadFirstPageIfNeeded(.visual)
        let first = model.page(.visual).items
        model.itemAppeared(first[3].id, in: .visual)
        try? await Task.sleep(for: .milliseconds(80))
        #expect(model.page(.visual).items.count == ContactMediaModel.pageSize, "far from the end: nothing yet")
        model.itemAppeared(first[first.count - ContactMediaModel.prefetchDistance].id, in: .visual)
        await eventually { model.page(.visual).items.count > ContactMediaModel.pageSize }
        #expect(model.page(.visual).items.count == 41)
    }

    @Test func aReloadStartsFromTheNewestAgain() async {
        let (model, service) = makeModel()
        await model.loadFirstPageIfNeeded(.visual)
        await model.loadMore(.visual)
        await service.send(attachment: .photo(.designArtwork(.sunsetVideo), caption: nil), in: "deniz")
        await model.reload(.visual)
        let items = model.page(.visual).items
        #expect(items.count == ContactMediaModel.pageSize)
        #expect(items.first?.isOutgoing == true, "the photo just sent leads")
        #expect(model.page(.visual).hasMore)
    }

    @Test func overlappingPagesAreMergedOnce() {
        let a = Message(id: "a", direction: .incoming, sentAt: Date(), content: .photo(.designArtwork(.nightPier), caption: nil))
        let b = Message(id: "b", direction: .incoming, sentAt: Date(), content: .photo(.designArtwork(.nightPier), caption: nil))
        #expect(ContactMediaModel.unique([a, b, a, b]).map(\.id) == ["a", "b"])
    }

    @Test func thePageFunctionHonoursBeforeAndLimit() {
        let base = DesignFixtures.referenceDate
        let messages = (0..<10).map { index in
            Message(
                id: "p\(index)", direction: .incoming, sentAt: base.addingTimeInterval(Double(index) * 60),
                content: .photo(.designArtwork(.nightPier), caption: nil))
        } + [
            Message(id: "text", direction: .incoming, sentAt: base, content: .text("selam")),
            Message(id: "once", direction: .incoming, sentAt: base, content: .viewOnce(isVideo: false, state: .unopened)),
            Message(id: "gone", direction: .incoming, sentAt: base, content: .deleted),
        ]
        let first = DesignFixtures.mediaPage(messages, kind: .visual, before: nil, limit: 4)
        #expect(first.map(\.id) == ["p9", "p8", "p7", "p6"])
        let next = DesignFixtures.mediaPage(messages, kind: .visual, before: "p6", limit: 4)
        #expect(next.map(\.id) == ["p5", "p4", "p3", "p2"])
        let last = DesignFixtures.mediaPage(messages, kind: .visual, before: "p2", limit: 4)
        #expect(last.map(\.id) == ["p1", "p0"])
        #expect(DesignFixtures.mediaPage(messages, kind: .visual, before: "missing", limit: 4).isEmpty, "a page after something gone is empty")
        #expect(DesignFixtures.mediaPage(messages, kind: .documents, before: nil, limit: 4).isEmpty)
        #expect(DesignFixtures.mediaPage(messages, kind: .visual, before: nil, limit: 0).isEmpty)
    }
}

@Suite("Media · saving from the viewers")
@MainActor
struct MediaSaverTests {
    @MainActor
    final class Recorder {
        var answer = true
        var asked: [(peer: String, kind: SaveNoticeKind)] = []
        var photos: [(payload: MediaPayload, isVideo: Bool)] = []
        var photosResult: MediaSaver.PhotosResult = .saved
        var exported: [URL] = []
        var exportSaves = true
        var shared: [URL] = []
        var onShared: [@MainActor () -> Void] = []
        var deniedShown = 0
    }

    private func makeSaver(
        _ chat: ChatID, memory: InMemorySaveNoticeMemory = InMemorySaveNoticeMemory()
    ) -> (MediaSaver, DesignFixturesService, Recorder) {
        let service = fixtures()
        let recorder = Recorder()
        let saver = MediaSaver(chatID: chat, messenger: service, memory: memory)
        saver.askNotice = { peer, kind, _ in
            recorder.asked.append((peer, kind))
            return recorder.answer
        }
        saver.addToPhotos = { payload, isVideo in
            recorder.photos.append((payload, isVideo))
            return recorder.photosResult
        }
        saver.presentExporter = { url in
            recorder.exported.append(url)
            return recorder.exportSaves
        }
        saver.presentShareSheet = { url, onShared in
            recorder.shared.append(url)
            recorder.onShared.append(onShared)
            return true
        }
        saver.presentPhotosDenied = { _ in recorder.deniedShown += 1 }
        return (saver, service, recorder)
    }

    private func message(_ id: MessageID, in chat: ChatID, _ service: DesignFixturesService) throws -> Message {
        try #require(service.messages(in: chat).first { $0.id == id })
    }

    @Test func theFirstSaveAsksOnceAndKaydetIsRemembered() async throws {
        let memory = InMemorySaveNoticeMemory()
        let (saver, service, recorder) = makeSaver("deniz", memory: memory)
        let photo = try message("d2", in: "deniz", service)
        #expect(await saver.saveToPhotos(photo) == .saved)
        #expect(recorder.asked.count == 1)
        #expect(recorder.asked.first?.peer == "deniz")
        #expect(recorder.asked.first?.kind == .photo)
        #expect(memory.hasAcknowledgedSaveNotice)
        #expect(recorder.photos.count == 1)
        #expect(recorder.photos.first?.isVideo == false)
        #expect(try message("d2", in: "deniz", service).savedByMe, "the messenger was told")
        #expect(await saver.saveToPhotos(photo) == .saved)
        #expect(recorder.asked.count == 1, "never asked again")
        #expect(recorder.photos.count == 2)
    }

    @Test func vazgecSavesNothingAndAsksAgainNextTime() async throws {
        let memory = InMemorySaveNoticeMemory()
        let (saver, service, recorder) = makeSaver("deniz", memory: memory)
        recorder.answer = false
        let photo = try message("d2", in: "deniz", service)
        #expect(await saver.saveToPhotos(photo) == .cancelled)
        #expect(recorder.photos.isEmpty, "nothing was written")
        #expect(!memory.hasAcknowledgedSaveNotice)
        #expect(try !message("d2", in: "deniz", service).savedByMe, "nobody was told")
        recorder.answer = true
        #expect(await saver.saveToPhotos(photo) == .saved)
        #expect(recorder.asked.count == 2, "asked again until “Kaydet”")
    }

    @Test func anAcknowledgedNoticeIsNotShownAgain() async throws {
        let (saver, service, recorder) = makeSaver("deniz", memory: InMemorySaveNoticeMemory(acknowledged: true))
        #expect(await saver.saveToPhotos(try message("d2", in: "deniz", service)) == .saved)
        #expect(recorder.asked.isEmpty)
    }

    @Test func theUsersOwnFileNeverAsks() async throws {
        let (saver, service, recorder) = makeSaver("mert_k")
        let pdf = try message("m1", in: "mert_k", service)
        let url = URL(fileURLWithPath: "/tmp/kira-sozlesmesi.pdf")
        #expect(await saver.exportFile(pdf, from: url) == .saved)
        #expect(recorder.asked.isEmpty, "nobody to tell about one's own file")
        #expect(recorder.exported == [url])
        #expect(try message("m1", in: "mert_k", service).savedByMe)
    }

    @Test func disappearingAndViewOnceMediaNeverLeaveTheApp() async throws {
        let (saver, service, recorder) = makeSaver("mert_k")
        service.setDisappearing(.fiveMinutes, of: "m1", in: "mert_k")
        let pdf = try message("m1", in: "mert_k", service)
        let url = URL(fileURLWithPath: "/tmp/kira-sozlesmesi.pdf")
        #expect(await saver.exportFile(pdf, from: url) == .notAllowed)
        #expect(await saver.shareFile(pdf, from: url) == .notAllowed)
        let viewOnce = try message("m6", in: "mert_k", service)
        #expect(await saver.saveToPhotos(viewOnce) == .notAllowed)
        #expect(recorder.asked.isEmpty)
        #expect(recorder.exported.isEmpty && recorder.shared.isEmpty && recorder.photos.isEmpty)
        #expect(recorder.onShared.isEmpty)
        await service.markSaved("m1", in: "mert_k")
        #expect(try !message("m1", in: "mert_k", service).savedByMe, "the service refuses too")
    }

    @Test func aClosedExporterOrShareSheetTellsNoOne() async throws {
        let (saver, service, recorder) = makeSaver("mert_k")
        let file = service.addIncomingFile(in: "mert_k", availability: .ready)
        let incoming = try message(file, in: "mert_k", service)
        let url = URL(fileURLWithPath: "/tmp/file.pdf")
        recorder.exportSaves = false
        #expect(await saver.exportFile(incoming, from: url) == .cancelled)
        #expect(await saver.shareFile(incoming, from: url) == .presented)
        try? await Task.sleep(for: .milliseconds(50))
        #expect(try !message(file, in: "mert_k", service).savedByMe, "the sheet is up, nothing went anywhere yet")
        // An activity completed (the sheet may stay up and share again).
        let completeShare = try #require(recorder.onShared.first)
        completeShare()
        await eventually { (try? message(file, in: "mert_k", service).savedByMe) == true }
        #expect(try message(file, in: "mert_k", service).savedByMe, "a share that went somewhere is told")
        #expect(recorder.asked.count == 1, "asked before the first export only")
        #expect(recorder.asked.first?.kind == .file)
    }

    @Test func photosRefusedShowsTheWayToSettings() async throws {
        let (saver, service, recorder) = makeSaver("deniz", memory: InMemorySaveNoticeMemory(acknowledged: true))
        recorder.photosResult = .denied
        #expect(await saver.saveToPhotos(try message("d2", in: "deniz", service)) == .denied)
        #expect(recorder.deniedShown == 1)
        #expect(try !message("d2", in: "deniz", service).savedByMe)
    }

    @Test func aVideoIsSavedAsAVideo() async throws {
        let (saver, service, recorder) = makeSaver("mert_k", memory: InMemorySaveNoticeMemory(acknowledged: true))
        #expect(await saver.saveToPhotos(try message("m2", in: "mert_k", service)) == .saved)
        #expect(recorder.photos.first?.isVideo == true)
        #expect(recorder.photos.first?.payload.contentType == "video/mp4")
    }

    @Test func theExplanationNamesWhatIsSaved() {
        func make(_ content: MessageContent) -> Message {
            Message(id: "x", direction: .incoming, sentAt: Date(), content: content)
        }
        #expect(MediaExportPolicy.noticeKind(of: make(.photo(.designArtwork(.nightPier), caption: nil))) == .photo)
        #expect(MediaExportPolicy.noticeKind(of: make(.video(.designArtwork(.sunsetVideo), duration: 3, byteCount: 1))) == .video)
        #expect(MediaExportPolicy.noticeKind(of: make(.file(name: "a.pdf", byteCount: 1, typeLabel: "PDF", pageCount: 1))) == .file)
        #expect(MediaExportPolicy.noticeKind(of: make(.file(name: "a.mov", byteCount: 1, typeLabel: "MOV", pageCount: nil))) == .video)
        #expect(
            StringTable.conversation.string("save.notice.title.photo \("deniz")", locale: tr)
                == "deniz fotoğrafı kaydettiğini görecek")
        #expect(
            StringTable.conversation.string("save.notice.title.photo \("deniz")", locale: en)
                == "deniz will see that you saved it")
        #expect(StringTable.conversation.string("save.notice.confirm", locale: tr) == "Kaydet")
        #expect(StringTable.conversation.string("save.notice.cancel", locale: tr) == "Vazgeç")
    }

    @Test func whatMayLeaveTheApp() {
        func make(_ content: MessageContent, _ availability: MediaAvailability = .ready, disappearing: DisappearingTimer = .off)
            -> Message
        {
            var message = Message(id: "x", direction: .incoming, sentAt: Date(), content: content, disappearing: disappearing)
            message.mediaAvailability = availability
            return message
        }
        let photo = MessageContent.photo(.designArtwork(.nightPier), caption: nil)
        #expect(MediaExportPolicy.allowsExport(make(photo)))
        #expect(MediaExportPolicy.allowsExport(make(photo, .remote)), "downloaded first")
        #expect(!MediaExportPolicy.allowsExport(make(photo, .held)), "a request's media")
        #expect(!MediaExportPolicy.allowsExport(make(photo, .unavailable)))
        #expect(!MediaExportPolicy.allowsExport(make(photo, .preparing)))
        #expect(!MediaExportPolicy.allowsExport(make(photo, disappearing: .oneHour)), "disappearing never")
        #expect(!MediaExportPolicy.allowsExport(make(.viewOnce(isVideo: false, state: .unopened))), "view-once never")
        #expect(!MediaExportPolicy.allowsExport(make(.text("selam"))))
        #expect(!MediaExportPolicy.allowsExport(make(.deleted)))
        #expect(MediaExportPolicy.savesToPhotos(make(.file(name: "a.mov", byteCount: 1, typeLabel: "MOV", pageCount: nil))))
        #expect(!MediaExportPolicy.savesToPhotos(make(.file(name: "a.pdf", byteCount: 1, typeLabel: "PDF", pageCount: 1))))
    }

    @Test func imageFilesOpenInThePhotoViewerNotQuickLook() {
        #expect(MediaFileKind.opensInPhotoViewer(fileName: "kumsal.jpg"))
        #expect(MediaFileKind.opensInPhotoViewer(fileName: "ekran.PNG"))
        #expect(MediaFileKind.opensInPhotoViewer(fileName: "foto.heic"))
        #expect(!MediaFileKind.opensInPhotoViewer(fileName: "plan.svg"), "web content is never previewed")
        #expect(!MediaFileKind.opensInPhotoViewer(fileName: "rapor.pdf"))
        #expect(!MediaFileKind.opensInPhotoViewer(fileName: "klip.mov"))
        #expect(!MediaFileKind.opensInPhotoViewer(fileName: "uzantısız"))
    }

    @Test func thePreferencesRememberTheAcknowledgementUntilReset() {
        let defaults = UserDefaults(suiteName: "us2us.tests.\(UUID().uuidString)")!
        let preferences = AppPreferences(defaults: defaults)
        #expect(!preferences.hasAcknowledgedSaveNotice)
        preferences.hasAcknowledgedSaveNotice = true
        #expect(AppPreferences(defaults: defaults).hasAcknowledgedSaveNotice, "kept across launches")
        #expect(StandardSaveNoticeMemory(defaults: defaults).hasAcknowledgedSaveNotice, "the same preference")
        preferences.reset()
        #expect(!AppPreferences(defaults: defaults).hasAcknowledgedSaveNotice)
    }

    @Test func eraseEverythingForgetsTheAcknowledgement() async {
        var configuration = AppConfiguration()
        configuration.start = .chats
        let defaults = UserDefaults(suiteName: "us2us.tests.\(UUID().uuidString)")!
        let preferences = AppPreferences(defaults: defaults)
        preferences.hasAcknowledgedSaveNotice = true
        let app = AppModel(configuration: configuration, preferences: preferences, messenger: fixtures())
        await app.eraseEverything()
        #expect(!preferences.hasAcknowledgedSaveNotice, "a new account is told again")
    }

    @Test func everyWayTheAccountGoesForgetsTheAcknowledgement() {
        var configuration = AppConfiguration()
        configuration.start = .chats
        let defaults = UserDefaults(suiteName: "us2us.tests.\(UUID().uuidString)")!
        let preferences = AppPreferences(defaults: defaults)
        let app = AppModel(configuration: configuration, preferences: preferences, messenger: fixtures())
        // The wipe after ten wrong codes, outside the lock.
        preferences.hasAcknowledgedSaveNotice = true
        app.dataWipedAfterWrongCodes()
        #expect(!preferences.hasAcknowledgedSaveNotice)
        // Leaving the wiped lock (start over or restore).
        preferences.hasAcknowledgedSaveNotice = true
        app.leaveWipedLock(restoring: true)
        #expect(!preferences.hasAcknowledgedSaveNotice)
        // A restore inside the lock replaced the vault.
        preferences.hasAcknowledgedSaveNotice = true
        app.continueAfterRestore()
        #expect(!preferences.hasAcknowledgedSaveNotice)
        // The wipe on the lock itself.
        preferences.hasAcknowledgedSaveNotice = true
        let lock = LockFlowModel(
            messenger: fixtures(), biometry: .none, onUnlock: {}, onErase: {},
            onWiped: { app.forgetAccountPreferences() })
        lock.dataWiped()
        #expect(!preferences.hasAcknowledgedSaveNotice)
    }
}

@Suite("Media · Kaydetti chip")
@MainActor
struct SavedChipTests {
    private func make(
        _ content: MessageContent, _ direction: MessageDirection = .outgoing, savedByPeer: Bool = true
    ) -> Message {
        var message = Message(id: "x", direction: direction, sentAt: Date(), content: content, status: .read)
        message.savedByPeer = savedByPeer
        return message
    }

    @Test func showsOnTheUsersOwnSavedPhotosVideosAndFilesOnly() {
        #expect(SavedMediaChip.isShown(for: make(.photo(.designArtwork(.nightPier), caption: nil))))
        #expect(SavedMediaChip.isShown(for: make(.video(.designArtwork(.sunsetVideo), duration: 18, byteCount: 1))))
        #expect(SavedMediaChip.isShown(for: make(.file(name: "a.pdf", byteCount: 1, typeLabel: "PDF", pageCount: 1))))
        #expect(!SavedMediaChip.isShown(for: make(.photo(.designArtwork(.nightPier), caption: nil), savedByPeer: false)))
        #expect(!SavedMediaChip.isShown(for: make(.photo(.designArtwork(.nightPier), caption: nil), .incoming)), "never on theirs")
        #expect(!SavedMediaChip.isShown(for: make(.text("selam"))))
        #expect(!SavedMediaChip.isShown(for: make(.voice(duration: 3, waveform: [], playedFraction: 0))))
        #expect(!SavedMediaChip.isShown(for: make(.deleted)), "not on a deleted message's trace")
    }

    @Test func chipsKeepRoomUnderTheBubble() {
        let saved = make(.photo(.designArtwork(.nightPier), caption: nil))
        #expect(saved.showsBubbleChips)
        var reacted = make(.text("selam"), savedByPeer: false)
        #expect(!reacted.showsBubbleChips)
        reacted.reactions = [Reaction(emoji: "❤️")]
        #expect(reacted.showsBubbleChips)
        var both = saved
        both.reactions = [Reaction(emoji: "❤️")]
        #expect(both.showsBubbleChips && SavedMediaChip.isShown(for: both), "the chip and the reaction together")
    }

    @Test func theInfoSheetEndsInSavedOnceSaved() {
        let saved = make(.file(name: "a.pdf", byteCount: 1, typeLabel: "PDF", pageCount: 1))
        #expect(InfoStep.steps(for: saved, showsRead: true).last?.kind == .saved)
        #expect(InfoStep.steps(for: saved, showsRead: false).last?.kind == .saved, "told whatever read receipts say")
        let notSaved = make(.file(name: "a.pdf", byteCount: 1, typeLabel: "PDF", pageCount: 1), savedByPeer: false)
        #expect(!InfoStep.steps(for: notSaved, showsRead: true).contains { $0.kind == .saved })
    }

    @Test func theChipSpeaksBothLanguages() {
        #expect(StringTable.conversation.string("bubble.saved", locale: tr) == "Kaydetti")
        #expect(StringTable.conversation.string("bubble.saved", locale: en) == "Saved")
        #expect(StringTable.conversation.string("bubble.saved.a11y", locale: tr) == "Karşı taraf kaydetti")
        #expect(StringTable.conversation.string("bubble.saved.a11y", locale: en) == "They saved this")
        #expect(StringTable.conversation.string("viewer.save", locale: tr) == "Kaydet")
        #expect(StringTable.contact.string("tab.verification", locale: tr) == "Doğrulama")
        #expect(StringTable.contact.string("tab.photos", locale: en) == "Photos")
        #expect(StringTable.contact.string("tab.documents", locale: tr) == "Belgeler")
    }
}
