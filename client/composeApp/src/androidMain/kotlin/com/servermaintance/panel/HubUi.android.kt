package com.servermaintance.panel

import android.os.Handler
import android.os.Looper

private val hubMainHandler = Handler(Looper.getMainLooper())

internal actual fun publishHubUpdate(block: () -> Unit) {
    if (Looper.myLooper() == Looper.getMainLooper()) block()
    else hubMainHandler.post(block)
}
