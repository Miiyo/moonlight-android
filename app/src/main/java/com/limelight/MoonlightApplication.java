package com.limelight;

import android.app.Application;
import com.google.android.material.color.DynamicColors;

public class MoonlightApplication extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        // Opt in to Monet dynamic color on Android 12+ (S+).
        // Falls back to the hardcoded M3 purple palette on older devices.
        DynamicColors.applyToActivitiesIfAvailable(this);
    }
}
