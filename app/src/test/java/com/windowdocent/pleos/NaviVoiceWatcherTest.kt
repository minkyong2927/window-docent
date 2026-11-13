package com.windowdocent.pleos

import android.media.AudioAttributes
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 내비 안내 음성 판단: 재생 중인 소리 가운데 길 안내 음성 용도가 있을 때만 '안내 음성 중' */
class NaviVoiceWatcherTest {
    @Test fun guidanceUsageMeansSpeaking() {
        assertTrue(NaviVoiceWatcher.isGuidancePlaying(listOf(AudioAttributes.USAGE_MEDIA, AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)))
    }

    @Test fun musicOrNothingIsNotGuidance() {
        assertFalse(NaviVoiceWatcher.isGuidancePlaying(emptyList()))
        assertFalse(NaviVoiceWatcher.isGuidancePlaying(listOf(AudioAttributes.USAGE_MEDIA, AudioAttributes.USAGE_ASSISTANT)))
    }
}
