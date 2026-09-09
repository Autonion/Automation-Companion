package com.autonion.automationcompanion

import android.app.Activity
import android.app.Application
import android.os.Bundle
import com.autonion.automationcompanion.features.system_context_automation.location.helpers.LocationAutomationController

class AutomationCompanionApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            private var restored = false

            override fun onActivityResumed(activity: Activity) {
                if (restored) return
                restored = true
                // Force-stop removes system registrations. Recover on the first visible launch,
                // without resetting presence whenever a background geofence wakes the process.
                LocationAutomationController.requestReconcile(applicationContext, resetPresence = true)
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }
}
