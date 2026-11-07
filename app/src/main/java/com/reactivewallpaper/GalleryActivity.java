package com.reactivewallpaper;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Browses the shared look gallery. Each look is previewed on the user's own
 * picture; "Use" loads it into the editor, "Save" adds it to the saved looks.
 */
public class GalleryActivity extends Activity {
    /** Result extra: the look code to load into the editor. */
    static final String EXTRA_CODE = "code";
    /** In: the editor's current look as a code, for suggesting it to the gallery. */
    static final String EXTRA_CURRENT = "current";

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private LinearLayout list;
    private TextView status;
    private Bitmap picture;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(getColor(R.color.background));
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            v.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                    insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            return insets.consumeSystemWindowInsets();
        });
        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(dp(4), 0, dp(4), 0);
        Button back = flat("Close");
        back.setOnClickListener(v -> finish());
        TextView title = new TextView(this);
        title.setText("Look gallery");
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        title.setTextColor(getColor(R.color.text_primary));
        title.setGravity(Gravity.CENTER);
        Button suggest = flat("Suggest yours");
        suggest.setOnClickListener(v -> suggest());
        top.addView(back);
        top.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        top.addView(suggest);
        root.addView(top);

        ScrollView scroll = new ScrollView(this);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(12), 0, dp(12), dp(16));
        status = new TextView(this);
        status.setText("Loading the gallery…");
        status.setTextColor(getColor(R.color.text_secondary));
        status.setPadding(dp(4), dp(8), dp(4), dp(8));
        content.addView(status);
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        content.addView(list);
        scroll.addView(content);
        root.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);
        load();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdownNow();
    }

    private void load() {
        DisplayMetrics dm = getResources().getDisplayMetrics();
        int cardW = (dm.widthPixels - dp(24)) / columns() - dp(8);
        int cardH = Math.round(cardW * Math.max(dm.widthPixels, dm.heightPixels)
                / (float) Math.min(dm.widthPixels, dm.heightPixels));
        executor.execute(() -> {
            List<Gallery.Entry> entries;
            try {
                entries = Gallery.fetch();
            } catch (IOException e) {
                main.post(() -> status.setText("Couldn't load the gallery. Check your connection and try again."));
                return;
            }
            picture = ImageStore.load(ImageStore.draftFile(this), cardW, cardH);
            List<Gallery.Entry> shown = entries;
            main.post(() -> {
                if (isDestroyed()) {
                    return;
                }
                status.setText(shown.isEmpty() ? "The gallery is empty right now."
                        : "Each look is shown on your picture. Use it to load it into the editor, or save it "
                        + "to your looks.");
                ImageView[] images = buildCards(shown, cardW, cardH);
                // Previews one at a time, top first.
                for (int i = 0; i < shown.size(); i++) {
                    int index = i;
                    executor.execute(() -> {
                        Bitmap p = Gallery.preview(picture, shown.get(index).look.settings, cardW, cardH,
                                getResources().getDisplayMetrics().density);
                        main.post(() -> {
                            if (!isDestroyed()) {
                                images[index].setImageBitmap(p);
                            }
                        });
                    });
                }
            });
        });
    }

    private int columns() {
        return getResources().getConfiguration().screenWidthDp >= 600 ? 3 : 2;
    }

    private ImageView[] buildCards(List<Gallery.Entry> entries, int cardW, int cardH) {
        ImageView[] images = new ImageView[entries.size()];
        LinearLayout row = null;
        for (int i = 0; i < entries.size(); i++) {
            if (i % columns() == 0) {
                row = new LinearLayout(this);
                list.addView(row);
            }
            Gallery.Entry e = entries.get(i);
            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setPadding(dp(4), dp(8), dp(4), dp(8));
            ImageView image = new ImageView(this);
            image.setBackgroundColor(getColor(R.color.surface));
            image.setScaleType(ImageView.ScaleType.CENTER_CROP);
            image.setClipToOutline(true);
            image.setOutlineProvider(new android.view.ViewOutlineProvider() {
                @Override
                public void getOutline(android.view.View view, android.graphics.Outline outline) {
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), dp(12));
                }
            });
            images[i] = image;
            card.addView(image, new LinearLayout.LayoutParams(cardW, cardH));
            TextView name = new TextView(this);
            name.setText(e.name);
            name.setTypeface(Typeface.DEFAULT_BOLD);
            name.setTextColor(getColor(R.color.text_primary));
            name.setPadding(0, dp(6), 0, 0);
            card.addView(name);
            if (!e.about.isEmpty()) {
                TextView about = new TextView(this);
                about.setText(e.about);
                about.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
                about.setTextColor(getColor(R.color.text_secondary));
                card.addView(about);
            }
            LinearLayout buttons = new LinearLayout(this);
            Button use = flat("Use");
            use.setOnClickListener(v -> {
                setResult(RESULT_OK, new Intent().putExtra(EXTRA_CODE, e.code));
                finish();
            });
            Button save = flat("Save");
            save.setOnClickListener(v -> {
                PresetStore.save(this, e.name, e.look.settings, false);
                save.setText("Saved");
                save.setEnabled(false);
                Toast.makeText(this, "Added “" + e.name + "” to your looks", Toast.LENGTH_SHORT).show();
            });
            buttons.addView(use);
            buttons.addView(save);
            card.addView(buttons);
            row.addView(card, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        }
        // Keep a short last row's cards the same width as the others.
        if (row != null) {
            for (int k = entries.size() % columns(); k != 0 && k < columns(); k++) {
                row.addView(new android.view.View(this), new LinearLayout.LayoutParams(0, 1, 1f));
            }
        }
        return images;
    }

    /** Opens a prefilled suggestion (a GitHub issue) with the editor's current look. */
    private void suggest() {
        String code = getIntent().getStringExtra(EXTRA_CURRENT);
        if (code == null) {
            return;
        }
        String body = "Name: \nWhat it looks like: \n\nLook code (from Resona):\n" + code;
        Uri uri = Uri.parse(Gallery.SUGGEST_URL).buildUpon()
                .appendQueryParameter("title", "Gallery look: ")
                .appendQueryParameter("body", body)
                .build();
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, uri));
        } catch (android.content.ActivityNotFoundException ex) {
            Toast.makeText(this, "No browser to open the suggestion page.", Toast.LENGTH_LONG).show();
        }
    }

    private Button flat(String text) {
        Button b = new Button(this, null, android.R.attr.borderlessButtonStyle);
        b.setText(text);
        b.setTextColor(getColor(R.color.accent));
        return b;
    }

    private int dp(float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()));
    }
}
