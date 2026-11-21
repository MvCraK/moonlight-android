package com.limelight.ss

import android.app.Activity
import android.app.Application
import android.os.Bundle
import com.limelight.PcView

/**
 * Single entry point called from LimelightApplication. Restores routing state and
 * attaches the toolbar button to PcView through lifecycle callbacks, so upstream
 * activities and layouts need no edits.
 */
object VplusSs {
    fun install(app: Application) {
        SsRoutes.init(app)
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                if (activity is PcView) SsToolbar.attach(activity)
            }
            override fun onActivityResumed(activity: Activity) {
                if (activity is PcView) SsToolbar.resume(activity)
            }
            override fun onActivityPaused(activity: Activity) {
                if (activity is PcView) SsToolbar.pause(activity)
            }
            override fun onActivityDestroyed(activity: Activity) {
                if (activity is PcView) SsToolbar.detach(activity)
            }
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
        })
    }
}
