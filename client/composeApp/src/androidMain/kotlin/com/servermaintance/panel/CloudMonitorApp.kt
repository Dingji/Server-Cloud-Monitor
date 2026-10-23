package com.servermaintance.panel

import android.app.Application

class CloudMonitorApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppContextHolder.init(this)
    }
}
