package com.servermaintance.panel.core

import io.ktor.client.engine.HttpClientEngineConfig
import io.ktor.client.engine.HttpClientEngineFactory

/**
 * 与 Desktop 一致使用 CIO：OkHttp 在 Ktor 3.6 上对 WebSocket 的 HttpTimeout / ping 行为
 * 与桌面 CIO 不一致，实测会导致 Android 侧 `/ws`、`/term` 持续重连。
 */
actual fun createEngine(): HttpClientEngineFactory<*> = io.ktor.client.engine.cio.CIO

actual fun configureHttpEngine(engineConfig: HttpClientEngineConfig) {}
