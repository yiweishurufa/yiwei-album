package com.hark.shiguang

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 用户 2026-10-24 要求排查：重建 / 最近任务重开时不再重复处理分享进来的旧 intent。 */
class StaleIntentTest {
    private val fromHistory = 0x00100000 // Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY

    @Test fun freshLaunchIsHandled() = assertFalse(isStaleLaunchIntent(false, 0))
    @Test fun restoredAfterProcessDeathIsStale() = assertTrue(isStaleLaunchIntent(true, 0))
    @Test fun reopenedFromRecentsIsStale() = assertTrue(isStaleLaunchIntent(false, fromHistory or 0x10000000))
}
