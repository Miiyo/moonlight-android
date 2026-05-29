package com.limelight.binding.input;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.util.Log;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

import com.limelight.LimeLog;

/**
 * Accessibility service that intercepts physical keyboard events — including
 * meta/Windows key, Alt+Tab, and other system-intercepted shortcuts — and
 * forwards them to the active Moonlight streaming session.
 *
 * <p>Android's normal key-dispatch pipeline lets the OS consume many key
 * combinations (e.g. WIN, WIN+D, ALT+TAB) before the focused Activity ever
 * sees them.  An AccessibilityService receives a {@link #onKeyEvent} callback
 * <em>before</em> that consumption, giving Moonlight a chance to relay the
 * raw keystroke to the remote PC.</p>
 *
 * <h3>How it works</h3>
 * <ol>
 *   <li>The service starts when the user enables it in Android Settings →
 *       Accessibility, or programmatically via the prompt shown in
 *       {@link com.limelight.Game}.</li>
 *   <li>When a Moonlight stream is active, {@link com.limelight.Game} sends
 *       {@link #ACTION_STREAMING_START} so the service knows to forward
 *       events.</li>
 *   <li>Every {@link KeyEvent} that arrives in {@link #onKeyEvent} is
 *       broadcast via {@link #ACTION_KEY_EVENT} so {@link com.limelight.Game}
 *       can translate and send it over the network.</li>
 *   <li>When the stream ends, {@link com.limelight.Game} sends
 *       {@link #ACTION_STREAMING_STOP}; the service reverts to passthrough
 *       mode so the rest of the OS behaves normally.</li>
 * </ol>
 *
 * <h3>Key events broadcast extras</h3>
 * <ul>
 *   <li>{@link #EXTRA_KEY_ACTION}   – {@link KeyEvent#ACTION_DOWN} or UP</li>
 *   <li>{@link #EXTRA_KEY_CODE}     – Android keycode</li>
 *   <li>{@link #EXTRA_META_STATE}   – raw meta-state flags from the event</li>
 *   <li>{@link #EXTRA_DEVICE_ID}    – source InputDevice id</li>
 *   <li>{@link #EXTRA_SCAN_CODE}    – hardware scan code</li>
 *   <li>{@link #EXTRA_REPEAT_COUNT} – repeat count (0 = first press)</li>
 *   <li>{@link #EXTRA_EVENT_TIME}   – event time in ms</li>
 * </ul>
 */
public class PhysicalKeyboardAccessibilityService extends AccessibilityService {

    private static final String TAG = "PhysKeyboardA11y";

    // -----------------------------------------------------------------------
    // Public intent / extra constants (used by Game.java)
    // -----------------------------------------------------------------------

    /** Sent by Game to tell the service a stream is active. */
    public static final String ACTION_STREAMING_START =
            "com.limelight.accessibility.STREAMING_START";

    /** Sent by Game to tell the service the stream has ended. */
    public static final String ACTION_STREAMING_STOP =
            "com.limelight.accessibility.STREAMING_STOP";

    /**
     * Broadcast emitted BY the service for every physical key event that
     * should be forwarded to the remote PC.
     */
    public static final String ACTION_KEY_EVENT =
            "com.limelight.accessibility.KEY_EVENT";

    public static final String EXTRA_KEY_ACTION    = "action";
    public static final String EXTRA_KEY_CODE      = "keyCode";
    public static final String EXTRA_META_STATE    = "metaState";
    public static final String EXTRA_DEVICE_ID     = "deviceId";
    public static final String EXTRA_SCAN_CODE     = "scanCode";
    public static final String EXTRA_REPEAT_COUNT  = "repeatCount";
    public static final String EXTRA_EVENT_TIME    = "eventTime";

    // -----------------------------------------------------------------------
    // Static handle so Game.java can query whether the service is running
    // -----------------------------------------------------------------------

    private static PhysicalKeyboardAccessibilityService sInstance;

    /** @return the running service instance, or {@code null} if not enabled. */
    public static PhysicalKeyboardAccessibilityService getInstance() {
        return sInstance;
    }

    // -----------------------------------------------------------------------
    // Instance state
    // -----------------------------------------------------------------------

    /**
     * True while a Moonlight stream is active.  Only forward key events when
     * this flag is set so that the accessibility service is transparent to
     * the rest of the Android UI at all other times.
     */
    private volatile boolean streamingActive = false;

    private BroadcastReceiver streamingStateReceiver;

    // -----------------------------------------------------------------------
    // AccessibilityService lifecycle
    // -----------------------------------------------------------------------

