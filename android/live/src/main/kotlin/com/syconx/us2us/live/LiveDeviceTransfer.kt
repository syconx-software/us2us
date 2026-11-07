package com.syconx.us2us.live

import com.syconx.us2us.core.transfer.DeviceTransferError
import com.syconx.us2us.core.transfer.DeviceTransferException
import com.syconx.us2us.core.transfer.DeviceTransferHistorySource
import com.syconx.us2us.core.transfer.DeviceTransferInvite
import com.syconx.us2us.core.transfer.DeviceTransferSpool
import com.syconx.us2us.domain.BackupContents
import com.syconx.us2us.domain.DeviceTransferDeliveryInfo
import com.syconx.us2us.domain.DeviceTransferEvent
import com.syconx.us2us.domain.DeviceTransferFailure
import com.syconx.us2us.domain.DeviceTransferIncoming
import com.syconx.us2us.domain.MessengerException
import com.syconx.us2us.domain.MessengerService
import com.syconx.us2us.runtime.transfer.DeviceTransferListening
import com.syconx.us2us.runtime.transfer.DeviceTransferNetworkException
import com.syconx.us2us.runtime.transfer.DeviceTransferSending
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Direct transfer over the phones' own network (PROTOCOL.md §5.6): the backup pipeline of §5.5 with
 * a generated password nobody has to see or keep.
 */
internal object LiveDeviceTransfer {
    fun isInvitation(text: String): Boolean = DeviceTransferInvite.parse(text) != null

    suspend fun startReceiving(
        directory: File,
        main: CoroutineDispatcher,
        io: CoroutineDispatcher,
        keepHistory: (DeviceTransferSpool?) -> Unit,
    ): DeviceTransferIncoming {
        // Not cancelled half way: an open socket nobody holds would stay open.
        val listening = withContext(io + NonCancellable) {
            try {
                DeviceTransferListening(directory).also { it.start() }
            } catch (_: DeviceTransferNetworkException) {
                throw MessengerException.DeviceTransfer(DeviceTransferFailure.NoLocalNetwork)
            } catch (_: IOException) {
                throw MessengerException.DeviceTransfer(DeviceTransferFailure.Unreachable)
            }
        }
        if (!currentCoroutineContext().isActive) {
            listening.stop()
            throw CancellationException()
        }
        keepHistory(null)
        return Incoming(listening, main, keepHistory)
    }

    suspend fun send(
        messenger: MessengerService,
        history: DeviceTransferHistorySource,
        invitation: String,
        main: CoroutineDispatcher,
        io: CoroutineDispatcher,
        progress: (Double) -> Unit,
    ) {
        val password = messenger.generateBackupPassword()
        val file = try {
            messenger.exportBackup(BackupContents(), password, "transfer.u2u") {}
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            throw MessengerException.DeviceTransfer(DeviceTransferFailure.NotPrepared)
        }
        try {
            withContext(io) {
                coroutineScope {
                    DeviceTransferSending.send(File(file), password, invitation, history) { fraction ->
                        launch(main) { progress(fraction) }
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: DeviceTransferNetworkException) {
            throw MessengerException.DeviceTransfer(DeviceTransferFailure.Unreachable)
        } catch (e: Exception) {
            val full = (e as? DeviceTransferException)?.error == DeviceTransferError.NO_SPACE
            throw MessengerException.DeviceTransfer(if (full) DeviceTransferFailure.NoSpace else DeviceTransferFailure.Interrupted)
        } finally {
            withContext(NonCancellable) { messenger.finishExport(file, saved = false) }
        }
    }

    private class Incoming(
        private val listening: DeviceTransferListening,
        private val main: CoroutineDispatcher,
        private val keepHistory: (DeviceTransferSpool?) -> Unit,
    ) : DeviceTransferIncoming {
        private val scope = CoroutineScope(SupervisorJob() + main)

        override var onEvent: ((DeviceTransferEvent) -> Unit)? = null

        init {
            listening.onStarted = { deliver(DeviceTransferEvent.Started) }
            listening.onProgress = { deliver(DeviceTransferEvent.Progress(it)) }
            listening.onInterrupted = { deliver(DeviceTransferEvent.Interrupted) }
            listening.onNoSpace = { deliver(DeviceTransferEvent.NoSpace) }
        }

        private fun deliver(event: DeviceTransferEvent) {
            scope.launch { onEvent?.invoke(event) }
        }

        override val invitation: String get() = listening.invitation

        override suspend fun waitForDelivery(): DeviceTransferDeliveryInfo {
            val delivery = listening.waitForDelivery()
            withContext(main) { keepHistory(delivery.history) }
            return DeviceTransferDeliveryInfo(delivery.file.toURI(), delivery.password)
        }

        override fun stop() {
            scope.cancel()
            listening.stop()
        }

        override fun removeFiles() {
            keepHistory(null)
            listening.removeFiles()
        }
    }
}
