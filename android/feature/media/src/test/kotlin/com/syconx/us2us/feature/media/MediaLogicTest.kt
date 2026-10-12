package com.syconx.us2us.feature.media

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import com.syconx.us2us.domain.DesignClock
import com.syconx.us2us.domain.LivePosition
import com.syconx.us2us.domain.OwnPositionState
import com.syconx.us2us.domain.design.DesignFixtures
import com.syconx.us2us.domain.nav.ChatId
import com.syconx.us2us.domain.nav.MessageId
import com.syconx.us2us.fixtures.FixturesMessengerService
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.time.Duration
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The media screens' rules on their own (iOS `MediaTests`, `LocationTests`): the viewer's zoom and
 * swipe-away arithmetic, what the file preview shows, the map links, the live map's framing and
 * state, the top bar's time.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class MediaLogicTest {
    // MARK: Viewer geometry

    @Test
    fun aPhotoFitsTheScreenWhole() {
        assertEquals(Size(402f, 268f), ZoomMath.fittedSize(Size(3_000f, 2_000f), Size(402f, 874f)))
        val portrait = ZoomMath.fittedSize(Size(1_000f, 4_000f), Size(402f, 874f))
        assertEquals(874f, portrait.height, 0.001f)
        assertEquals(218.5f, portrait.width, 0.001f)
        assertEquals(Size.Zero, ZoomMath.fittedSize(Size.Zero, Size(402f, 874f)))
    }

    @Test
    fun doubleTapZoomsAroundTheTappedPoint() {
        // The tapped point ends up in the middle.
        val offset = ZoomMath.centeredOffset(Offset.Zero, Offset(100f, 50f), 1f, ZoomMath.DOUBLE_TAP_SCALE)
        assertEquals(Offset(-250f, -125f), offset)
        // A pinch keeps the point under the fingers where it is.
        val pinched = ZoomMath.zoomedOffset(Offset.Zero, Offset(100f, 50f), 1f, 2f)
        assertEquals(Offset(-100f, -50f), pinched)
        val content = (Offset(100f, 50f) - pinched) / 2f
        assertEquals(Offset(100f, 50f), pinched + content * 2f)
    }

    @Test
    fun panningStaysOnThePhoto() {
        val fitted = Size(1_206f, 804f)
        val bounds = Size(1_206f, 2_622f)
        assertEquals(Offset.Zero, ZoomMath.maxOffset(fitted, bounds, 1f))
        assertEquals(Offset(904.5f, 0f), ZoomMath.maxOffset(fitted, bounds, 2.5f))
        assertEquals(Offset(904.5f, 0f), ZoomMath.clampOffset(Offset(5_000f, 300f), fitted, bounds, 2.5f))
    }

    @Test
    fun zoomGoesAtLeastToFourTimesAndFurtherForDetailedPhotos() {
        // A 2048 px photo fitted 1206 px wide: 1.7× its pixels → 4×.
        assertEquals(4f, ZoomMath.maximumZoom(2_048f, 1_206f))
        assertEquals(4_096f / 1_206f * 2f, ZoomMath.maximumZoom(4_096f, 1_206f), 0.001f)
        assertEquals(10f, ZoomMath.maximumZoom(40_000f, 1_206f))
        assertEquals(4f, ZoomMath.maximumZoom(160f, 0f))
    }

    @Test
    fun swipeAwayClosesPastTheDistanceOrOnAFlick() {
        assertTrue(MediaDismissGesture.shouldDismiss(120f, 0f))
        assertTrue("up works too", MediaDismissGesture.shouldDismiss(-130f, -100f))
        assertFalse(MediaDismissGesture.shouldDismiss(60f, 300f))
        assertTrue("a flick", MediaDismissGesture.shouldDismiss(40f, 1_200f))
        assertFalse("flicked back settles", MediaDismissGesture.shouldDismiss(150f, -1_500f))
        assertEquals(0f, MediaDismissGesture.progress(0f, 874f))
        assertEquals(1f, MediaDismissGesture.progress(2_000f, 874f))
        assertEquals(0.9f, MediaDismissGesture.scale(1f), 0.0001f)
        assertEquals(0.15f, MediaDismissGesture.backdropOpacity(1f), 0.0001f)
        assertEquals(1f, MediaDismissGesture.backdropOpacity(-1f))
        assertEquals(0f, MediaDismissGesture.chromeOpacity(0.5f))
    }

    @Test
    fun theTopBarSaysTodayOrTheDay() {
        val zone = ZoneId.of("Europe/Istanbul")
        val now = ZonedDateTime.of(2026, 10, 8, 18, 52, 0, 0, zone).toInstant()
        val today = ZonedDateTime.of(2026, 10, 8, 18, 40, 0, 0, zone).toInstant()
        assertEquals("Bugün 18:40", MediaViewerTime.line(today, now, Locale.forLanguageTag("tr-TR"), "Bugün %s", zone))
        assertEquals("Today 18:40", MediaViewerTime.line(today, now, Locale.US, "Today %s", zone))
        val earlier = ZonedDateTime.of(2026, 10, 6, 9, 5, 0, 0, zone).toInstant()
        assertEquals("6 Eki 09:05", MediaViewerTime.line(earlier, now, Locale.forLanguageTag("tr-TR"), "Bugün %s", zone))
    }

    // MARK: Files

    @Test
    fun thePreviewShowsDocumentsButNeverWebContent() {
        for (name in listOf("kira.pdf", "foto.jpg", "not.txt", "tablo.csv", "ses.m4a", "NOTLAR.MD")) {
            assertTrue(name, FilePreviewPolicy.allowsPreview(name))
        }
        for (name in listOf("sayfa.html", "sayfa.htm", "veri.xml", "logo.svg", "arsiv.webarchive", "betik.js", "bilinmeyen.xyz", "uzantisiz", "video.mp4", "rapor.docx")) {
            assertFalse(name, FilePreviewPolicy.allowsPreview(name))
        }
        assertEquals(FilePreviewKind.Pdf, FilePreviewPolicy.kind("kira-sozlesmesi.pdf"))
        assertEquals(FilePreviewKind.Audio, FilePreviewPolicy.kind("ses.m4a"))
        assertEquals(FilePreviewKind.Text, FilePreviewPolicy.kind("tablo.csv"))
    }

    // MARK: Map links

    private val moda = MapPlace("Moda İskelesi", 40.9846, 29.0249)

    @Test
    fun coordinatesAlwaysUseAPoint() {
        val default = Locale.getDefault()
        Locale.setDefault(Locale.forLanguageTag("tr-TR"))
        try {
            assertEquals("40.984600", MapLinks.coordinate(40.9846))
            assertEquals("-0.500000", MapLinks.coordinate(-0.5))
            assertEquals("40.984600,29.024900", MapLinks.coordinatePair(moda))
            assertEquals("40.98460, 29.02490", MapLinks.displayCoordinates(moda))
        } finally {
            Locale.setDefault(default)
        }
    }

    @Test
    fun aMapsAppGetsThePinAndTheName() {
        assertEquals("geo:40.984600,29.024900?q=40.984600,29.024900(Moda%20%C4%B0skelesi)", MapLinks.geo(moda))
        assertEquals("geo:41.008200,28.978400?q=41.008200,28.978400", MapLinks.geo(MapPlace("  ", 41.0082, 28.9784)))
        assertTrue(MapLinks.geo(MapPlace("Kafe & Bar + 1", 1.0, 2.0)).endsWith("(Kafe%20%26%20Bar%20%2B%201)"))
    }

    @Test
    fun googleAndOpenStreetMapGetOnlyTheCoordinates() {
        val google = MapLinks.googleMaps(moda)
        assertEquals("https://www.google.com/maps/search/?api=1&query=40.984600,29.024900", google)
        assertFalse(google.contains("Moda"))
        val osm = MapLinks.openStreetMap(moda)
        assertEquals("https://www.openstreetmap.org/?mlat=40.984600&mlon=29.024900#map=17/40.984600/29.024900", osm)
    }

    // MARK: Live map framing

    private val pier = LivePosition(40.9846, 29.0249)
    private val street = LivePosition(40.9872, 29.0262)

    @Test
    fun nothingToFrameWithoutPoints() {
        assertNull(LiveMapFraming.region(emptyList()))
    }

    @Test
    fun bothPeopleAreInViewWithRoomAround() {
        val region = LiveMapFraming.region(listOf(pier, street))!!
        assertEquals(40.9859, region.centerLatitude, 1e-9)
        assertEquals(29.02555, region.centerLongitude, 1e-9)
        for (point in listOf(pier, street)) {
            assertTrue(abs(point.latitude - region.centerLatitude) < region.latitudeDelta / 2)
            assertTrue(abs(point.longitude - region.centerLongitude) < region.longitudeDelta / 2)
        }
        assertTrue(region.latitudeDelta >= (street.latitude - pier.latitude) * LiveMapFraming.LATITUDE_MARGIN - 1e-12)
    }

    @Test
    fun aSinglePointShowsItsStreet() {
        val region = LiveMapFraming.region(listOf(pier))!!
        assertEquals(pier.latitude, region.centerLatitude, 0.0)
        val high = region.latitudeDelta * 111_320
        val wide = region.longitudeDelta * 111_320 * cos(pier.latitude * PI / 180)
        assertEquals(LiveMapFraming.MINIMUM_SPAN_METERS, high, 0.5)
        assertEquals(LiveMapFraming.MINIMUM_SPAN_METERS, wide, 0.5)
    }

    @Test
    fun pointsEitherSideOfTheDateLineStayNeighbours() {
        val region = LiveMapFraming.region(listOf(LivePosition(-16.5, 179.999), LivePosition(-16.5, -179.999)))!!
        assertTrue(abs(abs(region.centerLongitude) - 180) < 0.01)
        assertTrue(region.longitudeDelta < 1)
    }

    @Test
    fun positionsThatAreNoPlaceAreLeftOut() {
        val region = LiveMapFraming.region(listOf(pier, LivePosition(Double.NaN, 10.0), LivePosition(95.0, Double.POSITIVE_INFINITY)))!!
        assertEquals(pier.latitude, region.centerLatitude, 0.0)
        assertNull(LiveMapFraming.region(listOf(LivePosition(Double.POSITIVE_INFINITY, 0.0))))
    }

    @Test
    fun accuracyCirclesOnlyWhenTheyMeanSomething() {
        assertFalse(LiveMapFraming.showsAccuracy(0.0))
        assertTrue(LiveMapFraming.showsAccuracy(12.0))
        assertFalse(LiveMapFraming.showsAccuracy(5_000.0))
        assertFalse(LiveMapFraming.showsAccuracy(Double.NaN))
    }

    @Test
    fun theCameraFramesTheRegionBetweenTheHeaderAndTheCard() {
        val camera = MapCamera()
        val region = LiveMapFraming.region(listOf(pier, street))!!
        val target = camera.target(region, widthDp = 402f, heightDp = 874f, topDp = 110f, bottomDp = 260f)
        camera.latitude = target.first
        camera.longitude = target.second
        camera.metersPerDp = target.third
        val centre = camera.project(region.centerLatitude, region.centerLongitude, 402f, 874f)
        assertEquals(402f / 2, centre.x, 0.01f)
        assertEquals(110f + (874f - 110f - 260f) / 2, centre.y, 0.01f)
        for (point in listOf(pier, street)) {
            val projected = camera.project(point.latitude, point.longitude, 402f, 874f)
            assertTrue(projected.y in 110f..(874f - 260f))
            assertTrue(projected.x in 0f..402f)
        }
        // A pan moves the map with the finger; a pinch keeps the point under the fingers.
        val before = camera.project(pier.latitude, pier.longitude, 402f, 874f)
        camera.gesture(10f, 20f, 1f, Offset.Zero)
        val after = camera.project(pier.latitude, pier.longitude, 402f, 874f)
        assertEquals(before.x + 10f, after.x, 0.01f)
        assertEquals(before.y + 20f, after.y, 0.01f)
        assertTrue(camera.positionedByUser)
        val focus = after - Offset(201f, 437f)
        camera.gesture(0f, 0f, 2f, focus)
        val zoomed = camera.project(pier.latitude, pier.longitude, 402f, 874f)
        assertEquals(after.x, zoomed.x, 0.05f)
        assertEquals(after.y, zoomed.y, 0.05f)
    }

    @Test
    fun theDotsAreANiceDistanceApart() {
        assertEquals(20.0, MapGrid.step(1.0), 1e-9)
        assertEquals(1.0, MapGrid.step(0.05), 1e-9)
        assertEquals(500.0, MapGrid.step(20.0), 1e-9)
        for (scale in listOf(0.3, 1.7, 13.0, 222.0)) assertTrue(MapGrid.step(scale) / scale >= 18)
    }

    // MARK: Live map state

    private val mert = ChatId("mert_k")
    private val now = DesignFixtures.referenceDate

    private fun service() = FixturesMessengerService(hasAccount = true, clock = DesignClock.frozen(DesignClock.referenceDate), latency = Duration.ZERO)

    @Test
    fun bothPeopleAreOnTheMap() {
        val service = service()
        service.startDemoLiveLocation(mert)
        val map = LiveLocationMapModel(mert, MessageId("m4"), service)
        assertEquals("mert_k", map.peerUsername)
        assertEquals(DesignFixtures.peerFixturePosition, map.peerPosition(now))
        assertEquals("the position last shared, until the phone reports", DesignFixtures.ownFixturePosition.latitude, map.ownPosition(now)!!.latitude, 1e-9)
        assertEquals(2, map.points(now).size)
        assertEquals(MessageId("m4"), (map.ownRow(now) as LiveLocationMapModel.OwnRow.Sharing).share.id)
        val phone = LivePosition(40.9875, 29.0270, accuracy = 8.0)
        map.receive(OwnPositionState.Position(phone))
        assertEquals("this device's own position comes first", phone, map.ownPosition(now))
        assertTrue(map.isLive(now))
        assertFalse(map.isGone)
    }

    @Test
    fun withoutLocationAccessOnlyTheContactShows() = runBlocking {
        val service = service()
        val peer = service.startDemoLiveLocation(mert)
        service.stopLiveLocation(MessageId("m4"), mert)
        val map = LiveLocationMapModel(mert, peer, service)
        map.receive(OwnPositionState.Denied)
        assertNull(map.ownPosition(now))
        assertEquals(listOf(DesignFixtures.peerFixturePosition), map.points(now))
        assertEquals(LiveLocationMapModel.OwnRow.Denied, map.ownRow(now))
        map.receive(OwnPositionState.NeedsPermission)
        assertEquals(LiveLocationMapModel.OwnRow.AskPermission, map.ownRow(now))
        map.receive(OwnPositionState.Locating)
        assertEquals(LiveLocationMapModel.OwnRow.Locating, map.ownRow(now))
        map.receive(OwnPositionState.Position(DesignFixtures.ownFixturePosition))
        assertEquals(LiveLocationMapModel.OwnRow.Device, map.ownRow(now))
        map.receive(OwnPositionState.Unavailable)
        assertEquals(LiveLocationMapModel.OwnRow.Hidden, map.ownRow(now))
    }

    @Test
    fun thisPhonesPositionIsFramedOnlyWhenAskedFor() = runBlocking {
        val service = service()
        val peer = service.startDemoLiveLocation(mert)
        service.stopLiveLocation(MessageId("m4"), mert)
        val map = LiveLocationMapModel(mert, peer, service)
        val phone = LivePosition(40.9875, 29.0270, accuracy = 8.0)
        map.receive(OwnPositionState.Position(phone))
        assertEquals("the marker is drawn", phone, map.ownPosition(now))
        assertEquals(listOf(DesignFixtures.peerFixturePosition), map.points(now))
        map.framesDevicePosition = true
        assertEquals(listOf(DesignFixtures.peerFixturePosition, phone), map.points(now))
    }

    @Test
    fun anEndedShareLeavesTheMapAndAGoneOneClosesIt() = runBlocking {
        val service = service()
        val peer = service.startDemoLiveLocation(mert)
        val map = LiveLocationMapModel(mert, peer, service)
        service.stopLiveLocation(peer, mert)
        service.stopLiveLocation(MessageId("m4"), mert)
        assertNull(map.peerShare(now))
        assertFalse(map.isLive(now))
        assertFalse("its bubble is still there", map.isGone)
        val own = LiveLocationMapModel(mert, MessageId("m4"), service)
        service.deleteForMe(MessageId("m4"), mert)
        assertTrue(own.isGone)
    }

    @Test
    fun minutesLeftRoundUp() {
        assertEquals(1, LiveLocationMapModel.minutesLeft(now.plusSeconds(1), now))
        assertEquals(38, LiveLocationMapModel.minutesLeft(now.plusSeconds(37 * 60 + 2), now))
        assertEquals(0, LiveLocationMapModel.minutesLeft(now.minusSeconds(60), now))
    }
}
