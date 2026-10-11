package de.pascal.pillarone.ble

import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationManager
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import de.pascal.pillarone.MainActivity
import de.pascal.pillarone.PillarApplication
import de.pascal.pillarone.R
import org.junit.Assert.*
import org.junit.Test

class BleConnectionServiceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test fun notificationHasLowImportanceAndImmutableDisconnectAction() {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.deleteNotificationChannel(BleConnectionService.CHANNEL)
        BleConnectionNotification.createChannel(context)
        assertEquals(NotificationManager.IMPORTANCE_LOW,
            manager.getNotificationChannel(BleConnectionService.CHANNEL).importance)
        val notification = BleConnectionNotification.build(context, ConnectionPhase.READY)
        assertEquals(context.getString(R.string.ble_notification_connected),
            notification.extras.getCharSequence(Notification.EXTRA_TEXT))
        assertTrue(notification.flags and Notification.FLAG_ONGOING_EVENT != 0)
        assertNotNull(notification.smallIcon)
        assertEquals(context.getString(R.string.disconnect), notification.actions.single().title)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            assertTrue(notification.contentIntent.isImmutable)
            assertTrue(notification.actions.single().actionIntent.isImmutable)
        }
    }

    @Test fun staleStartCreatesChannelThenStopsWithoutAnIdleForegroundNotification() {
        ActivityScenario.launch(MainActivity::class.java).use {
            instrumentation.runOnMainSync { (context.applicationContext as PillarApplication).ble.disconnect() }
            awaitStopped()
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.deleteNotificationChannel(BleConnectionService.CHANNEL)
            ContextCompat.startForegroundService(context, Intent(context, BleConnectionService::class.java)
                .putExtra(BleConnectionService.REQUEST, -100L))
            await { manager.getNotificationChannel(BleConnectionService.CHANNEL) != null }
            awaitStopped()
            assertFalse(manager.activeNotifications.any { it.id == BleConnectionService.NOTIFICATION_ID })
        }
    }

    @Test fun disconnectCommandWithoutASessionStopsAndResumeDoesNotConnect() {
        ActivityScenario.launch(MainActivity::class.java).use {
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.deleteNotificationChannel(BleConnectionService.CHANNEL)
            ContextCompat.startForegroundService(context, Intent(context, BleConnectionService::class.java)
                .setAction(BleConnectionService.DISCONNECT))
            await { manager.getNotificationChannel(BleConnectionService.CHANNEL) != null }
            awaitStopped()
            instrumentation.runOnMainSync {
                val runtime = (context.applicationContext as PillarApplication).ble
                runtime.onAppVisible()
                assertFalse(runtime.state.value.phase in setOf(ConnectionPhase.CONNECTING, ConnectionPhase.RECONNECTING))
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun awaitStopped() = await {
        context.getSystemService(ActivityManager::class.java).getRunningServices(Int.MAX_VALUE)
            .none { it.service.className == BleConnectionService::class.java.name }
    }

    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 5_000
        do {
            instrumentation.waitForIdleSync()
            if (condition()) return
            SystemClock.sleep(20)
        } while (SystemClock.uptimeMillis() < deadline)
        assertTrue("Service lifecycle did not settle", condition())
    }
}