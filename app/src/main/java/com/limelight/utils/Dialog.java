package com.limelight.utils;

import java.util.ArrayList;

import android.app.Activity;
import android.content.DialogInterface;

import androidx.appcompat.app.AlertDialog;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import com.limelight.R;

public class Dialog implements Runnable {
    private final String title;
    private final String message;
    private final Activity activity;
    private final Runnable runOnDismiss;

    private AlertDialog alert;

    private static final ArrayList<Dialog> rundownDialogs = new ArrayList<>();

    private Dialog(Activity activity, String title, String message, Runnable runOnDismiss) {
        this.activity = activity;
        this.title = title;
        this.message = message;
        this.runOnDismiss = runOnDismiss;
    }

    public static void closeDialogs() {
        synchronized (rundownDialogs) {
            for (Dialog d : rundownDialogs) {
                if (d.alert != null && d.alert.isShowing()) {
                    d.alert.dismiss();
                }
            }
            rundownDialogs.clear();
        }
    }

    public static void displayDialog(final Activity activity, String title, String message, final boolean endAfterDismiss) {
        activity.runOnUiThread(new Dialog(activity, title, message, () -> {
            if (endAfterDismiss) {
                activity.finish();
            }
        }));
    }

    public static void displayDialog(Activity activity, String title, String message, Runnable runOnDismiss) {
        activity.runOnUiThread(new Dialog(activity, title, message, runOnDismiss));
    }

    @Override
    public void run() {
        if (activity.isFinishing()) return;

        alert = new MaterialAlertDialogBuilder(activity)
                .setTitle(title)
                .setMessage(message)
                .setCancelable(false)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    synchronized (rundownDialogs) {
                        rundownDialogs.remove(Dialog.this);
                    }
                    runOnDismiss.run();
                })
                .setNeutralButton(R.string.help, (dialog, which) -> {
                    synchronized (rundownDialogs) {
                        rundownDialogs.remove(Dialog.this);
                    }
                    runOnDismiss.run();
                    HelpLauncher.launchTroubleshooting(activity);
                })
                .show();

        // Focus the positive button for TV/controller nav
        alert.getButton(AlertDialog.BUTTON_POSITIVE).requestFocus();

        synchronized (rundownDialogs) {
            rundownDialogs.add(this);
        }
    }
}
