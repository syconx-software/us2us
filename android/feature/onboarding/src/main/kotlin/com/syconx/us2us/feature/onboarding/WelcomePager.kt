package com.syconx.us2us.feature.onboarding

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToDownIgnoreConsumed
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.pageLeft
import androidx.compose.ui.semantics.pageRight
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.syconx.us2us.domain.DesignClock
import com.syconx.us2us.domain.MessageStatus
import com.syconx.us2us.feature.onboarding.R as OnboardingR
import com.syconx.us2us.scene3d.WelcomeScene
import com.syconx.us2us.ui.R
import com.syconx.us2us.ui.bubbles.MessageStatusLegendRow
import com.syconx.us2us.ui.components.DotGridBackdrop
import com.syconx.us2us.ui.components.DotGridMask
import com.syconx.us2us.ui.components.DotPattern
import com.syconx.us2us.ui.components.DottedHourMeter
import com.syconx.us2us.ui.components.DottedRule
import com.syconx.us2us.ui.components.LocalU2LabelStyle
import com.syconx.us2us.ui.components.MonoKicker
import com.syconx.us2us.ui.components.PageDots
import com.syconx.us2us.ui.components.U2PrimaryButton
import com.syconx.us2us.ui.components.U2TextButton
import com.syconx.us2us.ui.components.U2TextButtonTone
import com.syconx.us2us.ui.components.U2Wordmark
import com.syconx.us2us.ui.components.currentLabelColor
import com.syconx.us2us.ui.components.text.U2DisplayText
import com.syconx.us2us.ui.env.LocalU2Clock
import com.syconx.us2us.ui.env.U2TimeView
import com.syconx.us2us.ui.env.rememberU2Now
import com.syconx.us2us.ui.env.u2Bottom
import com.syconx.us2us.ui.env.u2Top
import com.syconx.us2us.ui.icons.PhosphorGlyph
import com.syconx.us2us.ui.icons.PhosphorIcon
import com.syconx.us2us.ui.l10n.U2Format
import com.syconx.us2us.ui.l10n.currentAppLocale
import com.syconx.us2us.ui.motion.LocalReduceMotion
import com.syconx.us2us.ui.motion.U2Motion
import com.syconx.us2us.ui.motion.u2Spec
import com.syconx.us2us.ui.nav.LocalIsLayerVisible
import com.syconx.us2us.ui.nav.LocalLayerMotion
import com.syconx.us2us.ui.nav.SoftNavigation
import com.syconx.us2us.ui.screen.U2Screen
import com.syconx.us2us.ui.theme.U2Theme
import com.syconx.us2us.ui.tokens.U2Spacing
import com.syconx.us2us.ui.tokens.U2TextStyles
import com.syconx.us2us.ui.type.U2Text
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toKotlinDuration

/**
 * 01 · Karşılama — the three welcome pages (1.1 → 1.3).
 *
 * The pages cross-fade with a 16 dp drift (0.5 s, the design curve) — by button or by a horizontal
 * swipe that follows the finger and settles without bounce. The chrome is shared: the wordmark
 * stays, the pager label and the CTA label cross-fade, the active page dot morphs (0.36 s), and the
 * bottom block glides between its 1.1 position (with “Yedekten geri yükle”) and its 1.2/1.3
 * position. Reduce motion: fades only.
 */
@Composable
internal fun WelcomePager(pager: WelcomePagerModel, onCreateAccount: () -> Unit, onRestore: () -> Unit) {
    val reduceMotion = LocalReduceMotion.current
    val isLayerVisible = LocalIsLayerVisible.current
    val enclosingMotion = LocalLayerMotion.current
    SideEffect {
        pager.motion.parent = enclosingMotion
        pager.reduceMotion = reduceMotion
    }
    // Android's back steps back through the pages (the first page leaves it to the system).
    BackHandler(enabled = isLayerVisible && pager.page > 0) { pager.go(pager.page - 1) }

    U2Screen {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val width = constraints.maxWidth.toFloat()
            Box(
                Modifier
                    .fillMaxSize()
                    .welcomeSwipe(pager, width)
                    .semantics {
                        pageRight { pager.go(pager.page + 1); true }
                        pageLeft { pager.go(pager.page - 1); true }
                    },
            ) {
                WelcomePage(0, pager, isLayerVisible) { WelcomeKarsilamaPage() }
                WelcomePage(1, pager, isLayerVisible) { WelcomeNoNotificationsPage() }
                WelcomePage(2, pager, isLayerVisible) { WelcomeSilenceErasesPage() }
                WelcomeChrome(pager, onCreateAccount, onRestore)
            }
        }
    }
}

