package com.syconx.us2us.feature.media

import androidx.media3.common.Player
import com.syconx.us2us.domain.MediaPayload

/** A video engine without a decoder: ready at once with [length] seconds, playing only in the test's say-so. */
internal class FakeVideoEngine(private val length: Double = 18.0, private val readyAtOnce: Boolean = true) : VideoEngine {
    var listener: VideoEngine.Listener? = null
    var prepared: MediaPayload? = null
    var playing = false
    var silent = false
    var released = false
    var seeks = mutableListOf<Double>()

    override val player: Player? = null
    override var position: Double = 0.0
    override var buffered: Double = 0.0

    override fun prepare(payload: MediaPayload, listener: VideoEngine.Listener) {
        prepared = payload
        this.listener = listener
        buffered = length
        if (readyAtOnce) listener.onReady(length)
    }

    override fun play() {
        playing = true
    }

    override fun pause() {
        playing = false
    }

    override fun seekTo(seconds: Double) {
        seeks += seconds
        position = seconds
    }

    override fun setMuted(muted: Boolean) {
        silent = muted
    }

    override fun release() {
        released = true
        playing = false
    }
}
