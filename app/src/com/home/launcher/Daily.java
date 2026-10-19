package com.home.launcher;

import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Calendar;
import java.util.TimeZone;

/**
 * The rotating text under the prayer times, changing every SLOT_MINUTES. Half the slots are
 * Surah Al-Haqqah (current hifz): ayahs 1-25 in order, with every third one previewing 26-52,
 * each labelled with its theme (titles in haqqah_meaning.json).
 * The other slots take turns: a curated verse, a hadith from Imam an-Nawawi's Forty Hadith,
 * a dua from the Quran, and a Juz 'Amma ayah for review. Quran text and Sahih International
 * translation come from Quran.com; everything is bundled.
 */
public class Daily {
    public static final int SLOT_MINUTES = 5;
    /** Wallpaper changes once a day. */
    public static final int SLOTS_PER_WALLPAPER = 24 * 60 / SLOT_MINUTES;

    private static final int HAQQAH = 0, VERSE = 1, HADITH = 2, DUA = 3, HIFZ = 4;
    /** Slot pattern: Al-Haqqah every other slot. */
    private static final int[] CYCLE = {HAQQAH, VERSE, HAQQAH, HADITH, HAQQAH, DUA, HAQQAH, HIFZ};
    /** Ayahs she is memorizing now; the rest of the surah is previewed less often. */
    private static final int HAQQAH_CURRENT = 25;

    private final HomeActivity a;
    private final SharedPreferences prefs;
    private JSONArray verses = new JSONArray(), hadith = new JSONArray(),
            duas = new JSONArray(), juzAmma = new JSONArray(), haqqah = new JSONArray(),
            meanings = new JSONArray(), adhkar = new JSONArray();
    private Typeface quran;

    public Daily(HomeActivity a) {
        this.a = a;
        prefs = a.getSharedPreferences("daily", 0);
        try {
            verses = new JSONArray(Prayer.readAll(a.getAssets().open("verses.json")));
            hadith = new JSONArray(Prayer.readAll(a.getAssets().open("hadith.json")));
            duas = new JSONArray(Prayer.readAll(a.getAssets().open("duas.json")));
            juzAmma = new JSONArray(Prayer.readAll(a.getAssets().open("juz_amma.json")));
            haqqah = new JSONArray(Prayer.readAll(a.getAssets().open("haqqah.json")));
            meanings = new JSONArray(Prayer.readAll(a.getAssets().open("haqqah_meaning.json")));
            adhkar = new JSONArray(Prayer.readAll(a.getAssets().open("adhkar.json")));
            quran = Typeface.createFromAsset(a.getAssets(), "fonts/AmiriQuran.ttf");
        } catch (Exception ignored) {
        }
    }

    public static long slot(Calendar now) {
        long local = now.getTimeInMillis() + TimeZone.getDefault().getOffset(now.getTimeInMillis());
        return local / (SLOT_MINUTES * 60000L);
    }

    /**
     * Hifz goes ayah by ayah and must not skip ayahs while the TV is off, so its position is
     * stored and advanced once per hifz slot actually shown.
     */
    private int advance(String key, long slot) {
        int pos = prefs.getInt(key + "Pos", -1);
        long last = prefs.getLong(key + "Slot", Long.MIN_VALUE);
        if (pos < 0 || slot > last) {
            pos++;
            prefs.edit().putInt(key + "Pos", pos).putLong(key + "Slot", slot).apply();
        }
        return pos;
    }

    public View view(long slot, boolean preview) {
        int kind = CYCLE[(int) Math.floorMod(slot, (long) CYCLE.length)];
        long n = Math.floorDiv(slot, CYCLE.length);

        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        box.setPadding(a.dp(70), a.dp(4), a.dp(70), a.dp(8));

        // Calm layout: the Arabic, its translation, and one quiet line saying where it is from.
        try {
            if (kind == HAQQAH && haqqah.length() > 0) {
                return haqqahView(preview ? (int) Math.floorMod(slot, 1000L) : advance("haqqah", slot));
            } else if (kind == HIFZ && juzAmma.length() > 0) {
                int i = Math.floorMod(preview ? (int) n : advance("hifz", slot), juzAmma.length());
                JSONObject v = juzAmma.getJSONObject(i);
                box.addView(arabic(v.getString("ar"), 36));
                box.addView(english(quoted(v.getString("en")), 19));
                box.addView(ref(v.getString("surah") + " " + v.getInt("a") + "  ·  Juz 'Amma review"));
            } else if (kind == VERSE && verses.length() > 0) {
                JSONObject v = verses.getJSONObject((int) Math.floorMod(n, (long) verses.length()));
                box.addView(arabic(v.getString("ar"), 32));
                box.addView(english(quoted(v.getString("en")), 19));
                box.addView(ref(v.getString("surah") + " " + v.getString("ref").split(":")[1]));
            } else if (kind == DUA && duas.length() > 0) {
                JSONObject v = duas.getJSONObject((int) Math.floorMod(n, (long) duas.length()));
                box.addView(arabic(v.getString("ar"), 32));
                box.addView(english(quoted(v.getString("en")), 19));
                box.addView(ref("Dua  ·  " + v.getString("surah") + " " + v.getString("ref").split(":")[1]));
            } else if (hadith.length() > 0) {
                JSONObject h = hadith.getJSONObject((int) Math.floorMod(n, (long) hadith.length()));
                TextView en = english(h.getString("en"), 18);
                en.setLineSpacing(0, 1.3f);
                box.addView(en);
                box.addView(ref("Forty Hadith of Imam an-Nawawi, " + h.getInt("n")));
            }
        } catch (Exception ignored) {
        }
        return box;
    }

