package com.syconx.us2us.feature.settings

import com.syconx.us2us.domain.DesignClock
import com.syconx.us2us.domain.DeviceTransferFailure
import com.syconx.us2us.domain.design.DesignFixtures
import com.syconx.us2us.fixtures.FixturesMessengerService
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The screens' models of the transfer (PROTOCOL.md §5.6) over the design fixtures (iOS `DeviceTransferModelTests`). */
@OptIn(ExperimentalCoroutinesApi::class)
class DeviceTransferModelTest {
    private fun service() = FixturesMessengerService(
        hasAccount = true,
        clock = DesignClock.frozen(DesignClock.referenceDate),
        latency = Duration.ZERO,
    )

    /** What runs in the background scope is left alone by `advanceUntilIdle`. */
    private fun TestScope.finishWork() {
        advanceTimeBy(5_000.milliseconds)
        runCurrent()
    }

    @Test
    fun theOldPhoneSendsOnceTheCodeIsRead() = runTest {
        val model = DeviceTransferSendModel(service(), backgroundScope)
        assertEquals(DeviceTransferSendModel.Stage.Intro, model.stage)
        model.start()
        assertEquals(DeviceTransferSendModel.Stage.Code, model.stage)
        model.scan()
        assertEquals(DeviceTransferSendModel.Stage.Scanning, model.stage)
        model.send(DesignFixtures.TRANSFER_INVITATION)
        assertEquals(DeviceTransferSendModel.Stage.Sending(0.0), model.stage)
        advanceTimeBy(160.milliseconds)
        runCurrent()
        assertEquals(DeviceTransferSendModel.Stage.Sending(0.25), model.stage)
        finishWork()
        assertEquals(DeviceTransferSendModel.Stage.Done, model.stage)
    }

    @Test
    fun aFailureSaysWhyAndTheScanCanBeRetried() = runTest {
        val service = service().apply { transferSendFailure = DeviceTransferFailure.Unreachable }
        val model = DeviceTransferSendModel(service, backgroundScope)
        model.start()
        model.scan()
        model.send(DesignFixtures.TRANSFER_INVITATION)
        finishWork()
        assertEquals(DeviceTransferSendModel.Stage.Failed(DeviceTransferFailure.Unreachable), model.stage)
        model.scan()
        assertEquals(DeviceTransferSendModel.Stage.Scanning, model.stage)
    }

    @Test
    fun leavingTheAppStopsTheTransferAndStartsOver() = runTest {
        val model = DeviceTransferSendModel(service(), backgroundScope)
        model.start()
        model.scan()
        model.send(DesignFixtures.TRANSFER_INVITATION)
        runCurrent()
        model.appLeft()
        assertEquals(DeviceTransferSendModel.Stage.Intro, model.stage)
        finishWork()
        assertEquals("what was underway does not come back", DeviceTransferSendModel.Stage.Intro, model.stage)
    }

    @Test
    fun leavingAfterItWasSentKeepsThatSaid() = runTest {
        val model = DeviceTransferSendModel(service(), backgroundScope)
        model.start()
        model.scan()
        model.send(DesignFixtures.TRANSFER_INVITATION)
        finishWork()
        model.appLeft()
        assertEquals(DeviceTransferSendModel.Stage.Done, model.stage)
    }

    @Test
    fun theNewPhoneShowsACodeTheOldPhoneCanRead() = runTest {
        val service = service()
        val model = DeviceTransferReceiveModel(service, backgroundScope)
        model.start()
        runCurrent()
        val stage = model.stage as DeviceTransferReceiveModel.Stage.Waiting
        assertTrue(service.isAccountInvitation(stage.invitation))
        assertFalse(service.isAccountInvitation("selin_n"))
        assertFalse(service.isAccountInvitation("us2us-transfer:2:x:y:1.2.3.4:5"))
        model.finish()
    }

    @Test
    fun theNewPhoneWithoutANetworkSaysSoAndCanTryAgain() = runTest {
        val service = service().apply { transferReceiveFailure = DeviceTransferFailure.NoLocalNetwork }
        val model = DeviceTransferReceiveModel(service, backgroundScope)
        model.start()
        runCurrent()
        assertEquals(DeviceTransferReceiveModel.Stage.Failed(DeviceTransferFailure.NoLocalNetwork), model.stage)
        service.transferReceiveFailure = null
        model.start()
        runCurrent()
        assertTrue(model.stage is DeviceTransferReceiveModel.Stage.Waiting)
        model.finish()
    }

    @Test
    fun anInvitationThatRunsOutIsReplacedByANewOne() = runTest {
        var opened = 0
        val service = object : com.syconx.us2us.domain.MessengerService by service() {
            override suspend fun startReceivingAccount(): com.syconx.us2us.domain.DeviceTransferIncoming {
                opened += 1
                return FixturesMessengerService(
                    hasAccount = false,
                    clock = DesignClock.frozen(DesignClock.referenceDate),
                    latency = Duration.ZERO,
                ).startReceivingAccount()
            }
        }
        val model = DeviceTransferReceiveModel(service, backgroundScope, lifetime = 50.milliseconds)
        model.start()
        runCurrent()
        assertEquals(1, opened)
        assertTrue(model.stage is DeviceTransferReceiveModel.Stage.Waiting)
        advanceTimeBy(60.milliseconds)
        runCurrent()
        assertEquals(2, opened)
        assertTrue(model.stage is DeviceTransferReceiveModel.Stage.Waiting)
        model.finish()
        advanceTimeBy(500.milliseconds)
        runCurrent()
        assertEquals("nothing comes up after the screen went away", 2, opened)
    }

    @Test
    fun anAccountOnItsWayKeepsTheCodeFromRunningOutThenIsHandedOn() = runTest {
        val service = service().apply { transferDelivers = true }
        val model = DeviceTransferReceiveModel(service, backgroundScope, lifetime = 450.milliseconds)
        model.start()
        runCurrent()
        assertTrue(model.stage is DeviceTransferReceiveModel.Stage.Waiting)
        advanceTimeBy(410.milliseconds)
        runCurrent()
        assertEquals(DeviceTransferReceiveModel.Stage.Receiving(0.0), model.stage)
        finishWork()
        val delivered = model.stage as DeviceTransferReceiveModel.Stage.Delivered
        assertEquals(DesignFixtures.DEMO_BACKUP_PASSWORD, delivered.delivery.password)
    }

    @Test
    fun leavingTheAppEndsTheInvitationAndReturningBringsANewOne() = runTest {
        val model = DeviceTransferReceiveModel(service(), backgroundScope)
        model.appReturned()
        runCurrent()
        assertTrue(model.stage is DeviceTransferReceiveModel.Stage.Waiting)
        model.appLeft()
        model.appReturned()
        assertEquals(DeviceTransferReceiveModel.Stage.Preparing, model.stage)
        runCurrent()
        assertTrue(model.stage is DeviceTransferReceiveModel.Stage.Waiting)
        model.finish()
    }
}
