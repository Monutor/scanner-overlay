package com.scanner.overlay.calibration

import android.content.Context

data class SupportedBrowser(
    val packageName: String,
    val label: String
)

object SupportedBrowsers {
    const val YANDEX_BROWSER = "com.yandex.browser"
    const val CHROME = "com.android.chrome"
    const val CHROME_BETA = "com.chrome.beta"
    const val CHROME_DEV = "com.chrome.dev"
    const val BRAVE = "com.brave.browser"
    const val EDGE = "com.microsoft.emmx"

    private val candidates: List<SupportedBrowser> = listOf(
        SupportedBrowser(YANDEX_BROWSER, "Яндекс Браузер"),
        SupportedBrowser(CHROME, "Google Chrome"),
        SupportedBrowser(CHROME_BETA, "Chrome Beta"),
        SupportedBrowser(CHROME_DEV, "Chrome Dev"),
        SupportedBrowser(BRAVE, "Brave"),
        SupportedBrowser(EDGE, "Microsoft Edge")
    )

    val SUPPORTED_PACKAGES: Set<String> = candidates.map { it.packageName }.toSet()

    fun getInstalled(context: Context): List<SupportedBrowser> {
        val pm = context.packageManager
        return candidates.filter { browser ->
            runCatching { pm.getPackageInfo(browser.packageName, 0) }.isSuccess
        }
    }
}
