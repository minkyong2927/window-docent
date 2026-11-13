package com.windowdocent

import android.annotation.SuppressLint
import android.app.Activity
import android.os.Bundle
import android.webkit.WebView
import android.webkit.WebViewClient

/**
 * 1차 버전: 웹 프로토타입 화면을 띄우고, 판단은 Kotlin 엔진이 맡음.
 * 화면의 시연용 신호 판 -> DocentBridge(window.Docent) -> DocentEngine -> 해설 방식 · 청중 · 명소 순서 · 사실 검증 -> 화면.
 * 2차에서 신호 판 대신 Pleos Connect SDK 신호를 VehicleSignalSource 구현체로 연결.
 */
class MainActivity : Activity() {

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val web = WebView(this)
        web.settings.javaScriptEnabled = true
        web.settings.mediaPlaybackRequiresUserGesture = false
        // 화면이 뜰 때부터 window.Docent 로 엔진을 부를 수 있게 먼저 붙임
        web.addJavascriptInterface(DocentBridge(), "Docent")
        web.webViewClient = WebViewClient()
        setContentView(web)
        web.loadUrl("file:///android_asset/index.html")
    }
}