/**
 * One page at its distance from the drawn position: cross-fade + 16 dp drift (flows.md: “cross-fade
 * + 16 pt drift, 0.5 s curveSoft”); reduce motion fades only. Pages off screen pause their work.
 */
@Composable
private fun WelcomePage(index: Int, pager: WelcomePagerModel, isLayerVisible: Boolean, content: @Composable () -> Unit) {
    val reduceMotion = LocalReduceMotion.current
    val isVisible by remember(pager) { derivedStateOf { pageIsVisible(index, pager.position) } }
    val isCurrent = index == pager.page
    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer {
                val distance = pageDistance(index, pager.position)
                alpha = 1f - abs(distance)
                translationX = if (reduceMotion) 0f else 16.dp.toPx() * distance
            }
            .then(if (isCurrent) Modifier else Modifier.clearAndSetSemantics {}),
    ) {
        CompositionLocalProvider(
            LocalIsLayerVisible provides (isLayerVisible && isVisible),
            LocalLayerMotion provides pager.motion,
        ) {
            content()
        }
    }
}

// MARK: - Chrome

@Composable
private fun WelcomeChrome(pager: WelcomePagerModel, onCreateAccount: () -> Unit, onRestore: () -> Unit) {
    val reduceMotion = LocalReduceMotion.current
    val primary = {
        if (pager.page < WelcomePagerModel.PAGE_COUNT - 1) pager.advance() else onCreateAccount()
    }
    Column(Modifier.fillMaxSize()) {
        WelcomeHeader(pager.page + 1)
        Spacer(Modifier.weight(1f))
        if (reduceMotion) {
            // Nothing moves: the 1.1 block and the 1.2/1.3 block cross-fade in place. The block shown
            // lies on top and the other one takes no touch (its invisible button over “Yedekten geri
            // yükle” must not page on).
            Box(contentAlignment = Alignment.BottomCenter) {
                val first by animateFloatAsState(if (pager.page == 0) 1f else 0f, u2Spec(U2Motion.screen), label = "welcomeBlock")
                Box(Modifier.zIndex(if (pager.page == 0) 1f else 0f).graphicsLayer { alpha = first }.then(if (pager.page == 0) Modifier else Modifier.inactiveBlock())) {
                    WelcomeBottomBlock(pager, showsRestore = true, isActive = pager.page == 0, onPrimary = primary, onRestore = onRestore)
                }
                Box(Modifier.zIndex(if (pager.page != 0) 1f else 0f).graphicsLayer { alpha = 1f - first }.then(if (pager.page != 0) Modifier else Modifier.inactiveBlock())) {
                    WelcomeBottomBlock(pager, showsRestore = false, isActive = pager.page != 0, onPrimary = primary, onRestore = onRestore)
                }
            }
        } else {
            // The active dot morphs and the block glides between the 1.1 and the 1.2/1.3 positions while the restore button fades.
            WelcomeBottomBlock(pager, showsRestore = pager.page == 0, isActive = true, onPrimary = primary, onRestore = onRestore)
        }
    }
}

/**
 * The block that is fading out (or hidden): out of the accessibility tree, and its buttons take no
 * tap (presses and releases are swallowed; a drag still pages).
 */
private fun Modifier.inactiveBlock(): Modifier = clearAndSetSemantics {}.pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) {
            awaitPointerEvent(PointerEventPass.Initial).changes.forEach { change ->
                if (change.changedToDownIgnoreConsumed() || change.changedToUpIgnoreConsumed()) change.consume()
            }
        }
    }
}

