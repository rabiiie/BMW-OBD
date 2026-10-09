package com.rabie.bmwobd

import android.app.Activity
import android.app.Application
import android.os.Bundle
import androidx.compose.ui.graphics.Color
import com.rabie.bmwobd.settings.SettingsStore
import com.rabie.bmwobd.trips.TripStore
import com.rabie.bmwobd.ui.Bmw
import com.rabie.bmwobd.vehicle.VehicleStore
import java.io.File

class BmwObdApp : Application() {

    lateinit var trips: TripStore
        private set

    lateinit var vehicles: VehicleStore
        private set

    lateinit var settings: SettingsStore
        private set

    lateinit var controller: ObdController
        private set

    lateinit var bubble: Bubble
        private set

    override fun onCreate() {
        super.onCreate()
        trips = TripStore(File(filesDir, "trips")) { key -> key?.let(vehicles::byKey)?.diesel ?: true }
        vehicles = VehicleStore(getSharedPreferences("vehicles", MODE_PRIVATE))
        settings = SettingsStore(getSharedPreferences("settings", MODE_PRIVATE))
        Bmw.Accent = Color(settings.state.value.accent.argb)
        controller = ObdController(this, trips, vehicles, settings)
        bubble = Bubble(this, controller)
        registerActivityLifecycleCallbacks(VisibilityTracker())
    }

    /** Avisa a la burbuja de si la app esta en pantalla: solo sale cuando no lo esta. */
    private inner class VisibilityTracker : ActivityLifecycleCallbacks {
        private var started = 0

        override fun onActivityStarted(activity: Activity) {
            started++
            bubble.onAppVisible(true)
        }

        override fun onActivityStopped(activity: Activity) {
            started--
            bubble.onAppVisible(started > 0)
        }

        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
        override fun onActivityResumed(activity: Activity) = Unit
        override fun onActivityPaused(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
        override fun onActivityDestroyed(activity: Activity) = Unit
    }
}
