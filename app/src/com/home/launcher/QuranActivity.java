package com.home.launcher;

import android.app.Activity;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.Bundle;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Quran recitation player (Al-Baqarah, Al-Kahf, Juz 29 and Juz 30). Audio is Masjidal's public Quran library (one MP3 per surah);
 * pick a reciter, pick a surah, and it plays on to the next surah.
 */
public class QuranActivity extends Activity {
    static final String BASE = "https://masjidal.s3.us-east-2.amazonaws.com/audio/quran/";
    static final String[][] RECITERS = {
            {"Mishari Al-Afasy", "mishari-rashid-al-afasy"},
            {"Mishari + English", "eng_mishari-rashid-al-afasy_with_ibrahim-walk_saheeh-translation"},
            {"Al-Husary", "mahmoud_khaleel_al-husary"},
            {"Al-Minshawi", "muhammad_siddiq_al-minshawi"},
    };

    private final List<int[]> surahs = new ArrayList<>();      // {number, ayahs}
    private final List<String[]> names = new ArrayList<>();    // {english, arabic}
    private final List<String> groupTitles = new ArrayList<>();
    private final List<Integer> groupStarts = new ArrayList<>();
    private final List<View> surahViews = new ArrayList<>();
    private final List<View> reciterViews = new ArrayList<>();
    private MediaPlayer player;
    private AudioManager audio;
    private AudioFocusRequest focus;
    private int reciter, playing = -1;
    private TextView nowTitle, nowArabic, nowState, pauseBtn;
    private boolean prepared;
    private Typeface quran;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        audio = (AudioManager) getSystemService(AUDIO_SERVICE);
        reciter = getSharedPreferences("quran", 0).getInt("reciter", 0);
        try {
            quran = Typeface.createFromAsset(getAssets(), "fonts/AmiriQuran.ttf");
            JSONArray groups = new JSONArray(Prayer.readAll(getAssets().open("quran_surahs.json")));
            for (int g = 0; g < groups.length(); g++) {
                JSONObject grp = groups.getJSONObject(g);
                JSONArray list = grp.getJSONArray("surahs");
                groupTitles.add(grp.getString("title"));
                groupStarts.add(surahs.size());
                for (int i = 0; i < list.length(); i++) {
                    JSONObject v = list.getJSONObject(i);
                    surahs.add(new int[]{v.getInt("n"), v.getInt("ayahs")});
                    names.add(new String[]{v.getString("en"), v.getString("ar")});
                }
            }
        } catch (Exception ignored) {
        }
        setContentView(build());
    }

    private View build() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{HomeActivity.BG_TOP, HomeActivity.BG_BOTTOM}));
        root.setPadding(dp(56), dp(28), dp(56), dp(24));

        // Now playing.
        nowState = text(11, HomeActivity.GOLD, "sans-serif-medium");
        nowState.setLetterSpacing(0.2f);
        nowState.setText("CHOOSE A SURAH");
        root.addView(nowState);
        LinearLayout now = new LinearLayout(this);
        now.setGravity(Gravity.CENTER_VERTICAL);
        nowTitle = text(30, HomeActivity.TEXT, "sans-serif-light");
        nowArabic = text(34, 0xFFDDD3BC, "serif");
        if (quran != null) nowArabic.setTypeface(quran);
        nowArabic.setIncludeFontPadding(true);
        now.addView(nowTitle, new LinearLayout.LayoutParams(0, -2, 1));
        now.addView(nowArabic);
        pauseBtn = chip("❚❚  Pause");
        pauseBtn.setVisibility(View.GONE);
        pauseBtn.setBackground(round(HomeActivity.PANEL_HI, 18));
        pauseBtn.setOnClickListener(v -> togglePause());
        LinearLayout.LayoutParams pl = new LinearLayout.LayoutParams(-2, -2);
        pl.leftMargin = dp(24);
        now.addView(pauseBtn, pl);
        root.addView(now);

        // Reciters.
        TextView rl = text(11, HomeActivity.MUTED, "sans-serif-medium");
        rl.setLetterSpacing(0.18f);
        rl.setText("RECITER");
        rl.setPadding(0, dp(14), 0, dp(8));
        root.addView(rl);
        LinearLayout rrow = new LinearLayout(this);
        for (int i = 0; i < RECITERS.length; i++) {
            final int idx = i;
            TextView chip = chip(RECITERS[i][0]);
            chip.setOnClickListener(v -> {
                reciter = idx;
                getSharedPreferences("quran", 0).edit().putInt("reciter", idx).apply();
                refreshChips();
                if (playing >= 0) play(playing);
            });
            reciterViews.add(chip);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.rightMargin = dp(10);
            rrow.addView(chip, lp);
        }
        HorizontalScrollView rs = new HorizontalScrollView(this);
        rs.setHorizontalScrollBarEnabled(false);
        rs.addView(rrow);
        root.addView(rs);

        // Surah grid, one section per group (Al-Baqarah & Al-Kahf, Juz 29, Juz 30).
        LinearLayout grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);
        grid.setPadding(0, dp(6), 0, 0);
        LinearLayout row = null;
        int inRow = 0;
        for (int i = 0; i < surahs.size(); i++) {
            int g = groupStarts.indexOf(i);
            if (g >= 0) {
                while (row != null && row.getChildCount() < 6)
                    row.addView(new View(this), new LinearLayout.LayoutParams(0, dp(52), 1));
                TextView sl = text(11, HomeActivity.MUTED, "sans-serif-medium");
                sl.setLetterSpacing(0.18f);
                sl.setText(groupTitles.get(g).toUpperCase(Locale.US));
                sl.setPadding(0, dp(14), 0, dp(8));
                grid.addView(sl);
                inRow = 0;
            }
            if (inRow++ % 6 == 0) {
                row = new LinearLayout(this);
                grid.addView(row);
            }
            final int idx = i;
            LinearLayout cell = new LinearLayout(this);
            cell.setOrientation(LinearLayout.VERTICAL);
            cell.setFocusable(true);
            cell.setClickable(true);
            cell.setPadding(dp(12), dp(8), dp(12), dp(8));
            cell.setBackground(round(HomeActivity.PANEL, 10));
            TextView num = text(10, HomeActivity.DIM, "sans-serif-medium");
            num.setText(String.valueOf(surahs.get(i)[0]));
            TextView en = text(14, HomeActivity.TEXT, "sans-serif-light");
            en.setSingleLine(true);
            en.setText(names.get(i)[0]);
            cell.addView(num);
            cell.addView(en);
            cell.setOnFocusChangeListener((v, has) -> {
                v.setBackground(has ? round(HomeActivity.PANEL_HI, 10) : round(
                        idx == playing ? 0xFF3A3528 : HomeActivity.PANEL, 10));
                v.setForeground(has ? stroke() : null);
            });
            cell.setOnClickListener(v -> {
                if (idx == playing && player != null) togglePause();   // same surah: pause/resume
                else play(idx);
            });
            surahViews.add(cell);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(52), 1);
            lp.setMargins(0, 0, dp(10), dp(10));
            row.addView(cell, lp);
        }
        // Pad the last row so cells keep their width.
        while (row != null && row.getChildCount() < 6) row.addView(new View(this), new LinearLayout.LayoutParams(0, dp(52), 1));
        ScrollView sv = new ScrollView(this);
        sv.addView(grid);
        root.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1));

        refreshChips();
        if (!surahViews.isEmpty()) surahViews.get(0).post(() -> surahViews.get(0).requestFocus());
        return root;
    }

    private void refreshChips() {
        for (int i = 0; i < reciterViews.size(); i++) {
            TextView c = (TextView) reciterViews.get(i);
            boolean sel = i == reciter;
            c.setTextColor(sel ? 0xFF10140F : HomeActivity.TEXT);
            c.setBackground(round(sel ? HomeActivity.GOLD : HomeActivity.PANEL, 18));
        }
    }

    private void play(int idx) {
        if (idx < 0 || idx >= surahs.size()) return;
        stop();
        playing = idx;
        String url = BASE + RECITERS[reciter][1] + "/" + String.format(Locale.US, "%03d", surahs.get(idx)[0]) + ".mp3";
        nowTitle.setText("Surah " + names.get(idx)[0]);
        nowArabic.setText(names.get(idx)[1]);
        nowState.setText("LOADING  ·  " + RECITERS[reciter][0].toUpperCase(Locale.US));
        for (int i = 0; i < surahViews.size(); i++) {
            View v = surahViews.get(i);
            if (!v.hasFocus()) v.setBackground(round(i == idx ? 0xFF3A3528 : HomeActivity.PANEL, 10));
        }
        AudioAttributes attrs = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build();
        focus = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN).setAudioAttributes(attrs)
                .setOnAudioFocusChangeListener(ch -> {
                    if (ch == AudioManager.AUDIOFOCUS_LOSS) stop();
                }).build();
        audio.requestAudioFocus(focus);
        player = new MediaPlayer();
        player.setAudioAttributes(attrs);
        prepared = false;
        pauseBtn.setVisibility(View.GONE);
        try {
            player.setDataSource(url);
            player.setOnPreparedListener(mp -> {
                mp.start();
                prepared = true;
                showPlaying();
            });
            player.setOnCompletionListener(mp -> play(playing + 1));
            player.setOnErrorListener((mp, what, extra) -> {
                nowState.setText("COULDN'T PLAY THIS SURAH (" + what + ")");
                return true;
            });
            player.prepareAsync();
        } catch (Exception e) {
            nowState.setText("COULDN'T PLAY THIS SURAH");
        }
    }

    private void showPlaying() {
        boolean on = player != null && player.isPlaying();
        nowState.setText((on ? "PLAYING" : "PAUSED") + "  ·  " + RECITERS[reciter][0].toUpperCase(Locale.US)
                + (on ? "  ·  PLAYS ON TO THE NEXT SURAH" : ""));
        pauseBtn.setText(on ? "❚❚  Pause" : "▶  Resume");
        pauseBtn.setVisibility(View.VISIBLE);
    }

    private void togglePause() {
        if (player == null || !prepared) return;
        if (player.isPlaying()) {
            player.pause();
        } else {
            audio.requestAudioFocus(focus);
            player.start();
        }
        showPlaying();
    }

    private void stop() {
        prepared = false;
        if (pauseBtn != null) pauseBtn.setVisibility(View.GONE);
        if (player != null) {
            try {
                player.stop();
            } catch (Exception ignored) {
            }
            player.release();
            player = null;
        }
        if (focus != null) audio.abandonAudioFocusRequest(focus);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (playing >= 0 && keyCode == KeyEvent.KEYCODE_MEDIA_NEXT) {
            play(playing + 1);
            return true;
        }
        if (playing >= 0 && keyCode == KeyEvent.KEYCODE_MEDIA_PREVIOUS) {
            play(playing - 1);
            return true;
        }
        if (player != null && (keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
                || keyCode == KeyEvent.KEYCODE_MEDIA_PAUSE || keyCode == KeyEvent.KEYCODE_MEDIA_PLAY)) {
            boolean wantPlay = keyCode == KeyEvent.KEYCODE_MEDIA_PLAY
                    || (keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE && !player.isPlaying());
            if (wantPlay != player.isPlaying()) togglePause();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    protected void onStop() {
        super.onStop();
        stop();
        playing = -1;
    }

    private TextView chip(String s) {
        TextView t = text(13, HomeActivity.TEXT, "sans-serif-medium");
        t.setText(s);
        t.setFocusable(true);
        t.setClickable(true);
        t.setPadding(dp(16), dp(8), dp(16), dp(8));
        t.setOnFocusChangeListener((v, has) -> v.setForeground(has ? stroke() : null));
        return t;
    }

    private TextView text(float sp, int color, String family) {
        TextView t = new TextView(this);
        t.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setTypeface(Typeface.create(family, Typeface.NORMAL));
        t.setIncludeFontPadding(false);
        return t;
    }

    private GradientDrawable round(int color, int r) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(r));
        return g;
    }

    private GradientDrawable stroke() {
        GradientDrawable g = new GradientDrawable();
        g.setColor(0);
        g.setStroke(dp(2), HomeActivity.GOLD);
        g.setCornerRadius(dp(10));
        return g;
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