/** C5 with the pager label cross-fading between pages (the wordmark stays). */
@Composable
private fun WelcomeHeader(page: Int) {
    val description = stringResource(R.string.common_a11y_pager, page, WelcomePagerModel.PAGE_COUNT)
    Row(
        Modifier
            .fillMaxWidth()
            .u2Top(U2Spacing.onboardingHeaderTop)
            .padding(horizontal = U2Spacing.screenGutterL)
            .testTag(OnboardingTags.HEADER),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        U2Wordmark()
        Spacer(Modifier.weight(1f))
        Crossfade(targetState = page, animationSpec = softSpec(U2Motion.screen), label = "welcomePager") { shown ->
            U2Text(
                "$shown / ${WelcomePagerModel.PAGE_COUNT}",
                U2TextStyles.monoMeta,
                color = U2Theme.colors.textSecondary,
                modifier = Modifier.clearAndSetSemantics { contentDescription = description },
            )
        }
    }
}

/** `isActive`: under reduce motion two blocks cross-fade; only the one shown carries the CTA's tag. */
@Composable
private fun WelcomeBottomBlock(
    pager: WelcomePagerModel,
    showsRestore: Boolean,
    isActive: Boolean,
    onPrimary: () -> Unit,
    onRestore: () -> Unit,
) {
    val ctaTag = when (pager.page) {
        0 -> OnboardingTags.START
        1 -> OnboardingTags.PAGE2_CTA
        else -> OnboardingTags.PAGE3_CTA
    }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = 26.dp)
            .padding(horizontal = U2Spacing.screenGutterL)
            .u2Bottom(U2Spacing.ctaBottomOnboarding),
    ) {
        Box(Modifier.fillMaxWidth().padding(bottom = 16.dp).testTag(if (isActive) OnboardingTags.PAGE_DOTS else "")) {
            PageDots(count = WelcomePagerModel.PAGE_COUNT, current = pager.page)
        }
        Spacer(Modifier.height(10.dp))
        U2PrimaryButton(onClick = onPrimary, modifier = Modifier.testTag(if (isActive) ctaTag else "$ctaTag.hidden")) {
            WelcomeCtaLabel(pager.page)
        }
        AnimatedVisibility(
            visible = showsRestore,
            enter = fadeIn(softSpec(U2Motion.state)) + expandVertically(softSpec(U2Motion.screen), expandFrom = Alignment.Top),
            exit = fadeOut(softSpec(U2Motion.state)) + shrinkVertically(softSpec(U2Motion.screen), shrinkTowards = Alignment.Top),
        ) {
            Column {
                Spacer(Modifier.height(10.dp))
                U2TextButton(
                    title = stringResource(R.string.onboarding_welcome_restore),
                    onClick = onRestore,
                    modifier = Modifier.testTag(OnboardingTags.RESTORE),
                    tone = U2TextButtonTone.Quiet,
                )
            }
        }
    }
}

/** The three CTA titles stacked, the current one shown: they cross-fade with the page change. */
@Composable
private fun WelcomeCtaLabel(page: Int) {
    val label = LocalU2LabelStyle.current
    val ink = currentLabelColor()
    val titles = listOf(
        stringResource(R.string.onboarding_welcome_start),
        stringResource(R.string.onboarding_notifications_cta),
        stringResource(R.string.onboarding_ghost_cta),
    )
    Box(contentAlignment = Alignment.Center) {
        titles.forEachIndexed { index, title ->
            val alpha by animateFloatAsState(if (index == page) 1f else 0f, u2Spec(U2Motion.screen), label = "welcomeCta")
            U2Text(
                title,
                label.style,
                color = ink,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .graphicsLayer { this.alpha = alpha }
                    .then(if (index == page) Modifier else Modifier.clearAndSetSemantics {}),
            )
        }
    }
}

// MARK: - Swipe

/**
 * A mostly horizontal drag pages; the content follows the finger (cross-fading) and settles on the
 * critically damped spring. The finger travels 75 % of the width for a whole page; past the first
 * and last page it rubber-bands.
 */
