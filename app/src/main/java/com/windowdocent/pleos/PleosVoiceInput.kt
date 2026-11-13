package com.windowdocent.pleos

import ai.pleos.playground.stt.SpeechToText
import ai.pleos.playground.stt.constant.ErrorType
import ai.pleos.playground.stt.constant.Mode
import ai.pleos.playground.stt.listener.OnInitializeListener
import ai.pleos.playground.stt.listener.ResultListener
import android.content.Context
import android.util.Log
import com.windowdocent.core.VoiceInput

/**
 * Pleos SpeechToText SDK 3.3.1 로 한 마디를 들음 (구현 4번 음성 명령).
 * - 기기 안 인식(Mode.ON_DEVICE)만 씀: 앱 등록(registerApp)이 필요 없고, 차량 마이크 음성을 바깥 음성 인식 서비스로 보내지 않음 (공모전 공지)
 * - request() 로 듣기 시작 · ResultListener.onUpdated(글, 끝났는지) 에서 끝난 글을 받음 · stop() 으로 그만
 * - 해설을 말하는 동안 계속 듣지는 않음: 화면의 말하기 단추를 누르거나, 못 한 이야기를 물은 뒤 한 번만 들음
 * - 연결(initialize)에 실패하면 unavailable 을 불러 화면이 흉내 단추로 돌아가게 함
 * - 매니페스트에 pleos.car.permission.STT_SERVICE 가 있어야 함. 만들다 예외가 나면 MainActivity 가 흉내 단추로만 둠
 * 컴파일은 SDK 로 확인했고, Pleos Connect 에뮬레이터에서의 실행 확인은 2차 준비 기간에 함.
 */
class PleosVoiceInput(
    context: Context,
    private val unavailable: (String) -> Unit,
) : VoiceInput {
    private val stt = SpeechToText(context.applicationContext, Mode.ON_DEVICE)
    private var ready = false
    private var failedInit = false
    private var pending: Request? = null
    private var current: Request? = null
    private var locale: String? = null

    private class Request(val language: String, val heard: (String) -> Unit, val failed: (String) -> Unit)

    private val results = object : ResultListener {
        override fun onReady() {}
        override fun onStartedRecognition() {}
        override fun onUpdatedRms(rms: Double) {}
        override fun onUpdatedEpdData(start: Long, end: Long) {}

        override fun onUpdated(text: String, isFinal: Boolean) {
            if (!isFinal) return
            take()?.heard?.invoke(text)
        }

        override fun onEndedRecognition() {
            // 끝난 글 없이 끝남 (아무 말도 안 함)
            take()?.failed?.invoke("들은 말 없음")
        }

        override fun onError(errorType: ErrorType) {
            take()?.failed?.invoke(errorType.name)
        }
    }

    init {
        stt.addListener(results)
        stt.initialize(object : OnInitializeListener {
            override fun onSuccess() {
                val next = synchronized(this@PleosVoiceInput) {
                    ready = true
                    pending.also { pending = null }
                }
                next?.let { start(it) }
            }

            override fun onFailure(errorType: ErrorType) {
                Log.w(TAG, "SpeechToText 연결 실패: $errorType")
                val waiting = synchronized(this@PleosVoiceInput) {
                    failedInit = true
                    pending.also { pending = null }
                }
                unavailable(errorType.name)
                waiting?.failed?.invoke(errorType.name)
            }
        })
    }

    override fun listen(language: String, heard: (String) -> Unit, failed: (String) -> Unit) {
        val r = Request(language, heard, failed)
        val now = synchronized(this) {
            when {
                failedInit -> NOT_CONNECTED
                !ready -> { pending = r; WAIT }
                else -> LISTEN
            }
        }
        when (now) {
            NOT_CONNECTED -> failed("SpeechToText 연결 안 됨")
            LISTEN -> start(r)
        }
    }

    override fun stop() {
        synchronized(this) {
            pending = null
            current = null
        }
        try {
            stt.stop()
        } catch (t: Throwable) {
            Log.w(TAG, "stop 실패", t)
        }
    }

    override fun release() {
        stop()
        try {
            stt.removeListener(results)
            stt.release()
        } catch (t: Throwable) {
            Log.w(TAG, "release 실패", t)
        }
    }

    private fun start(r: Request) {
        synchronized(this) { current = r }
        try {
            if (locale != r.language) {
                stt.setLocale(PleosSpeaker.localeOf(r.language))
                locale = r.language
            }
            stt.request()
        } catch (t: Throwable) {
            Log.w(TAG, "request 실패", t)
            take()?.failed?.invoke(t.javaClass.simpleName)
        }
    }

    @Synchronized
    private fun take(): Request? = current.also { current = null }

    private companion object {
        const val TAG = "PleosVoiceInput"
        const val NOT_CONNECTED = 0
        const val WAIT = 1
        const val LISTEN = 2
    }
}
