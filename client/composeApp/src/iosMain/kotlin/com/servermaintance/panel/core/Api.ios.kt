package com.servermaintance.panel.core

import io.ktor.client.engine.HttpClientEngineConfig
import io.ktor.client.engine.HttpClientEngineFactory

/**
 * iOS 用 Darwin（NSURLSession），而不是 Android / Desktop 的 CIO。
 *
 * Darwin 走系统 TLS（Secure Transport）与系统信任库，等价于 Safari 的证书校验行为；
 * CIO 在 Kotlin/Native 上用的是 ktor 自实现的 TLS，对证书链与套件的支持面更窄，
 * 一旦某台面板服务器握手失败就是「完全连不上」，在无法本机编译的平台上最难排查。
 *
 * 注意：引擎差异会影响 WebSocket 时序（见 Api.android.kt 里 Android 弃用 OkHttp 的教训），
 * 所以 `/ws`、`/term` 的自动重连必须在真机上重点回归；若要退回 CIO，只改这一行即可。
 */
actual fun createEngine(): HttpClientEngineFactory<*> = io.ktor.client.engine.darwin.Darwin

actual fun configureHttpEngine(engineConfig: HttpClientEngineConfig) {}