private fun Modifier.welcomeSwipe(pager: WelcomePagerModel, width: Float): Modifier = pointerInput(pager, width) {
    val minimum = 14.dp.toPx()
    val span = (width * 0.75f).coerceAtLeast(1f)
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val tracker = VelocityTracker()
        tracker.addPointerInputChange(down)
        var dx = 0f
        var dy = 0f
        var dragging = false
        while (true) {
            val event = awaitPointerEvent()
            val change = event.changes.firstOrNull { it.id == down.id } ?: break
            if (!change.pressed) {
                if (dragging && pager.dragProgress != 0f) {
                    tracker.addPointerInputChange(change)
                    val velocity = tracker.calculateVelocity().x
                    pager.endDrag(
                        predictedProgress = -SoftNavigation.projectedEnd(dx, velocity) / span,
                        velocity = -velocity / span,
                    )
                }
                break
            }
            if (change.isConsumed && !dragging) break
            tracker.addPointerInputChange(change)
            val delta = change.positionChange()
            dx += delta.x
            dy += delta.y
            if (!dragging) {
                if (abs(dx) < minimum && abs(dy) < minimum) continue
                if (abs(dx) <= abs(dy)) break
                dragging = true
            }
            change.consume()
            var progress = -dx / span
            val page = pager.page
            if ((page == 0 && progress < 0f) || (page == WelcomePagerModel.PAGE_COUNT - 1 && progress > 0f)) progress *= 0.18f
            pager.dragProgress = progress.coerceIn(-1f, 1f)
        }
    }
}

// MARK: - 1.1 · Karşılama

/** 1.1 — dot grid, the 3D scene (402 × 330 at y 84), “İki uç. Bir hat.” and the promise; the CTA block is the pager's chrome (bottom 206 dp). */
@Composable
internal fun WelcomeKarsilamaPage() {
    Box(Modifier.fillMaxSize()) {
        DotGridBackdrop(mask = DotGridMask.Welcome, color = U2Theme.colors.dotGrid)
        // The text sits on the CTA block; the scene takes the room above it. On a short screen or
        // with a large system font the scene shrinks toward the top (and gives way when too small to
        // read) instead of being drawn over the title.
        Layout(
            contents = listOf(
                { WelcomeScene(dark = U2Theme.isDark, frozenSeconds = null, modifier = Modifier.u2Top(84.dp)) },
                {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = U2Spacing.screenGutterL)
                            .u2Bottom(206.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        U2DisplayText(R.string.onboarding_welcome_title, U2TextStyles.displayHero, modifier = Modifier.testTag(OnboardingTags.WELCOME_TITLE))
                        U2Text(
                            stringResource(R.string.onboarding_welcome_body),
                            U2TextStyles.bodyL,
                            color = U2Theme.colors.textSecondary,
                            modifier = Modifier.fillMaxWidth().testTag(OnboardingTags.WELCOME_BODY),
                        )
                    }
                },
            ),
            modifier = Modifier.fillMaxSize(),
        ) { (sceneMeasurables, textMeasurables), constraints ->
            val loose = constraints.copy(minWidth = 0, minHeight = 0)
            val text = textMeasurables.first().measure(loose.copy(minWidth = constraints.maxWidth))
            val scene = sceneMeasurables.first().measure(loose)
            val room = (constraints.maxHeight - text.height - 16.dp.roundToPx()).coerceAtLeast(0)
            val scale = if (scene.height <= room) 1f else room.toFloat() / scene.height.coerceAtLeast(1)
            layout(constraints.maxWidth, constraints.maxHeight) {
                scene.placeWithLayer((constraints.maxWidth - scene.width) / 2, 0) {
                    if (scale < 1f) {
                        scaleX = scale
                        scaleY = scale
                        transformOrigin = TransformOrigin(0.5f, 0f)
                        alpha = if (scale < WELCOME_SCENE_MIN_SCALE) 0f else 1f
                    }
                }
                text.place(0, constraints.maxHeight - text.height)
            }
        }
    }
}

/** Below this the shrunken 1.1 scene is too small to read and is left out. */
private const val WELCOME_SCENE_MIN_SCALE = 0.45f

// MARK: - 1.2 · Bildirim yok. Bilerek.

/**
 * 1.2 — the empty lock-screen illustration (live time and date, the dashed ghost notification), the
 * title, the paragraph and the four delivery states with the chat's own icons. Between the pager's
 * header (bottom at y 97) and its CTA block (152 from the bottom), so very large text scrolls in
 * that band only.
 */
