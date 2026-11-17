package com.chessassist

import android.app.Application

class ChessPilotApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Service binders can arrive before unlock. Do not read user settings here.
        ModuleConnection.initialize()
    }
}
