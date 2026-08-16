package com.uhmk.pos.core.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateNotificationPolicyTest {

    @Test
    fun `startup check is throttled until the interval passes`() {
        val now = 10L * UpdateNotificationPolicy.MIN_CHECK_INTERVAL_MS

        assertTrue(UpdateNotificationPolicy.shouldCheck(0L, now))
        assertFalse(UpdateNotificationPolicy.shouldCheck(now - 1_000L, now))
        assertTrue(
            UpdateNotificationPolicy.shouldCheck(
                now - UpdateNotificationPolicy.MIN_CHECK_INTERVAL_MS,
                now,
            )
        )
    }

    @Test
    fun `clock rollback allows a fresh check`() {
        assertTrue(UpdateNotificationPolicy.shouldCheck(lastCheckedAt = 2_000L, now = 1_000L))
    }

    @Test
    fun `same release is notified only once`() {
        val update = update(versionCode = 10, versionName = "2.6.2")
        val key = UpdateNotificationPolicy.notificationKey(update)

        assertTrue(UpdateNotificationPolicy.shouldNotify(null, update))
        assertFalse(UpdateNotificationPolicy.shouldNotify(key, update))
    }

    @Test
    fun `version name identifies releases without a version code`() {
        val first = update(versionCode = null, versionName = "2.6.2")
        val next = update(versionCode = null, versionName = "2.6.3")

        assertFalse(
            UpdateNotificationPolicy.shouldNotify(
                UpdateNotificationPolicy.notificationKey(first),
                first,
            )
        )
        assertTrue(
            UpdateNotificationPolicy.shouldNotify(
                UpdateNotificationPolicy.notificationKey(first),
                next,
            )
        )
    }

    private fun update(versionCode: Int?, versionName: String) = AppUpdateInfo(
        versionCode = versionCode,
        versionName = versionName,
        title = "UhmK POS $versionName",
        notes = "",
        downloadUrl = "https://example.invalid/UhmKPOS-release.apk",
        assetName = "UhmKPOS-release.apk",
    )
}