@Composable
internal fun WelcomeNoNotificationsPage() {
    OnboardingFitOrScroll(Modifier.u2Top(97.dp).u2Bottom(152.dp)) {
        LockScreenIllustration(Modifier.padding(top = 34.dp))
        GhostNotificationCard(
            Modifier
                .padding(top = 26.dp)
                .padding(horizontal = U2Spacing.screenGutter),
        )
        Spacer(Modifier.weight(1f))
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = U2Spacing.screenGutterL),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            U2DisplayText(R.string.onboarding_notifications_title, U2TextStyles.displayTitleL, modifier = Modifier.testTag(OnboardingTags.NOTIFICATIONS_TITLE))
            U2Text(stringResource(OnboardingR.string.onboarding_android_notifications_body), U2TextStyles.body, color = U2Theme.colors.textSecondary, modifier = Modifier.fillMaxWidth())
            Column(Modifier.testTag(OnboardingTags.LEGEND), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                for (status in listOf(MessageStatus.Sent, MessageStatus.Delivered, MessageStatus.Read, MessageStatus.Failed)) {
                    MessageStatusLegendRow(status)
                }
            }
        }
    }
}

/**
 * “09:41” (`displayClock`, Doto 800 76, `text·86%`) over “Perşembe, 8 Ekim” (`label15`, `text·68%`),
 * gap 6 — the device's own time and date (this is “your lock screen”, so it follows the wall clock,
 * not the demo data), updated each minute. With the design clock frozen it shows the reference's
 * 09:41 on 8 October.
 */
@Composable
private fun LockScreenIllustration(modifier: Modifier = Modifier) {
    val clock = LocalU2Clock.current
    val locale = currentAppLocale()
    val date: Instant = if (clock.isFrozen) {
        DesignClock.designDate(day = 8, hour = 9, minute = 41)
    } else {
        rememberWallMinute()
    }
    val zone = ZoneId.systemDefault()
    val time = remember(date, locale) { DateTimeFormatter.ofPattern("HH:mm", locale).format(date.atZone(zone)) }
    val day = remember(date, locale) { DateTimeFormatter.ofPattern("EEEE, d MMMM", locale).format(date.atZone(zone)) }
    Column(
        modifier
            .fillMaxWidth()
            .testTag(OnboardingTags.CLOCK)
            .semantics(mergeDescendants = true) {},
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        U2Text(time, U2TextStyles.displayClock, color = U2Theme.colors.textClock)
        U2Text(day, U2TextStyles.label15, color = U2Theme.colors.textSecondary)
    }
}

/** The wall clock's time, read again at every minute (the illustration is the user's own lock screen). */
@Composable
private fun rememberWallMinute(): Instant {
    // The design clock runs from the demo moment on fixtures; the illustration wants the real time.
    val tick = rememberU2Now(1.minutes)
    return remember(tick) { Instant.now() }
}

/**
 * The ghost notification: 362 × 68, r22, dashed `text·30%` (1 dp as drawn), pad 14 16, gap 12 — a
 * dashed 38 × 38 r10 square, two dotted skeleton lines (46 % and 82 % of the column, gap 9) and
 * “bildirim yok” (`monoTag`).
 */
@Composable
private fun GhostNotificationCard(modifier: Modifier = Modifier) {
    val colors = U2Theme.colors
    Row(
        modifier
            .fillMaxWidth()
            .height(68.dp)
            .testTag(OnboardingTags.GHOST_CARD)
            .semantics(mergeDescendants = true) {}
            .dashedBorder(RoundedCornerShape(22.dp), colors.dashedStroke)
            // CSS padding sits inside the border, laid out 1 dp wide: content starts 17 in.
            .padding(horizontal = 17.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(38.dp).dashedBorder(RoundedCornerShape(10.dp), colors.dashedStroke))
        BoxWithConstraints(Modifier.weight(1f).height(21.dp)) {
            val column = maxWidth
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(9.dp, Alignment.CenterVertically)) {
                DottedRule(Modifier.width(column * 0.46f), color = colors.dashedDots, pattern = DotPattern.Skeleton)
                DottedRule(Modifier.width(column * 0.82f), color = colors.dashedDots, pattern = DotPattern.Skeleton)
            }
        }
        U2Text(stringResource(R.string.onboarding_notifications_card), U2TextStyles.monoTag, color = colors.textSecondary, maxLines = 1, softWrap = false)
    }
}

// MARK: - 1.3 · Sessizlik siler

