package com.windowdocent

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.windowdocent.core.Speaker
import com.windowdocent.core.SpeakerChain
import com.windowdocent.pleos.PleosSpeaker

/**
 * 앱이 쓰는 말하는 장치. Pleos 기기면 Pleos TextToSpeech SDK, 아니면 안드로이드 기본 TTS,
 * 그것도 못 쓰면 소리 없이 글 길이만큼 시간이 흐르는 장치 (화면의 해설 글은 그대로 진행).
 * 장치를 만들다 · 부르다 예외가 나도 앱은 꺼지지 않고 다음 장치로 넘어감 (SpeakerChain · 11/14 에뮬레이터에서 음성 권한이 없어 앱이 꺼진 일).
 * 어느 장치가 말하는지는 name 으로 화면에 보임.
 */
class AppSpeaker private constructor(private val chain: SpeakerChain) : Speaker by chain {
    constructor(context: Context) : this(chainFor(context.applicationContext))

    val name: String get() = chain.name

    private companion object {
        const val TAG = "AppSpeaker"

        fun chainFor(app: Context): SpeakerChain {
            val pleos = try {
                PleosSpeaker.available(app)
            } catch (t: Throwable) {
                Log.w(TAG, "Pleos 음성 서비스 확인 실패", t)
                false
            }
            return SpeakerChain(
                listOfNotNull(
                    if (pleos) SpeakerChain.Maker("Pleos TextToSpeech") { u -> PleosSpeaker(app, u) } else null,
                    SpeakerChain.Maker("안드로이드 TTS") { u -> AndroidSpeaker(app, u) },
                    SpeakerChain.Maker("소리 없음") { SilentSpeaker() },
                ),
                log = { msg, t -> Log.w(TAG, msg, t) },
            )
        }
    }
}

/** 안드로이드 기본 TTS (Pleos 기기가 아닌 휴대폰 등에서 APK 를 시연할 때) */
class AndroidSpeaker(context: Context, private val unavailable: (String) -> Unit) : Speaker {
    private var ready = false
    private var failedInit = false
    private var pending: Triple<String, String, Pair<() -> Unit, (String) -> Unit>>? = null
    private val waiting = HashMap<String, Pair<() -> Unit, (String) -> Unit>>()
    private var seq = 0
    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status -> onInit(status) }

    init {
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String) {}
            override fun onDone(utteranceId: String) {
                take(utteranceId)?.first?.invoke()
            }

            @Suppress("OVERRIDE_DEPRECATION")
            override fun onError(utteranceId: String) {
                take(utteranceId)?.second?.invoke("TTS 오류")
            }

            override fun onError(utteranceId: String, errorCode: Int) {
                take(utteranceId)?.second?.invoke("TTS 오류 $errorCode")
            }
        })
    }

    private fun onInit(status: Int) {
        val next = synchronized(this) {
            if (status == TextToSpeech.SUCCESS) ready = true else failedInit = true
            pending.also { pending = null }
        }
        if (status != TextToSpeech.SUCCESS) {
            unavailable("초기화 실패 $status")
            next?.third?.second?.invoke("TTS 없음")
            return
        }
        next?.let { (text, language, cb) -> start(text, language, cb) }
    }

    override fun speak(text: String, language: String, done: () -> Unit, failed: (String) -> Unit) {
        val cb = done to failed
        val now = synchronized(this) {
            when {
                failedInit -> "fail"
                !ready -> { pending = Triple(text, language, cb); "wait" }
                else -> "speak"
            }
        }
        when (now) {
            "fail" -> failed("TTS 없음")
            "speak" -> start(text, language, cb)
        }
    }

    private fun start(text: String, language: String, cb: Pair<() -> Unit, (String) -> Unit>) {
        val id = synchronized(this) { "u${++seq}".also { waiting[it] = cb } }
        val locale = PleosSpeaker.localeOf(language)
        if (tts.isLanguageAvailable(locale) >= TextToSpeech.LANG_AVAILABLE) tts.language = locale
        if (tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, id) != TextToSpeech.SUCCESS) {
            take(id)?.second?.invoke("speak 실패")
        }
    }

    @Synchronized
    private fun take(id: String) = waiting.remove(id)

    override fun stop() {
        synchronized(this) {
            pending = null
            waiting.clear()
        }
        tts.stop()
    }

    override fun release() {
        stop()
        tts.shutdown()
    }
}

/** 소리 없이 글 길이만큼 기다렸다 '다 말함' (말하는 장치가 없는 기기에서도 해설이 진행되게) */
class SilentSpeaker(private val msPerChar: Long = 90, private val minMs: Long = 800) : Speaker {
    private val handler = Handler(Looper.getMainLooper())
    private var task: Runnable? = null

    override fun speak(text: String, language: String, done: () -> Unit, failed: (String) -> Unit) {
        val r = Runnable { done() }
        synchronized(this) { task = r }
        handler.postDelayed(r, maxOf(minMs, text.length * msPerChar))
    }

    override fun stop() {
        synchronized(this) { task?.let(handler::removeCallbacks); task = null }
    }
}
