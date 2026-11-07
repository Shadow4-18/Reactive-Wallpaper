package com.reactivewallpaper;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Bitmap;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.google.mlkit.vision.barcode.common.Barcode;
import com.google.mlkit.vision.codescanner.GmsBarcodeScanner;
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions;
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;

import java.util.EnumMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Shares looks as QR codes and links ({@link LookCode}), and scans them with
 * Google's code scanner (Play services; Resona itself never gets the camera).
 */
final class LookShare {
    private static final String TAG = "LookShare";

    private LookShare() {}

    /** Shows a look's QR code, with a button to send it as a link instead. */
    static void show(Activity activity, String name, WallpaperSettings settings) {
        String code = LookCode.encode(name, settings);
        int size = Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 260,
                activity.getResources().getDisplayMetrics()));
        Bitmap qr = qrBitmap(code, size);
        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        int pad = size / 12;
        box.setPadding(pad, pad, pad, 0);
        if (qr != null) {
            ImageView image = new ImageView(activity);
            image.setImageBitmap(qr);
            // A white quiet zone around the code, so any camera can read it on a dark screen.
            image.setBackgroundColor(0xFFFFFFFF);
            image.setPadding(pad / 2, pad / 2, pad / 2, pad / 2);
            box.addView(image, new LinearLayout.LayoutParams(size + pad, size + pad));
        }
        TextView hint = new TextView(activity);
        hint.setText("Scan it in Resona (Looks > Scan a look). The look comes without the picture, "
                + "so it applies to whatever picture the other phone uses.");
        hint.setTextColor(activity.getColor(R.color.text_secondary));
        hint.setPadding(0, pad, 0, 0);
        box.addView(hint);
        new AlertDialog.Builder(activity)
                .setTitle(name)
                .setView(box)
                .setPositiveButton("Send as link", (d, w) -> {
                    Intent send = new Intent(Intent.ACTION_SEND)
                            .setType("text/plain")
                            .putExtra(Intent.EXTRA_TEXT, "My Resona wallpaper look “" + name + "”.\n"
                                    + "To add it: copy this message, then in Resona open Looks > Paste a look "
                                    + "(or share this message to Resona).\n" + code);
                    activity.startActivity(Intent.createChooser(send, "Share look"));
                })
                .setNegativeButton("Close", null)
                .show();
    }

    /** Reads a look from whatever was last copied (a whole message is fine). */
    static void paste(Activity activity, Consumer<LookCode.Look> onLook) {
        android.content.ClipboardManager clipboard =
                (android.content.ClipboardManager) activity.getSystemService(android.content.Context.CLIPBOARD_SERVICE);
        CharSequence text = null;
        if (clipboard != null && clipboard.hasPrimaryClip() && clipboard.getPrimaryClip() != null
                && clipboard.getPrimaryClip().getItemCount() > 0) {
            text = clipboard.getPrimaryClip().getItemAt(0).coerceToText(activity);
        }
        LookCode.Look look = text != null ? LookCode.decode(text.toString()) : null;
        if (look == null) {
            Toast.makeText(activity, "Copy a Resona look message or link first, then tap Paste a look.",
                    Toast.LENGTH_LONG).show();
        } else {
            onLook.accept(look);
        }
    }

    /** Opens Google's code scanner and hands back a look if one is scanned. */
    static void scan(Activity activity, Consumer<LookCode.Look> onLook) {
        try {
            GmsBarcodeScanner scanner = GmsBarcodeScanning.getClient(activity,
                    new GmsBarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build());
            scanner.startScan()
                    .addOnSuccessListener(barcode -> {
                        LookCode.Look look = LookCode.decode(barcode.getRawValue());
                        if (look == null) {
                            Toast.makeText(activity, "That code isn't a Resona look.", Toast.LENGTH_LONG).show();
                        } else {
                            onLook.accept(look);
                        }
                    })
                    .addOnFailureListener(e -> {
                        Log.w(TAG, "Scan failed", e);
                        Toast.makeText(activity, "Couldn't open the scanner. It needs Google Play services; "
                                + "if it's just been installed, try again in a minute.", Toast.LENGTH_LONG).show();
                    });
        } catch (Throwable t) {
            Log.w(TAG, "Scanner unavailable", t);
            Toast.makeText(activity, "Scanning isn't available on this phone. Ask for the link instead.",
                    Toast.LENGTH_LONG).show();
        }
    }

    /** Asks whether to add a received look to the saved looks; {@code then} runs after adding. */
    static void offer(Activity activity, LookCode.Look look, Runnable then) {
        new AlertDialog.Builder(activity)
                .setTitle("Add “" + look.name + "”?")
                .setMessage("Someone shared a Resona look. Add it to your looks? It uses your own picture.")
                .setPositiveButton("Add", (d, w) -> {
                    PresetStore.save(activity, look.name, look.settings, false);
                    Toast.makeText(activity, "Added “" + look.name + "” to your looks", Toast.LENGTH_SHORT).show();
                    if (then != null) {
                        then.run();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    static Bitmap qrBitmap(String text, int size) {
        try {
            Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
            hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M);
            hints.put(EncodeHintType.MARGIN, 0);
            BitMatrix m = new QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, hints);
            int w = m.getWidth();
            int h = m.getHeight();
            int[] px = new int[w * h];
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    px[y * w + x] = m.get(x, y) ? 0xFF000000 : 0xFFFFFFFF;
                }
            }
            return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888);
        } catch (Exception e) {
            Log.w(TAG, "Couldn't make a QR code", e);
            return null;
        }
    }
}