/** 1.3 — “GHOST MOD · 24 SAAT”, the live demo countdown from 23:41:07, the 24-dot hour meter, the title, the paragraph and the three features. */
@Composable
internal fun WelcomeSilenceErasesPage() {
    OnboardingFitOrScroll(Modifier.u2Top(97.dp).u2Bottom(152.dp)) {
        GhostDemoCountdown(Modifier.padding(top = 64.dp))
        Spacer(Modifier.weight(1f))
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = U2Spacing.screenGutterL),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            U2DisplayText(R.string.onboarding_ghost_title, U2TextStyles.displayTitleL, modifier = Modifier.testTag(OnboardingTags.GHOST_TITLE))
            U2Text(stringResource(R.string.onboarding_ghost_body), U2TextStyles.body, color = U2Theme.colors.textSecondary, modifier = Modifier.fillMaxWidth())
            val accent = U2Theme.colors.accent300
            Column(
                Modifier
                    .testTag(OnboardingTags.FEATURES)
                    .padding(top = 4.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OnboardingFeatureRow(stringResource(R.string.onboarding_ghost_feature_disappearing)) {
                    PhosphorIcon(PhosphorGlyph.Timer, size = 18.dp, tint = accent)
                }
                OnboardingFeatureRow(stringResource(R.string.onboarding_ghost_feature_viewonce)) {
                    ViewOnceDashedBadge()
                }
                OnboardingFeatureRow(stringResource(R.string.onboarding_ghost_feature_deleteforeveryone)) {
                    PhosphorIcon(PhosphorGlyph.Trash, size = 18.dp, tint = accent)
                }
            }
        }
    }
}

/**
 * The 1.3 hero: kicker, a demo Ghost countdown that starts at 23:41:07 and loops, and the hour meter
 * (one dot per hour of 24, lit for the whole hours still left; a dot fades out over 0.8 s when an
 * hour passes). It keeps ticking under reduce motion — it is information.
 */
@Composable
private fun GhostDemoCountdown(modifier: Modifier = Modifier) {
    val clock = LocalU2Clock.current
    val isVisible = LocalIsLayerVisible.current
    val origin = remember(clock) { GhostDemo.origin(clock) }
    if (isVisible) {
        U2TimeView(interval = 1.seconds) { now -> GhostDemoHero(GhostDemo.remaining(now, origin), modifier) }
    } else {
        // Off screen (another page, or a setup step on top): no ticking.
        GhostDemoHero(GhostDemo.remaining(clock.now, origin), modifier)
    }
}

@Composable
private fun GhostDemoHero(remaining: Duration, modifier: Modifier) {
    val text = U2Format.ghostLong(remaining)
    Column(
        modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        MonoKicker(stringResource(R.string.onboarding_ghost_kicker), glyph = PhosphorGlyph.Ghost, modifier = Modifier.testTag(OnboardingTags.GHOST_KICKER))
        U2Text(
            text,
            U2TextStyles.displayCountdownL,
            color = U2Theme.colors.accent200,
            modifier = Modifier.semantics { contentDescription = text },
        )
        DottedHourMeter(
            totalHours = 24,
            litHours = (remaining.inWholeSeconds / 3_600).toInt(),
            modifier = Modifier.size(288.dp, 8.dp).testTag(OnboardingTags.GHOST_METER),
        )
    }
}

/** The 1.3 demo countdown's arithmetic. */
internal object GhostDemo {
    /** 23:41:07 — the design's demo value. */
    val start: Duration = (23 * 3_600 + 41 * 60 + 7).seconds
    private val period: Duration = (24 * 3_600).seconds

    /** The clock's time when the demo first showed, so it starts at 23:41:07 on any clock. */
    private var firstShown: Instant? = null

    /** Frozen, the design's moment; otherwise when the demo first showed in this process. */
    fun origin(clock: DesignClock): Instant {
        if (clock.isFrozen) return DesignClock.referenceDate
        return firstShown ?: clock.now.also { firstShown = it }
    }

    fun remaining(now: Instant, origin: Instant): Duration {
        val elapsed = java.time.Duration.between(origin, now).toKotlinDuration().coerceAtLeast(Duration.ZERO)
        val value = (start - elapsed).inWholeMilliseconds % period.inWholeMilliseconds
        return (if (value <= 0) value + period.inWholeMilliseconds else value).milliseconds
    }
}
