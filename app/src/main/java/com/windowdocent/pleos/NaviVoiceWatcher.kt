package com.windowdocent.pleos

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.os.Handler
import android.os.Looper

/**
 * 내비 안내 음성이 나오는 중인지 알아냄.
 * Pleos NaviHelper 에는 안내 음성 상태 콜백이 없어서, 안드로이드 AudioManager 의 재생 목록에서
 * 용도가 길 안내 음성(USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)인 소리가 있는지를 봄.
 * 권한이 필요 없고, 어느 기기에서나 동작함 (안드로이드 8.0 이상).
 */
class NaviVoiceWatcher(
    context: Context,
    private val onChange: (speaking: Boolean) -> Unit,
) {
    private val audio = context.getSystemService(AudioManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private var last: Boolean? = null

    private val callback = object : AudioManager.AudioPlaybackCallback() {
        override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>) = publish(configs)
    }

    fun start() {
        val manager = checkNotNull(audio) { "AudioManager 없음" }
        manager.registerAudioPlaybackCallback(callback, handler)
        publish(manager.activePlaybackConfigurations)
    }

    fun stop() {
        audio?.unregisterAudioPlaybackCallback(callback)
        last = null
    }

    private fun publish(configs: List<AudioPlaybackConfiguration>) {
        val speaking = isGuidancePlaying(configs.map { it.audioAttributes.usage })
        if (speaking != last) {
            last = speaking
            onChange(speaking)
        }
    }

    companion object {
        /** 재생 중인 소리들의 용도 가운데 길 안내 음성이 있는지 */
        fun isGuidancePlaying(usages: List<Int>): Boolean =
            usages.any { it == AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE }
    }
}
