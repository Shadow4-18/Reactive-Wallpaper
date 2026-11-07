package com.reactivewallpaper;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.util.TypedValue;
import android.view.MotionEvent;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * The tap-along sync calibration: plays clicks through the current output,
 * collects the user's taps and offers the measured delay.
 */
final class CalibrationDialog {
    interface Host {
        /** Calibration needs the audio capture to itself. */
        void pauseAudio();

        void resumeAudio();

        /** The user chose to use this delay for that output. */
        void onMeasured(boolean bluetooth, int delayMs);
    }

    private CalibrationDialog() {}

    /** @param bluetooth whether sound is going to Bluetooth (which delay is being measured) */
    @SuppressLint("ClickableViewAccessibility")
    static void show(Activity activity, boolean bluetooth, Host host) {
        String output = bluetooth ? "Bluetooth" : "phone speaker / wired";

        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(activity, 24), dp(activity, 8), dp(activity, 24), 0);
        TextView text = new TextView(activity);
        text.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        text.setTextColor(activity.getColor(R.color.text_primary));
        text.setText("Measures the delay for " + output + ".\n\n"
                + "Your music will pause and you'll hear " + BeatCalibration.TOTAL_CLICKS
                + " clicks. Tap the big button exactly in time with each click you hear. "
                + "The first " + BeatCalibration.WARMUP_CLICKS + " are practice.\n\n"
                + "Turn the volume up so the clicks are easy to hear.");
        box.addView(text);
        TextView status = new TextView(activity);
        status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        status.setTextColor(activity.getColor(R.color.accent));
        status.setPadding(0, dp(activity, 12), 0, dp(activity, 12));
        box.addView(status);
        Button pad = new Button(activity);
        pad.setText("TAP");
        pad.setTextSize(TypedValue.COMPLEX_UNIT_SP, 28);
        pad.setEnabled(false);
        box.addView(pad, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(activity, 180)));

        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle("Calibrate sync")
                .setView(box)
                .setPositiveButton("Start", null)
                .setNegativeButton("Cancel", null)
                .create();
        BeatCalibration[] session = new BeatCalibration[1];
        int[] measured = {-1};

        dialog.setOnShowListener(d -> {
            Button positive = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            positive.setOnClickListener(v -> {
                if (measured[0] >= 0) {
                    host.onMeasured(bluetooth, measured[0]);
                    dialog.dismiss();
                    return;
                }
                host.pauseAudio();
                session[0] = new BeatCalibration(activity, new BeatCalibration.Callback() {
                    @Override
                    public void onProgress(int clicksPlayed, int taps) {
                        status.setText("Click " + clicksPlayed + " of " + BeatCalibration.TOTAL_CLICKS
                                + "   ·   taps: " + taps);
                    }

                    @Override
                    public void onFinished(BeatCalibration.Result result) {
                        session[0] = null;
                        host.resumeAudio();
                        pad.setEnabled(false);
                        if (result.ok) {
                            measured[0] = result.delayMs;
                            status.setText("Measured delay: " + result.delayMs + " ms. " + result.message);
                            positive.setText("Use " + result.delayMs + " ms");
                        } else {
                            status.setText(result.message);
                            positive.setText("Try again");
                        }
                        positive.setEnabled(true);
                    }
                });
                if (!session[0].start()) {
                    session[0] = null;
                    host.resumeAudio();
                    status.setText("Phone audio can't be captured on this device. Try microphone mode instead.");
                    return;
                }
                positive.setEnabled(false);
                pad.setEnabled(true);
            });
            // Time taps from the touch itself (not the click callback) for accuracy.
            pad.setOnTouchListener((v, event) -> {
                if (event.getActionMasked() == MotionEvent.ACTION_DOWN && session[0] != null) {
                    session[0].tap(event.getEventTime());
                    v.setPressed(true);
                } else if (event.getActionMasked() == MotionEvent.ACTION_UP
                        || event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                    v.setPressed(false);
                }
                return true;
            });
        });
        dialog.setOnDismissListener(d -> {
            if (session[0] != null) {
                session[0].cancel();
                session[0] = null;
                host.resumeAudio();
            }
        });
        dialog.show();
    }

    private static int dp(Activity activity, float value) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                activity.getResources().getDisplayMetrics()));
    }
}
