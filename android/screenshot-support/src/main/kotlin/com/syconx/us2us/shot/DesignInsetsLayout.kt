package com.syconx.us2us.shot

import android.annotation.SuppressLint
import android.content.Context
import android.view.WindowInsets
import android.widget.FrameLayout
import androidx.core.graphics.Insets
import androidx.core.view.WindowInsetsCompat

/**
 * Hands its children the design's safe area (status bar 62 dp, home indicator 34 dp) in place of
 * whatever the window reports, so `WindowInsets.statusBars`, `navigationBars`, `systemBars` and
 * `safeDrawing` measure exactly what the iOS frames assume. With [enabled] off the window's own
 * insets pass through.
 */
@SuppressLint("ViewConstructor")
internal class DesignInsetsLayout(
    context: Context,
    private val enabled: Boolean,
    private val topDp: Int = DesignMoment.SAFE_TOP_DP,
    private val bottomDp: Int = DesignMoment.SAFE_BOTTOM_DP,
) : FrameLayout(context) {
    private val designInsets: WindowInsets by lazy {
        val density = resources.displayMetrics.density
        val top = Math.round(topDp * density)
        val bottom = Math.round(bottomDp * density)
        WindowInsetsCompat.Builder()
            .setInsets(WindowInsetsCompat.Type.statusBars(), Insets.of(0, top, 0, 0))
            .setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(0, 0, 0, bottom))
            .setInsets(WindowInsetsCompat.Type.tappableElement(), Insets.of(0, top, 0, bottom))
            .setInsets(WindowInsetsCompat.Type.mandatorySystemGestures(), Insets.of(0, top, 0, bottom))
            .setInsetsIgnoringVisibility(WindowInsetsCompat.Type.statusBars(), Insets.of(0, top, 0, 0))
            .setInsetsIgnoringVisibility(WindowInsetsCompat.Type.navigationBars(), Insets.of(0, 0, 0, bottom))
            .setVisible(WindowInsetsCompat.Type.systemBars(), true)
            .build()
            .toWindowInsets()!!
    }

    override fun dispatchApplyWindowInsets(insets: WindowInsets): WindowInsets =
        super.dispatchApplyWindowInsets(if (enabled) designInsets else insets)

    /** Sends the design insets down now (the window may never dispatch any under Robolectric). */
    fun applyDesignInsets() {
        if (enabled) dispatchApplyWindowInsets(designInsets)
    }
}
