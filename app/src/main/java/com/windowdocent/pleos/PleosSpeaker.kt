package com.windowdocent.pleos

import ai.pleos.playground.tts.TextToSpeech
import ai.pleos.playground.tts.constant.ErrorType
import ai.pleos.playground.tts.constant.Mode
import ai.pleos.playground.tts.listener.EventListener
import ai.pleos.playground.tts.listener.OnInitializeListener
import android.content.Context
import android.content.Intent
import android.util.Log
import com.windowdocent.core.Speaker
import java.util.Locale

/**
 * Pleos TextToSpeech SDK 로 한 문장씩 말함. SDK 에는 speak 와 stop 만 있고 일시정지가 없어,
 * 멈춤 · 이어 말하기는 Narrator 가 문장 단위로 함.
 * - 기기 안 음성(Mode.ON_DEVICE): 통신이 끊겨도 말하게. 서버 음성(registerApp)은 쓰지 않음
 * - 연결(initialize)이 끝나기 전에 온 문장은 기다렸다 말함. 연결에 실패하면 unavailable 을 불러 다른 장치로 넘김
 * - 매니페스트에 pleos.car.permission.TTS_SERVICE 가 있어야 함. 없으면 addEventListener 가 SecurityException (11/14 에뮬레이터)
 *   만들다 예외가 나면 AppSpeaker(SpeakerChain) 가 안드로이드 TTS 로 넘김
 * 컴파일은 SDK 로 확인했고, Pleos Connect 에뮬레이터에서의 실행 확인은 2차 준비 기간에 함.
 */
class PleosSpeaker(
    context: Context,
    private val unavailable: (String) -> Unit,
) : Speaker {
    private val tts = TextToSpeech(context.applicationContext, Mode.ON_DEVICE)
    private var ready = false
    private var failedInit = false
    private var pending: Request? = null
    private var current: Request? = null
    private var locale: String? = null

    private class Request(val text: String, val language: String, val done: () -> Unit, val failed: (String) -> Unit)

    private val events = object : EventListener {
        override fun onReady() {}
        override fun onStart() {}
        override fun onUpdatedRms(rms: Double) {}
        override fun onStop() {}

        override fun onDone() {
            val r = take() ?: return
            r.done()
        }

        override fun onError(errorType: ErrorType) {
            val r = take() ?: return
            r.failed(errorType.name)
        }
    }

    init {
        tts.addEventListener(events)
        tts.initialize(object : OnInitializeListener {
            override fun onSuccess() {
                val next = synchronized(this@PleosSpeaker) {
                    ready = true
                    pending.also { pending = null }
                }
                next?.let { start(it) }
            }

            override fun onFailure(errorType: ErrorType) {
                Log.w(TAG, "TextToSpeech 연결 실패: $errorType")
                val waiting = synchronized(this@PleosSpeaker) {
                    failedInit = true
                    pending.also { pending = null }
                }
                unavailable(errorType.name)
                waiting?.failed(errorType.name)
            }
        })
    }

    override fun speak(text: String, language: String, done: () -> Unit, failed: (String) -> Unit) {
        val r = Request(text, language, done, failed)
        val now = synchronized(this) {
            when {
                failedInit -> NOT_CONNECTED
                !ready -> { pending = r; WAIT }
                else -> SPEAK
            }
        }
        when (now) {
            NOT_CONNECTED -> failed("TextToSpeech 연결 안 됨")
            SPEAK -> start(r)
        }
    }

    override fun stop() {
        synchronized(this) {
            pending = null
            current = null
        }
        try {
            tts.stop()
        } catch (t: Throwable) {
            Log.w(TAG, "stop 실패", t)
        }
    }

    override fun release() {
        stop()
        try {
            tts.removeEventListener(events)
            tts.release()
        } catch (t: Throwable) {
            Log.w(TAG, "release 실패", t)
        }
    }

    private fun start(r: Request) {
        synchronized(this) { current = r }
        try {
            if (locale != r.language) {
                tts.setLocale(localeOf(r.language))
                locale = r.language
            }
            // 둘째 인자 false: 이어 붙이지 않고 이 문장만 (문장 순서는 Narrator 가 정함)
            tts.speak(r.text, false)
        } catch (t: Throwable) {
            Log.w(TAG, "speak 실패", t)
            take()?.failed(t.javaClass.simpleName)
        }
    }

    @Synchronized
    private fun take(): Request? = current.also { current = null }

    companion object {
        private const val TAG = "PleosSpeaker"
        private const val NOT_CONNECTED = 0
        private const val WAIT = 1
        private const val SPEAK = 2

        /** Pleos 음성 서비스 주소 (TextToSpeech 묶음 매니페스트의 queries 와 같음) */
        private const val SPEECH_ACTION = "ai.pleos.playground.caas.intent.action.SPEECH_START"

        /** 이 기기에 Pleos 음성 서비스가 있는지 */
        fun available(context: Context): Boolean {
            val intent = Intent(SPEECH_ACTION)
            val pm = context.packageManager
            return pm.queryIntentServices(intent, 0).isNotEmpty() || pm.queryIntentActivities(intent, 0).isNotEmpty()
        }

        fun localeOf(language: String): Locale = when (language) {
            "en" -> Locale.US
            "es" -> Locale("es", "ES")
            "ja" -> Locale.JAPAN
            "zh" -> Locale.CHINA
            else -> Locale.KOREA
        }
    }
}
