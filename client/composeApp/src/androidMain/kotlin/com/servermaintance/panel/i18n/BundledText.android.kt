package com.servermaintance.panel.i18n

private object BundledTextAnchor

actual fun readBundledText(path: String): String? =
    BundledTextAnchor::class.java.classLoader?.getResourceAsStream(path)?.use {
        it.bufferedReader(Charsets.UTF_8).readText()
    }