    /** Turn k of the Al-Haqqah track: two from ayahs 1-25, then one preview from 26-52. */
    private View haqqahView(int k) throws Exception {
        int total = haqqah.length();
        int ahead = total - HAQQAH_CURRENT;
        boolean previewAhead = ahead > 0 && k % 3 == 2;
        int ayah = previewAhead
                ? HAQQAH_CURRENT + 1 + Math.floorMod(k / 3, ahead)
                : 1 + Math.floorMod(k - k / 3, HAQQAH_CURRENT);
        JSONObject v = haqqah.getJSONObject(ayah - 1);
        String theme = null;
        for (int i = 0; i < meanings.length(); i++) {
            JSONObject x = meanings.getJSONObject(i);
            if (ayah >= x.getInt("from") && ayah <= x.getInt("to")) theme = x.getString("title");
        }

        LinearLayout col = new LinearLayout(a);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        col.setPadding(a.dp(70), a.dp(4), a.dp(70), a.dp(8));
        col.addView(arabic(v.getString("ar"), 38));
        col.addView(english(quoted(v.getString("en")), 19));
        col.addView(ref("Al-Haqqah " + ayah + (theme != null ? "  ·  " + theme : "")
                + (previewAhead ? "  ·  coming up" : "")));
        return col;
    }

    /** After-prayer adhkar (Hisn al-Muslim) that apply to this prayer, in order. */
    public java.util.List<JSONObject> adhkarFor(String prayer) {
        java.util.List<JSONObject> out = new java.util.ArrayList<>();
        for (int i = 0; i < adhkar.length(); i++) {
            JSONObject x = adhkar.optJSONObject(i);
            JSONArray only = x.optJSONArray("only");
            boolean ok = only == null || only.length() == 0;
            for (int j = 0; only != null && j < only.length(); j++) ok |= only.optString(j).equals(prayer);
            if (ok) out.add(x);
        }
        return out;
    }

    public View adhkarView(JSONObject x, String prayer, int n, int total) {
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        box.setPadding(a.dp(70), a.dp(4), a.dp(70), a.dp(8));
        TextView head = a.text(13, HomeActivity.GOLD, "sans-serif-light");
        head.setGravity(Gravity.CENTER);
        head.setText("After " + prayer + " prayer  ·  " + n + " of " + total);
        box.addView(head);
        box.addView(arabic(x.optString("ar"), 30));
        if (x.optInt("repeat", 1) > 1) {
            TextView count = a.text(30, HomeActivity.GOLD, "sans-serif-light");
            count.setGravity(Gravity.CENTER);
            count.setPadding(0, a.dp(6), 0, 0);
            count.setText("× " + x.optInt("repeat"));
            box.addView(count);
        }
        // The Arabic is what's recited; very long translations are left off to keep the screen calm.
        String en = x.optString("en").replaceAll("^\\(|\\)$", "").replace(")(", ") (");
        if (en.length() <= 300) box.addView(english(en, 16));
        box.addView(ref("Hisn al-Muslim, " + x.optInt("id")));
        return box;
    }

    private static String quoted(String t) {
        // A single ayah can open or close speech that spans several ayahs; drop unbalanced quotes.
        if (t.chars().filter(c -> c == '"').count() % 2 == 1) t = t.replace("\"", "");
        return t.contains("\"") ? t : "“" + t + "”";
    }

    private TextView arabic(String s, float sp) {
        if (s.length() > 110) sp -= 4;
        if (s.length() > 170) sp -= 3;
        TextView ar = a.text(sp, 0xFFDDD3BC, "serif");
        if (quran != null) ar.setTypeface(quran);
        ar.setIncludeFontPadding(true);
        ar.setLineSpacing(0, 0.9f);
        ar.setGravity(Gravity.CENTER);
        ar.setTextDirection(View.TEXT_DIRECTION_RTL);
        ar.setShadowLayer(12, 0, 2, 0xCC000000);
        ar.setText(s);
        return ar;
    }

    private TextView english(String s, float sp) {
        if (s.length() > 180) sp -= 2f;
        if (s.length() > 300) sp -= 2f;
        TextView en = a.text(sp, HomeActivity.TEXT, "sans-serif-light");
        en.setGravity(Gravity.CENTER);
        en.setLineSpacing(0, 1.2f);
        en.setPadding(0, a.dp(12), 0, 0);
        en.setShadowLayer(10, 0, 2, 0xCC000000);
        en.setText(s);
        return en;
    }

    private TextView ref(String s) {
        TextView r = a.text(13, HomeActivity.GOLD, "sans-serif-light");
        r.setGravity(Gravity.CENTER);
        r.setAlpha(0.85f);
        r.setPadding(0, a.dp(16), 0, 0);
        r.setText(s);
        return r;
    }
}
