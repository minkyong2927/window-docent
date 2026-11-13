package com.windowdocent

import android.annotation.SuppressLint
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.webkit.WebView
import android.webkit.WebViewClient
import com.windowdocent.core.LiveSignalSource
import com.windowdocent.pleos.PleosSignalSource
import com.windowdocent.pleos.PleosSpeaker
import com.windowdocent.pleos.PleosVoiceInput

/**
 * 웹 프로토타입 화면을 띄우고, 판단은 Kotlin 엔진이 맡음.
 * 신호 -> DocentBridge(window.Docent) -> DocentEngine -> 해설 방식 · 청중 · 명소 순서 · 사실 검증 -> 화면.
 * 해설은 Narrator 가 문장마다 소리로 냄 (Pleos TextToSpeech → 없으면 안드로이드 TTS · AppSpeaker).
 * 음성 명령은 Pleos 기기에서만 Pleos SpeechToText 로 들음 (PleosVoiceInput · 다른 기기는 화면의 흉내 단추).
 * 신호는 기본이 화면의 시연용 신호 판. 화면의 「차량 신호」 를 켜면 Pleos Connect SDK(PleosSignalSource)에서 받음.
 * Pleos 기기가 아니면 차량 신호가 붙지 않고 화면이 시연 신호로 돌아감.
 */
class MainActivity : Activity() {
    private lateinit var bridge: DocentBridge
    private var live: PleosSignalSource? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val voice = AppSpeaker(this)
        val ears = if (PleosSpeaker.available(this)) PleosVoiceInput(this) { why -> Log.w(TAG, "음성 명령을 들을 수 없음: $why") } else null
        bridge = DocentBridge(
            liveFactory = { PleosSignalSource(this, voiceWatch = ::watchNaviVoice).also { live = it } },
            onLiveStart = ::askLocationIfNeeded,
            runner = { block -> runOnUiThread(block) },
            speaker = voice,
            speakerName = { voice.name },
            voiceInput = ears,
        )
        val web = WebView(this)
        web.settings.javaScriptEnabled = true
        web.settings.mediaPlaybackRequiresUserGesture = false
        // 화면이 뜰 때부터 window.Docent 로 엔진을 부를 수 있게 먼저 붙임
        web.addJavascriptInterface(bridge, "Docent")
        web.webViewClient = WebViewClient()
        setContentView(web)
        web.loadUrl("file:///android_asset/index.html")
    }

    /** 내비 안내 음성 감시 (Pleos SDK 에 없는 값 · AudioManager) · 멈추는 함수를 돌려줌 */
    private fun watchNaviVoice(onChange: (Boolean) -> Unit): () -> Unit {
        val watcher = NaviVoiceWatcher(this, onChange)
        watcher.start()
        return watcher::stop
    }

    /** 위치(속도 계산용)는 실행 중에 권한을 받아야 함. 받으면 못 붙은 모듈을 다시 붙임 */
    private fun askLocationIfNeeded(source: LiveSignalSource) {
        val pleos = source as? PleosSignalSource ?: return
        if (pleos.needsLocationPermission()) {
            requestPermissions(arrayOf(PleosSignalSource.LOCATION_PERMISSION), REQUEST_LOCATION)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_LOCATION && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            live?.retry()
        }
    }

    override fun onDestroy() {
        bridge.close()
        super.onDestroy()
    }

    private companion object {
        const val TAG = "MainActivity"
        const val REQUEST_LOCATION = 41
    }
}
