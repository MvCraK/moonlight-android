// EXTENSION DEVELOPMENT [EXT-SS-NETWORK] [ADDED]
package com.limelight.extensions.ss;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;

import com.limelight.PcView;

/**
 * Registered as the application class in AndroidManifest.xml so the SS config is
 * loaded for every entry point (launcher, shortcuts, stream resume); the
 * connection itself always starts off in a new process. Attaches
 * the home toolbar button through lifecycle callbacks, keeping PcView untouched.
 */
public class SsApplication extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        SsRoutes.init(this);
        registerActivityLifecycleCallbacks(new ActivityLifecycleCallbacks() {
            @Override public void onActivityStarted(Activity activity) {
                if (activity instanceof PcView) SsToolbar.attach(activity);
            }
            @Override public void onActivityResumed(Activity activity) {
                if (activity instanceof PcView) SsToolbar.resume(activity);
            }
            @Override public void onActivityPaused(Activity activity) {
                if (activity instanceof PcView) SsToolbar.pause(activity);
            }
            @Override public void onActivityDestroyed(Activity activity) {
                if (activity instanceof PcView) SsToolbar.detach(activity);
            }
            @Override public void onActivityCreated(Activity activity, Bundle savedInstanceState) {}
            @Override public void onActivityStopped(Activity activity) {}
            @Override public void onActivitySaveInstanceState(Activity activity, Bundle outState) {}
        });
    }
}
