package app.geoshift

import android.app.Application
import app.geoshift.data.Geoshift

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        // Registers for the Xposed framework's service binder as early as possible.
        Geoshift.get(this)
    }
}
