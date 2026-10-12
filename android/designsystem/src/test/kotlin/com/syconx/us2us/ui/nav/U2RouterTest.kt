package com.syconx.us2us.ui.nav

import com.syconx.us2us.domain.nav.AppRoute
import com.syconx.us2us.domain.nav.ChatId
import com.syconx.us2us.domain.nav.RequestId
import com.syconx.us2us.domain.nav.SettingsPicker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The router's path rules (iOS `NavigationTests.testRouterPushPopReplace`) and the swipe thresholds. */
class U2RouterTest {
    private val requests = AppRoute.Requests
    private val preview = AppRoute.RequestPreview(RequestId("kuzey_dev"))
    private val chat = AppRoute.Conversation(ChatId("kuzey_dev"))

    @Test
    fun pushPopReplace() {
        val router = U2Router<AppRoute>()
        assertTrue(router.isAtRoot)
        router.push(requests)
        router.push(preview)
        assertEquals(listOf(requests, preview), router.path)
        router.replaceTop(chat)
        assertEquals(listOf(requests, chat), router.path)
        router.pop()
        assertEquals(requests, router.top)
        router.popToRoot()
        assertTrue(router.isAtRoot)
        router.setPath(listOf(AppRoute.Settings, AppRoute.Picker(SettingsPicker.Theme)))
        assertEquals(2, router.path.size)
    }

    @Test
    fun aPushIsStagedOnThePathAtOnceAndRevealedWithAnimation() {
        val router = U2Router<AppRoute>()
        router.push(requests)
        assertEquals(requests, router.top)
        assertTrue(router.entries.single().isStaged)
        assertFalse("staging swaps in one frame", router.animatesChange)
        router.revealStaged()
        assertFalse(router.entries.single().isStaged)
        assertTrue("the reveal plays the push", router.animatesChange)
    }

    @Test
    fun anAnimatedReplaceKeepsTheOldScreenUntilTheNewOneMovesIn() {
        val router = U2Router(listOf<AppRoute>(requests, preview))
        router.replaceTop(chat)
        assertEquals(listOf(requests, chat), router.path)
        assertEquals(3, router.entries.size)
        assertTrue(router.entries[1].isLeaving)
        assertTrue(router.entries[2].isStaged)
        router.revealStaged()
        assertEquals(listOf(requests, chat), router.entries.map { it.route })
        assertTrue(router.animatesChange)
    }

    @Test
    fun aReplaceWithoutAnimationSwapsInOneFrame() {
        val router = U2Router(listOf<AppRoute>(requests, preview))
        router.replaceTop(chat, animated = false)
        assertEquals(listOf(requests, chat), router.entries.map { it.route })
        assertFalse(router.entries.any { it.isStaged || it.isLeaving })
        assertFalse(router.animatesChange)
    }

    @Test
    fun popsAnimateAndSetPathDoesNot() {
        val router = U2Router(listOf<AppRoute>(requests, preview))
        router.pop()
        assertTrue(router.animatesChange)
        router.setPath(listOf(AppRoute.Settings))
        assertFalse(router.animatesChange)
        router.finishInteractivePop()
        assertFalse("the end of a swipe swaps in one frame", router.animatesChange)
        assertTrue(router.isAtRoot)
        router.pop()
        assertTrue(router.isAtRoot)
        assertNull(router.top)
    }

    @Test
    fun popToARouteBelowTheTop() {
        val router = U2Router(listOf<AppRoute>(AppRoute.Settings, AppRoute.BackupExport, AppRoute.Picker(SettingsPicker.Theme)))
        router.pop(to = AppRoute.Settings)
        assertEquals(listOf<AppRoute>(AppRoute.Settings), router.path)
        router.pop(to = AppRoute.Settings) // the top itself: nothing happens
        assertEquals(listOf<AppRoute>(AppRoute.Settings), router.path)
        router.pop(to = AppRoute.Requests) // not on the path: nothing happens
        assertEquals(listOf<AppRoute>(AppRoute.Settings), router.path)
    }

    @Test
    fun entryIdsAreUnique() {
        val router = U2Router<AppRoute>()
        router.push(requests)
        router.pop()
        router.push(requests)
        router.push(preview)
        assertEquals(2, router.entries.map { it.id }.toSet().size)
        val first = router.entries.first().id
        router.setPath(listOf(requests))
        assertTrue(router.entries.single().id > first)
    }

    @Test
    fun layerMotionIsAChain() {
        val outer = U2LayerMotion()
        val inner = U2LayerMotion(parent = outer)
        assertTrue(inner.isSettled)
        outer.updateMoving(true)
        assertFalse("an enclosing stack moving unsettles the screens on it", inner.isSettled)
        outer.updateMoving(false)
        inner.updateMoving(true)
        assertFalse(inner.isSettled)
        assertTrue(outer.isSettled)
    }

    @Test
    fun edgeSwipeCommitThresholds() {
        // Past 0.35 of the width, or a fling projected past 0.6.
        assertFalse(SoftNavigation.commits(progress = 0.35f, projected = 0.35f))
        assertTrue(SoftNavigation.commits(progress = 0.36f, projected = 0.36f))
        assertFalse(SoftNavigation.commits(progress = 0.1f, projected = 0.6f))
        assertTrue(SoftNavigation.commits(progress = 0.1f, projected = 0.61f))
        // UIKit's projection at the normal deceleration rate: about half a second of the release velocity.
        assertEquals(100f + 499f, SoftNavigation.projectedEnd(100f, 1000f), 0.5f)
        assertEquals(100f, SoftNavigation.projectedEnd(100f, 0f), 0.0001f)
        assertEquals(28f, SoftNavigation.EdgeWidth.value, 0f)
        assertEquals(64f, SoftNavigation.SwipeTravel.value, 0f)
        assertEquals(16f, SoftNavigation.PushDrift.value, 0f)
    }
}
