package com.home.launcher;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Locale;

public class HomeActivity extends Activity {
    // Muted palette: neutral charcoal, warm cream text, soft gold accent.
    static final int BG_TOP = 0xFF121413, BG_BOTTOM = 0xFF0E100F;
    static final int PANEL = 0xFF1D1F1E, PANEL_HI = 0xFF2B2E2C;
    // Neutral translucent layers, so panels take on whichever wallpaper is showing.
    static final int GLASS = 0x73000000, GLASS_HI = 0x1FFFFFFF;
    static final int TEXT = 0xFFE8E4DA, MUTED = 0xFF8D978F, DIM = 0xFF5E6862, GOLD = 0xFFC9A86A;

    static final String LAUNCHERX = "com.google.android.apps.tv.launcherx";
    static final String[][] TILES = {
            {"Live TV", "live"},
            {"Quran", "quran"},
            {"Apple TV", "com.apple.atve.androidtv.appletv"},
            {"Netflix", "com.netflix.ninja"},
            {"YouTube", "com.google.android.youtube.tv"},
            {"Hulu", "com.hulu.livingroomplus"},
            {"Peacock", "com.peacocktv.peacockandroid"},
            {"All apps", "all"},
    };

    /** Set when we open Google TV's Live tab on purpose, so the guard lets it through. */
    static volatile long liveOpenedAt;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private Prayer prayer;
    private Daily daily;
    private Events events;
    private TextView eventsLine;
    private TextView clock, ampm, dateLine, nextLine;
    private LinearLayout strip;
    private FrameLayout textArea;
    private long renderedSlot = Long.MIN_VALUE;
    private LinearLayout content;
    private int lastShiftMinute = -1;
    /** Adhkar are shown for this long after each iqamah. */
    private static final int ADHKAR_MINUTES = 20, ADHKAR_SECONDS_EACH = 40;
    private FrameLayout root;
    private View adhanCard;
    private android.media.MediaPlayer adhanPlayer;
    private final ImageView[] wall = new ImageView[2];
    private String[] wallpapers = new String[0];
    private int shownWall = -1, wallLayer = 0;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            render(false);
            drift();
            ui.postDelayed(this, 5000);
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        prayer = new Prayer(this);
        daily = new Daily(this);
        events = new Events(this);
        try {
            wallpapers = getAssets().list("wallpapers");
            java.util.Arrays.sort(wallpapers);
        } catch (Exception ignored) {
        }
        setContentView(build());
    }

    @Override
    protected void onResume() {
        super.onResume();
        render(true);
        maybePlayAdhan();
        ui.removeCallbacks(tick);
        ui.postDelayed(tick, 15000);
        if (System.currentTimeMillis() - prayer.lastSync() > 6 * 3600 * 1000L
                || !"Masjidal".equals(prayer.source())) {
            new Thread(() -> {
                if (prayer.refresh()) ui.post(() -> render(true));
            }).start();
        }
        if (System.currentTimeMillis() - events.lastSync() > 12 * 3600 * 1000L) {
            new Thread(() -> {
                if (events.refresh()) ui.post(() -> render(false));
            }).start();
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        renderedSlot = Long.MIN_VALUE;
    }

    @Override
    protected void onPause() {
        super.onPause();
        ui.removeCallbacks(tick);
        stopAdhan();
    }

    @Override
    public void onBackPressed() {
        // This is home: nothing behind it. Back only stops the adhan.
        stopAdhan();
    }

    /** Called by GuardService at adhan time when this screen is showing. */
    static void openAdhan(android.content.Context c, String name, int index, Prayer.Day d) {
        Intent i = new Intent(c, HomeActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP
                | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        i.putExtra("adhan", name);
        i.putExtra("adhanAt", d.adhan[index]);
        i.putExtra("iqamahAt", d.iqamah[index]);
        i.putExtra("adhanIndex", index);
        c.startActivity(i);
    }

    private void maybePlayAdhan() {
        Intent in = getIntent();
        String name = in.getStringExtra("adhan");
        if (name == null) return;
        int at = in.getIntExtra("adhanAt", -1), iq = in.getIntExtra("iqamahAt", -1);
        boolean fajr = in.getIntExtra("adhanIndex", -1) == 0;
        in.removeExtra("adhan");
        stopAdhan();

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER);
        card.setBackgroundColor(0xFA0B100E);
        card.setClickable(true);
        TextView label = text(13, GOLD, "sans-serif-medium");
        label.setLetterSpacing(0.3f);
        label.setText("ADHAN");
        label.setGravity(Gravity.CENTER);
        card.addView(label, new LinearLayout.LayoutParams(-1, -2));
        TextView title = text(64, TEXT, "sans-serif-thin");
        title.setPadding(0, dp(10), 0, dp(6));
        title.setText(name);
        title.setGravity(Gravity.CENTER);
        card.addView(title, new LinearLayout.LayoutParams(-1, -2));
        TextView times = text(18, MUTED, "sans-serif-light");
        times.setText((at >= 0 ? Prayer.fmt(at) + " " + Prayer.ampm(at) : "")
                + (iq >= 0 ? "   ·   Iqamah " + Prayer.fmt(iq) + " " + Prayer.ampm(iq) : "")
                + "   ·   MCA Santa Clara");
        times.setGravity(Gravity.CENTER);
        card.addView(times, new LinearLayout.LayoutParams(-1, -2));
        TextView hint = text(12, DIM, "sans-serif-light");
        hint.setPadding(0, dp(40), 0, 0);
        hint.setText("Press Back to stop");
        hint.setGravity(Gravity.CENTER);
        card.addView(hint, new LinearLayout.LayoutParams(-1, -2));
        TextView credit = text(9, DIM, "sans-serif-light");
        credit.setPadding(0, dp(6), 0, 0);
        credit.setText("Adhan audio from Masjidal");
        credit.setGravity(Gravity.CENTER);
        card.addView(credit, new LinearLayout.LayoutParams(-1, -2));
        card.setAlpha(0f);
        root.addView(card, new FrameLayout.LayoutParams(-1, -1));
        card.animate().alpha(1f).setDuration(800).start();
        adhanCard = card;

        try {
            android.content.res.AssetFileDescriptor fd = getAssets().openFd(fajr ? "adhan/fajr.mp3" : "adhan/adhan.mp3");
            adhanPlayer = new android.media.MediaPlayer();
            adhanPlayer.setAudioAttributes(new android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH).build());
            adhanPlayer.setDataSource(fd.getFileDescriptor(), fd.getStartOffset(), fd.getLength());
            fd.close();
            adhanPlayer.setOnCompletionListener(mp -> ui.postDelayed(this::stopAdhan, 4000));
            adhanPlayer.prepare();
            adhanPlayer.start();
        } catch (Exception e) {
            android.util.Log.w("HomeLauncher", "adhan playback failed", e);
            ui.postDelayed(this::stopAdhan, 60000);
        }
    }

    private void stopAdhan() {
        if (adhanPlayer != null) {
            try {
                adhanPlayer.stop();
            } catch (Exception ignored) {
            }
            adhanPlayer.release();
            adhanPlayer = null;
        }
        if (adhanCard != null) {
            View c = adhanCard;
            adhanCard = null;
            c.animate().alpha(0f).setDuration(600).withEndAction(() -> root.removeView(c)).start();
        }
    }

    private View build() {
        root = new FrameLayout(this);
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{BG_TOP, BG_BOTTOM});
        root.setBackground(bg);

        // Two stacked layers so wallpapers can crossfade.
        for (int i = 0; i < 2; i++) {
            wall[i] = new ImageView(this);
            wall[i].setScaleType(ImageView.ScaleType.CENTER_CROP);
            wall[i].setAlpha(0f);
            root.addView(wall[i], new FrameLayout.LayoutParams(-1, -1));
        }

        LinearLayout col = new LinearLayout(this);
        content = col;
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(56), dp(28), dp(56), dp(44));   // clear of TV overscan at the bottom
        root.addView(col, new FrameLayout.LayoutParams(-1, -1));

        // Header: clock + dates on the left, masjid + next prayer on the right.
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.BOTTOM);
        col.addView(header, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        header.addView(left, new LinearLayout.LayoutParams(0, -2, 1));
        LinearLayout clockRow = new LinearLayout(this);
        clockRow.setGravity(Gravity.BOTTOM);
        clock = text(44, TEXT, "sans-serif-thin");
        ampm = text(18, MUTED, "sans-serif-light");
        ampm.setPadding(dp(8), 0, 0, dp(10));
        clockRow.addView(clock);
        clockRow.addView(ampm);
        left.addView(clockRow);
        dateLine = text(15, MUTED, "sans-serif-light");
        left.addView(dateLine);
        eventsLine = text(15, MUTED, "sans-serif-light");
        eventsLine.setPadding(0, dp(8), 0, 0);
        eventsLine.setSingleLine(true);
        eventsLine.setEllipsize(TextUtils.TruncateAt.END);
        left.addView(eventsLine);

        LinearLayout right = new LinearLayout(this);
        right.setOrientation(LinearLayout.VERTICAL);
        right.setGravity(Gravity.END);
        header.addView(right, new LinearLayout.LayoutParams(-2, -2));
        TextView masjid = text(13, DIM, "sans-serif-medium");
        masjid.setText("MCA · SANTA CLARA");
        masjid.setLetterSpacing(0.18f);
        masjid.setGravity(Gravity.END);
        right.addView(masjid);
        nextLine = text(18, GOLD, "sans-serif-light");
        nextLine.setGravity(Gravity.END);
        nextLine.setPadding(0, dp(6), 0, 0);
        right.addView(nextLine);

        // Prayer strip.
        strip = new LinearLayout(this);
        strip.setOrientation(LinearLayout.HORIZONTAL);
        strip.setBackground(round(GLASS, 18));
        strip.setPadding(dp(6), dp(6), dp(6), dp(6));
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-1, -2);
        sp.topMargin = dp(18);
        col.addView(strip, sp);

        // Daily verse / hadith.
        textArea = new FrameLayout(this);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(-1, 0, 1);
        tp.topMargin = dp(18);      // breathing room below the prayer bar
        tp.bottomMargin = dp(6);
        col.addView(textArea, tp);

        // App tiles.
        LinearLayout tiles = new LinearLayout(this);
        tiles.setOrientation(LinearLayout.HORIZONTAL);
        tiles.setGravity(Gravity.CENTER_HORIZONTAL);
        tiles.setClipChildren(false);
        tiles.setClipToPadding(false);
        col.addView(tiles, new LinearLayout.LayoutParams(-1, -2));
        View first = null;
        for (String[] t : TILES) {
            View v = tile(t[0], t[1]);
            if (v == null) continue;
            tiles.addView(v);
            if (first == null) first = v;
        }
        col.setClipChildren(false);
        col.setClipToPadding(false);   // a focused tile grows past the row into the padding
        root.setClipChildren(false);

        if (first != null) {
            View f = first.findViewWithTag("focus");
            if (f != null) f.post(f::requestFocus);
        }
        return root;
    }

    /**
     * Burn-in care: every 3 minutes the whole layout eases to a slightly different position
     * (a few pixels), so nothing static sits on exactly the same pixels all day.
     */
    private void drift() {
        int m = (int) (System.currentTimeMillis() / 60000);
        if (content == null || m == lastShiftMinute || m % 3 != 0) return;
        lastShiftMinute = m;
        java.util.Random r = new java.util.Random(m);
        content.animate().translationX(dp(r.nextInt(13) - 6)).translationY(dp(r.nextInt(9) - 4))
                .setDuration(4000).start();
    }

    /** Long passages shrink to fit rather than being cut off at the tiles. */
    private void fit(View v) {
        int avail = textArea.getHeight(), w = textArea.getWidth();
        if (avail <= 0 || w <= 0) return;
        // Natural height, unconstrained by the space available (a wrap_content child would be capped).
        v.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        int natural = v.getMeasuredHeight();
        ViewGroup.LayoutParams lp = v.getLayoutParams();
        if (lp.height != natural) {
            lp.height = natural;
            // Called from a layout listener; a resize requested mid-layout is dropped, so defer it.
            v.post(() -> v.setLayoutParams(lp));
        }
        float k = natural > avail ? (float) avail / natural : 1f;
        v.setScaleX(k);
        v.setScaleY(k);
    }

    private void showWallpaper(int index, boolean instant) {
        try {
            android.graphics.Bitmap bm = android.graphics.BitmapFactory.decodeStream(
                    getAssets().open("wallpapers/" + wallpapers[index]));
            ImageView in = wall[1 - wallLayer], out = wall[wallLayer];
            in.setImageBitmap(bm);
            if (instant) {
                in.setAlpha(1f);
                out.setAlpha(0f);
            } else {
                in.animate().alpha(1f).setDuration(2500).start();
                out.animate().alpha(0f).setDuration(2500).start();
            }
            wallLayer = 1 - wallLayer;
            shownWall = index;
        } catch (Exception ignored) {
        }
    }

    private void render(boolean full) {
        Calendar now = Calendar.getInstance();
        int mins = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE);

        clock.setText(new SimpleDateFormat("h:mm", Locale.US).format(now.getTime()));
        ampm.setText(new SimpleDateFormat("a", Locale.US).format(now.getTime()));
        dateLine.setText(new SimpleDateFormat("EEEE, MMMM d", Locale.US).format(now.getTime())
                + "   ·   " + prayer.hijri(now));

        Prayer.Day d = prayer.day(now);
        boolean friday = now.get(Calendar.DAY_OF_WEEK) == Calendar.FRIDAY;

        // Next iqamah (Sunrise has none); after Isha, tomorrow's Fajr.
        int nextIdx = -1, nextAt = -1;
        String nextLabel = null;
        if (d != null) {
            for (int i = 0; i < 6; i++) {
                if (d.iqamah[i] >= 0 && d.iqamah[i] > mins) {
                    nextIdx = i;
                    nextAt = d.iqamah[i];
                    break;
                }
            }
            if (nextIdx >= 0) {
                nextLabel = (friday && nextIdx == 2 ? "Jumu'ah" : Prayer.NAMES[nextIdx]) + " iqamah";
            } else {
                Calendar tm = (Calendar) now.clone();
                tm.add(Calendar.DATE, 1);
                Prayer.Day t = prayer.day(tm);
                if (t != null) {
                    nextLabel = "Fajr iqamah";
                    nextAt = t.iqamah[0] + 24 * 60;
                }
            }
        }
        if (nextLabel != null) {
            int left = nextAt - mins;
            String in = left >= 60 ? (left / 60) + "h " + (left % 60) + "m" : left + " min";
            nextLine.setText(nextLabel + " " + Prayer.fmt(nextAt) + " " + Prayer.ampm(nextAt) + "  ·  in " + in);
        } else {
            nextLine.setText("");
        }

        // Rebuilt every tick so the "next" highlight moves as prayers pass.
        {
            strip.removeAllViews();
            for (int i = 0; i < 6; i++) {
                boolean isNext = i == nextIdx;
                boolean past = d != null && nextIdx >= 0 && i < nextIdx
                        || d != null && nextIdx < 0;
                LinearLayout c = new LinearLayout(this);
                c.setOrientation(LinearLayout.VERTICAL);
                c.setGravity(Gravity.CENTER_HORIZONTAL);
                c.setPadding(dp(14), dp(10), dp(8), dp(10));
                if (isNext) c.setBackground(round(GLASS_HI, 14));

                String name = (friday && i == 2) ? "JUMU'AH" : Prayer.NAMES[i].toUpperCase(Locale.US);
                TextView n = text(11, isNext ? GOLD : MUTED, "sans-serif-medium");
                n.setLetterSpacing(0.16f);
                n.setText(name);
                c.addView(n);

                TextView t = text(23, past ? DIM : TEXT, "sans-serif-light");
                t.setPadding(0, dp(2), 0, 0);
                t.setText(d == null ? "–" : Prayer.fmt(d.adhan[i]));
                c.addView(t);

                TextView q = text(12, past ? DIM : (isNext ? GOLD : MUTED), "sans-serif-light");
                if (friday && i == 2) {
                    String[] j = prayer.jumuah();
                    q.setText(TextUtils.join("  ·  ", j));
                } else if (d != null && d.iqamah[i] >= 0) {
                    q.setText("Iqamah " + Prayer.fmt(d.iqamah[i]));
                } else {
                    q.setText(" ");
                }
                c.addView(q);
                strip.addView(c, new LinearLayout.LayoutParams(0, -2, 1));
            }
        }

        int previewSlots = getIntent().getIntExtra("previewSlots", 0); // adb test hook
        long slot = Daily.slot(now) + previewSlots;
        final long timeSlot = slot;

        // For a while after each iqamah, show the after-prayer adhkar instead, one at a time.
        int test = getIntent().getIntExtra("adhkarTest", -1);       // adb test hook: prayer index
        int secs = mins * 60 + now.get(Calendar.SECOND);
        View adhkarNext = null;
        if (d != null) {
            for (int i : new int[]{0, 2, 3, 4, 5}) {
                int since = test == i ? 0 : secs - d.iqamah[i] * 60;
                if (d.iqamah[i] < 0 || since < 0 || since >= ADHKAR_MINUTES * 60) continue;
                String name = friday && i == 2 ? "Jumu'ah" : Prayer.NAMES[i];
                java.util.List<org.json.JSONObject> list = daily.adhkarFor(Prayer.NAMES[i]);
                if (list.isEmpty()) break;
                int k = (since / ADHKAR_SECONDS_EACH) % list.size();
                if (test == i) k = (int) ((System.currentTimeMillis() / 1000 / ADHKAR_SECONDS_EACH) % list.size());
                slot = -1000L - i * 100L - k;                      // distinct key per adhkar card
                if (full || slot != renderedSlot) adhkarNext = daily.adhkarView(list.get(k), name, k + 1, list.size());
                break;
            }
        }
        if (full || slot != renderedSlot) {
            View next = adhkarNext != null ? adhkarNext : daily.view(slot, previewSlots != 0);
            View old = textArea.getChildCount() > 0 ? textArea.getChildAt(0) : null;
            next.setAlpha(old == null || full ? 1f : 0f);
            textArea.addView(next, new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER));
            next.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> fit(v));
            if (old != null) {
                if (full) {
                    textArea.removeView(old);
                } else {
                    old.animate().alpha(0f).setDuration(900)
                            .withEndAction(() -> textArea.removeView(old)).start();
                    next.animate().alpha(1f).setStartDelay(700).setDuration(1200).start();
                }
            }
            renderedSlot = slot;
        }

        if (wallpapers.length > 0) {
            int w = (int) Math.floorMod(Math.floorDiv(timeSlot, Daily.SLOTS_PER_WALLPAPER), (long) wallpapers.length);
            if (w != shownWall) showWallpaper(w, shownWall < 0);
        }

        StringBuilder ev = new StringBuilder();
        for (org.json.JSONObject e : events.upcoming(1)) {
            java.util.Date when = new java.util.Date(e.optLong("t"));
            ev.append(new SimpleDateFormat("EEE, MMM d", Locale.US).format(when));
            if (!e.optBoolean("allDay")) ev.append(" ").append(new SimpleDateFormat("h:mm a", Locale.US).format(when));
            ev.append("  ").append(e.optString("title"));
        }
        eventsLine.setText(ev.length() > 0 ? "Next at MCA:  " + ev : "");
    }

    private View tile(String label, String what) {
        PackageManager pm = getPackageManager();
        Drawable art = null;
        Intent launch;
        if (what.equals("live")) {
            launch = new Intent(Intent.ACTION_VIEW, Uri.parse("https://tv.google.com/destination/live"));
            launch.setComponent(new ComponentName(LAUNCHERX, LAUNCHERX + ".gateway.LauncherDeepLink"));
            art = getDrawable(getResources().getIdentifier("tile_live", "drawable", getPackageName()));
        } else if (what.equals("all")) {
            launch = new Intent(this, AppsActivity.class);
        } else if (what.equals("quran")) {
            launch = new Intent(this, QuranActivity.class);
        } else {
            launch = pm.getLeanbackLaunchIntentForPackage(what);
            if (launch == null) launch = pm.getLaunchIntentForPackage(what);
            if (launch == null) return null;
            art = bannerFor(pm, launch);
        }
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        final Intent go = launch;

        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.setGravity(Gravity.CENTER_HORIZONTAL);
        wrap.setClipChildren(false);
        LinearLayout.LayoutParams wl = new LinearLayout.LayoutParams(dp(78), -2);
        wl.setMargins(dp(6), dp(4), dp(6), 0);
        wrap.setLayoutParams(wl);

        FrameLayout card = new FrameLayout(this);
        card.setTag("focus");
        card.setFocusable(true);
        card.setClickable(true);
        card.setBackground(round(PANEL, 12));
        card.setClipToOutline(true);
        card.setPadding(dp(2), dp(2), dp(2), dp(2));
        wrap.addView(card, new LinearLayout.LayoutParams(dp(78), dp(44)));

        if (art != null) {
            ImageView iv = new ImageView(this);
            iv.setImageDrawable(art);
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            iv.setClipToOutline(true);
            iv.setBackground(round(PANEL, 10));
            card.addView(iv, new FrameLayout.LayoutParams(-1, -1));
        } else if (what.equals("quran")) {
            Typeface amiri = null;
            try {
                amiri = Typeface.createFromAsset(getAssets(), "fonts/AmiriQuran.ttf");
            } catch (Exception ignored) {
            }
            card.setPadding(0, 0, 0, 0);
            card.setBackground(round(0xFF0F3A2C, 12));
            card.addView(new QuranTile(this, amiri), new FrameLayout.LayoutParams(-1, -1));
        } else {
            TextView tv = text(11, TEXT, "sans-serif-light");
            tv.setGravity(Gravity.CENTER);
            tv.setText("All apps");
            card.addView(tv, new FrameLayout.LayoutParams(-1, -1));
        }

        // No caption under the tile: the logo says what it is. The label is for screen readers.
        card.setContentDescription(label);

        card.setOnFocusChangeListener((v, has) -> {
            v.animate().scaleX(has ? 1.08f : 1f).scaleY(has ? 1.08f : 1f).setDuration(140).start();
            v.setElevation(has ? dp(8) : 0);
            v.setForeground(has ? stroke(GOLD, 12) : null);
        });
        card.setOnClickListener(v -> {
            if (what.equals("live")) liveOpenedAt = System.currentTimeMillis();
            try {
                startActivity(go);
            } catch (Exception ignored) {
            }
        });
        return wrap;
    }

    static Drawable bannerFor(PackageManager pm, Intent launch) {
        try {
            ResolveInfo ri = pm.resolveActivity(launch, 0);
            if (ri != null) {
                ActivityInfo ai = ri.activityInfo;
                Drawable d = ai.loadBanner(pm);
                if (d != null) return d;
                d = pm.getApplicationBanner(ai.packageName);
                if (d != null) return d;
                return ai.loadIcon(pm);
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    TextView text(float sp, int color, String family) {
        TextView t = new TextView(this);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setTypeface(Typeface.create(family, Typeface.NORMAL));
        t.setIncludeFontPadding(false);
        return t;
    }

    public GradientDrawable round(int color, int radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radiusDp));
        return g;
    }

    GradientDrawable stroke(int color, int radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(Color.TRANSPARENT);
        g.setStroke(dp(2), color);
        g.setCornerRadius(dp(radiusDp));
        return g;
    }

    int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            startActivity(new Intent(android.provider.Settings.ACTION_SETTINGS));
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    static void open(android.content.Context c) {
        Intent i = new Intent(c, HomeActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP
                | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        c.startActivity(i);
    }
}
