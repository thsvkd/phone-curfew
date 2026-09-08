package com.thsvkd.curfew

import android.app.Application
import com.thsvkd.curfew.collect.scheduleCollection

class CurfewApp : Application() {
    override fun onCreate() {
        super.onCreate()
        scheduleCollection(this)
    }
}
