package com.servermaintance.panel.core

import io.ktor.client.engine.HttpClientEngineConfig
import io.ktor.client.engine.HttpClientEngineFactory

actual fun createEngine(): HttpClientEngineFactory<*> = io.ktor.client.engine.cio.CIO

actual fun configureHttpEngine(engineConfig: HttpClientEngineConfig) {}