    @Override
    public void onServiceConnected() {
        sInstance = this;

        // Configure: we only need to intercept key events — no UI events.
        AccessibilityServiceInfo info = new AccessibilityServiceInfo();
        info.flags = AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS;
        info.eventTypes = AccessibilityEvent.TYPES_ALL_MASK;
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC;
        setServiceInfo(info);

        // Listen for streaming-state broadcasts from Game.java
        streamingStateReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (intent == null) return;
                String action = intent.getAction();
                if (ACTION_STREAMING_START.equals(action)) {
                    streamingActive = true;
                    LimeLog.info(TAG + ": streaming started – meta key capture enabled");
                } else if (ACTION_STREAMING_STOP.equals(action)) {
                    streamingActive = false;
                    LimeLog.info(TAG + ": streaming stopped – meta key capture disabled");
                }
            }
        };

        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_STREAMING_START);
        filter.addAction(ACTION_STREAMING_STOP);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(streamingStateReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(streamingStateReceiver, filter);
        }

        LimeLog.info(TAG + ": service connected");
    }

    @Override
    public void onDestroy() {
        sInstance = null;
        streamingActive = false;
        if (streamingStateReceiver != null) {
            try {
                unregisterReceiver(streamingStateReceiver);
            } catch (Exception e) {
                // ignore
            }
            streamingStateReceiver = null;
        }
        LimeLog.info(TAG + ": service destroyed");
        super.onDestroy();
    }

    // -----------------------------------------------------------------------
    // Key interception
    // -----------------------------------------------------------------------

    /**
     * Called by Android for every key event, before the focused window
     * receives it.  Return {@code true} to consume (prevent system handling),
     * {@code false} to pass the event through normally.
     */
    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        if (!streamingActive) {
            // Not streaming — let Android handle everything normally.
            return false;
        }

        int keyCode = event.getKeyCode();
        int action  = event.getAction();

        // Only intercept key-down and key-up; ignore other action types
        // (ACTION_MULTIPLE is legacy and rarely used for physical keys).
        if (action != KeyEvent.ACTION_DOWN && action != KeyEvent.ACTION_UP) {
            return false;
        }

        // We intercept *all* keys while streaming so that combos like
        // Win+D, Alt+Tab, Ctrl+Alt+Del, etc. are forwarded instead of
        // consumed by the Android launcher / system UI.
        //
        // Exception: volume keys (handled separately by the OS for audio
        // feedback) and the power button are excluded — intercepting those
        // would be jarring and they are not normally sent to remote PCs.
        if (isExcludedSystemKey(keyCode)) {
            return false;
        }

        // Forward the event to Game.java via a local broadcast.
        broadcastKeyEvent(event);

        // Returning true consumes the event — the OS (and the focused
        // Activity's normal onKeyDown/onKeyUp path) will NOT receive it
        // again.  Game.java will handle the translated keystroke itself.
        return true;
    }

    /**
     * Keys we explicitly exclude from interception even during streaming.
     * These are either handled at the hardware level or would break the
     * device if suppressed.
     */
    private static boolean isExcludedSystemKey(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_VOLUME_UP:
            case KeyEvent.KEYCODE_VOLUME_DOWN:
            case KeyEvent.KEYCODE_VOLUME_MUTE:
            case KeyEvent.KEYCODE_POWER:
            case KeyEvent.KEYCODE_SLEEP:
            case KeyEvent.KEYCODE_WAKEUP:
            case KeyEvent.KEYCODE_CAMERA:
                return true;
            default:
                return false;
        }
    }

    /**
     * Broadcasts a key event so {@link com.limelight.Game} can receive and
     * process it.  All relevant fields are included so Game can reconstruct
     * the complete keystroke without needing the original {@link KeyEvent}
     * object.
     */
    private void broadcastKeyEvent(KeyEvent event) {
        Intent intent = new Intent(ACTION_KEY_EVENT);
        intent.putExtra(EXTRA_KEY_ACTION,    event.getAction());
        intent.putExtra(EXTRA_KEY_CODE,      event.getKeyCode());
        intent.putExtra(EXTRA_META_STATE,    event.getMetaState());
        intent.putExtra(EXTRA_DEVICE_ID,     event.getDeviceId());
        intent.putExtra(EXTRA_SCAN_CODE,     event.getScanCode());
        intent.putExtra(EXTRA_REPEAT_COUNT,  event.getRepeatCount());
        intent.putExtra(EXTRA_EVENT_TIME,    event.getEventTime());
        intent.setPackage(getPackageName()); // Keep it within Moonlight
        sendBroadcast(intent);
    }

    // -----------------------------------------------------------------------
    // Required overrides (we don't use UI accessibility events)
    // -----------------------------------------------------------------------

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // Not used — we only care about key events.
    }

    @Override
    public void onInterrupt() {
        // Called when the service is interrupted; nothing to clean up here.
    }
}
