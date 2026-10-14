package com.syconx.us2us.feature.conversation.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.os.Build
import android.os.SystemClock
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.syconx.us2us.domain.Bytes
import com.syconx.us2us.domain.VoiceRecordingFiles
import com.syconx.us2us.domain.VoiceWaveform
import com.syconx.us2us.ui.composer.VoiceCapture
import com.syconx.us2us.ui.composer.VoiceCaptureDevice
import com.syconx.us2us.ui.composer.VoicePermission
import com.syconx.us2us.ui.env.rememberGuardedLauncher
import java.io.File
import kotlin.math.log10
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CompletableDeferred

/**
 * The phone's microphone (RECORD_AUDIO: only while the user records a voice message). Asking goes
 * through [requester], the screen's permission launcher (guarded, so returning from the system
 * dialog does not lock the app).
 */
class MicrophoneVoiceDevice(
    context: Context,
    private val requester: suspend () -> Boolean,
) : VoiceCaptureDevice {
    private val appContext = context.applicationContext

    /** Not granted reads as not asked: the system dialog answers at once when it may not ask again, and the sheet follows. */
    override val permission: VoicePermission
        get() = if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            VoicePermission.Granted
        } else {
            VoicePermission.Undetermined
        }

    override suspend fun requestPermission(): Boolean = requester()

    override fun makeCapture(): VoiceCapture = MediaRecorderCapture(appContext)
}

/** A [MicrophoneVoiceDevice] whose permission request runs through this screen. */
@Composable
fun rememberMicrophoneVoiceDevice(): VoiceCaptureDevice {
    val context = LocalContext.current
    val pending = remember { arrayOfNulls<CompletableDeferred<Boolean>>(1) }
    val launcher = rememberGuardedLauncher(ActivityResultContracts.RequestPermission()) { granted ->
        pending[0]?.complete(granted)
        pending[0] = null
    }
    return remember(context, launcher) {
        MicrophoneVoiceDevice(context) {
            val answer = CompletableDeferred<Boolean>()
            pending[0]?.complete(false)
            pending[0] = answer
            launcher.launch(Manifest.permission.RECORD_AUDIO)
            answer.await()
        }
    }
}

/**
 * MediaRecorder: AAC-LC in MPEG-4, mono, 24 kHz, 32 kbit/s, into a fresh file of
 * [VoiceRecordingFiles] that [finish] reads into memory and deletes at once (and [discard] deletes).
 * An error of the recorder counts as an interruption (what was recorded goes to review).
 */
private class MediaRecorderCapture(private val context: Context) : VoiceCapture {
    override var onInterrupted: (() -> Unit)? = null

    private var recorder: MediaRecorder? = null
    private var file: File? = null
    private var startedAt = 0L
    private var failed = false

    override val currentTime: Duration
        get() = if (recorder == null) Duration.ZERO else (SystemClock.elapsedRealtime() - startedAt).milliseconds

    override val isRecording: Boolean get() = recorder != null && !failed

    override fun start(): Boolean {
        if (recorder != null) return false
        val file = VoiceRecordingFiles.makeFile() ?: return false
        @Suppress("DEPRECATION")
        val recorder = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(context) else MediaRecorder()
        return runCatching {
            recorder.setAudioSource(MediaRecorder.AudioSource.MIC)
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            recorder.setAudioChannels(1)
            recorder.setAudioSamplingRate(24_000)
            recorder.setAudioEncodingBitRate(32_000)
            recorder.setOutputFile(file.absolutePath)
            recorder.setOnErrorListener { _, _, _ ->
                failed = true
                onInterrupted?.invoke()
            }
            recorder.prepare()
            recorder.start()
            this.recorder = recorder
            this.file = file
            startedAt = SystemClock.elapsedRealtime()
            true
        }.getOrElse {
            recorder.release()
            VoiceRecordingFiles.discard(file)
            false
        }
    }

    override fun level(): Double {
        val recorder = recorder ?: return 0.0
        val amplitude = runCatching { recorder.maxAmplitude }.getOrDefault(0)
        if (amplitude <= 0) return 0.0
        return VoiceWaveform.level((20 * log10(amplitude / 32_767.0)).toFloat())
    }

    override fun finish(): Bytes? {
        val recorder = recorder ?: return null
        val file = file ?: return null
        this.recorder = null
        this.file = null
        val stopped = runCatching { recorder.stop() }.isSuccess
        recorder.release()
        if (!stopped) {
            VoiceRecordingFiles.discard(file)
            return null
        }
        return VoiceRecordingFiles.consume(file)
    }

    override fun discard() {
        recorder?.let { recorder ->
            runCatching { recorder.stop() }
            recorder.release()
        }
        recorder = null
        file?.let(VoiceRecordingFiles::discard)
        file = null
    }
}
