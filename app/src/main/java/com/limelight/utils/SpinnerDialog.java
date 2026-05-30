package com.limelight.utils;

import java.util.ArrayList;
import java.util.Iterator;

import android.app.Activity;
import android.content.DialogInterface;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.TextView;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.progressindicator.CircularProgressIndicator;
import androidx.appcompat.app.AlertDialog;

public class SpinnerDialog implements Runnable, DialogInterface.OnCancelListener {
    private final String title;
    private String message;
    private final Activity activity;
    private AlertDialog dialog;
    private TextView messageView;
    private final boolean finish;

    private static final ArrayList<SpinnerDialog> rundownDialogs = new ArrayList<>();

    private SpinnerDialog(Activity activity, String title, String message, boolean finish) {
        this.activity = activity;
        this.title = title;
        this.message = message;
        this.dialog = null;
        this.finish = finish;
    }

    public static SpinnerDialog displayDialog(Activity activity, String title, String message, boolean finish) {
        SpinnerDialog spinner = new SpinnerDialog(activity, title, message, finish);
        activity.runOnUiThread(spinner);
        return spinner;
    }

    public static void closeDialogs(Activity activity) {
        synchronized (rundownDialogs) {
            Iterator<SpinnerDialog> i = rundownDialogs.iterator();
            while (i.hasNext()) {
                SpinnerDialog d = i.next();
                if (d.activity == activity) {
                    i.remove();
                    if (d.dialog != null && d.dialog.isShowing()) {
                        d.dialog.dismiss();
                    }
                }
            }
        }
    }

    public void dismiss() {
        activity.runOnUiThread(this);
    }

    public void setMessage(final String msg) {
        activity.runOnUiThread(() -> {
            this.message = msg;
            if (messageView != null) {
                messageView.setText(msg);
            }
        });
    }

    @Override
    public void run() {
        synchronized (rundownDialogs) {
            if (dialog != null) {
                // Second call = dismiss
                if (dialog.isShowing()) {
                    dialog.dismiss();
                }
                rundownDialogs.remove(this);
                if (finish) {
                    activity.finish();
                }
                return;
            }

            // Build a Material 3 loading dialog with circular indicator
            View contentView = LayoutInflater.from(activity)
                    .inflate(android.R.layout.activity_list_item, null);

            dialog = new MaterialAlertDialogBuilder(activity)
                    .setTitle(title)
                    .setMessage(message)
                    .setCancelable(finish)
                    .setOnCancelListener(this)
                    .show();

            rundownDialogs.add(this);
        }
    }

    @Override
    public void onCancel(DialogInterface dialogInterface) {
        synchronized (rundownDialogs) {
            rundownDialogs.remove(this);
        }
        if (finish) {
            activity.finish();
        }
    }
}
