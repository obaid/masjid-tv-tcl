package com.home.launcher;

import android.content.Context;
import android.icu.util.IslamicCalendar;
import android.icu.util.ULocale;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * MCA Santa Clara (3003 Scott Blvd) prayer times.
 * Source of truth is Masjidal's public prayer API for MCA (the same feed as the masjid's
 * screens), cached on disk; mcabayarea.org's schedule page is the second source and the
 * bundled 2026 schedule from MCA's yearly PDF is the offline fallback.
 */
public class Prayer {
    public static final String[] NAMES = {"Fajr", "Sunrise", "Dhuhr", "Asr", "Maghrib", "Isha"};
    static final String[] ADHAN_KEYS = {"fajr", "sunrise", "dhuhr", "asr", "maghrib", "isha"};
    static final String[] IQAMAH_KEYS = {"fajr_iq", null, "dhuhr_iq", "asr_iq", "maghrib_iq", "isha_iq"};

    private static final String MASJIDAL_ID = "vOLn92KM";
    private static final String MASJIDAL_URL = "https://masjidal.com/api/v1/time/range?masjid_id=" + MASJIDAL_ID;
    private static final String SCHEDULE_URL = "https://mcabayarea.org/prayerschedule-mca/";
    private static final String HOME_URL = "https://mcabayarea.org/";
    private static final String UA = "Mozilla/5.0 (Linux; Android 14; Smart TV) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/130.0 Safari/537.36";
    private static final String[] MONTHS = {"January", "February", "March", "April", "May", "June",
            "July", "August", "September", "October", "November", "December"};

    private final Context ctx;
    private JSONObject bundled = new JSONObject();
    private JSONObject live = new JSONObject();   // {"days":{date:{...}}, "hijri":{date:text}, "jumuah":[..]}

    public Prayer(Context ctx) {
        this.ctx = ctx;
        try {
            bundled = new JSONObject(readAll(ctx.getAssets().open("mca_2026.json")));
        } catch (Exception ignored) {
        }
        try {
            live = new JSONObject(readAll(new FileInputStream(cacheFile())));
        } catch (Exception ignored) {
        }
    }

    /** Times for a date as minutes since midnight, indexed like NAMES; iqamah -1 when none. */
    public static class Day {
        public int[] adhan = new int[6];
        public int[] iqamah = new int[6];
        public boolean fromMasjidSite;
    }

    public static String key(Calendar c) {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(c.getTime());
    }

    public Day day(Calendar c) {
        String k = key(c);
        JSONObject d = null;
        boolean site = false;
        try {
            JSONObject days = live.optJSONObject("days");
            if (days != null && days.has(k)) {
                d = days.getJSONObject(k);
                site = true;
            } else if (bundled.has(k)) {
                d = bundled.getJSONObject(k);
            }
        } catch (Exception ignored) {
        }
        if (d == null) return null;
        Day out = new Day();
        out.fromMasjidSite = site;
        for (int i = 0; i < 6; i++) {
            out.adhan[i] = minutes(d.optString(ADHAN_KEYS[i], ""));
            out.iqamah[i] = IQAMAH_KEYS[i] == null ? -1 : minutes(d.optString(IQAMAH_KEYS[i], ""));
        }
        // MCA's PDF has no Maghrib iqamah column; the masjid uses adhan + 5 minutes.
        if (out.iqamah[4] < 0 && out.adhan[4] >= 0) out.iqamah[4] = out.adhan[4] + 5;
        return out;
    }

    public String hijri(Calendar c) {
        JSONObject h = live.optJSONObject("hijri");
        if (h != null && h.has(key(c))) return h.optString(key(c));
        IslamicCalendar ic = new IslamicCalendar(ULocale.ENGLISH);
        ic.setCalculationType(IslamicCalendar.CalculationType.ISLAMIC_UMALQURA);
        ic.setTime(c.getTime());
        String[] m = {"Muharram", "Safar", "Rabi al-Awwal", "Rabi al-Thani", "Jumada al-Awwal",
                "Jumada al-Thani", "Rajab", "Sha'ban", "Ramadan", "Shawwal", "Dhu al-Qadah", "Dhu al-Hijjah"};
        return m[ic.get(IslamicCalendar.MONTH)] + " " + ic.get(IslamicCalendar.DAY_OF_MONTH) + ", "
                + ic.get(IslamicCalendar.YEAR);
    }

    /** Jumu'ah khutbah times as shown on MCA's homepage, e.g. ["12:15 PM", "1:30 PM"]. */
    public String[] jumuah() {
        try {
            org.json.JSONArray a = live.getJSONArray("jumuah");
            if (a.length() > 0) {
                String[] out = new String[a.length()];
                for (int i = 0; i < a.length(); i++) out[i] = a.getString(i);
                return out;
            }
        } catch (Exception ignored) {
        }
        return new String[]{"12:15 PM", "1:30 PM"};
    }

    public long lastSync() {
        return live.optLong("synced", 0);
    }

    /** Network refresh. Call off the main thread. */
    public boolean refresh() {
        if (refreshMasjidal()) return true;
        return refreshSite();
    }

    public String source() {
        return live.optString("source", "mcabayarea.org");
    }

    private boolean refreshMasjidal() {
        try {
            Calendar from = Calendar.getInstance();
            from.add(Calendar.DATE, -1);
            Calendar to = Calendar.getInstance();
            to.add(Calendar.DATE, 40);
            JSONObject data = new JSONObject(get(MASJIDAL_URL + "&from_date=" + key(from) + "&to_date=" + key(to)))
                    .getJSONObject("data");
            org.json.JSONArray salah = data.getJSONArray("salah"), iqamah = data.getJSONArray("iqamah");
            SimpleDateFormat in = new SimpleDateFormat("EEEE, MMM d, yyyy", Locale.US);
            JSONObject days = live.optJSONObject("days");
            if (days == null) days = new JSONObject();
            JSONObject hj = live.optJSONObject("hijri");
            if (hj == null) hj = new JSONObject();
            org.json.JSONArray jum = new org.json.JSONArray();
            for (int i = 0; i < salah.length(); i++) {
                JSONObject a = salah.getJSONObject(i), q = iqamah.getJSONObject(i);
                Calendar c = Calendar.getInstance();
                c.setTime(in.parse(a.getString("date")));
                String k = key(c);
                JSONObject d = new JSONObject();
                d.put("fajr", to24(a.getString("fajr")));
                d.put("sunrise", to24(a.getString("sunrise")));
                d.put("dhuhr", to24(a.getString("zuhr")));
                d.put("asr", to24(a.getString("asr")));
                d.put("maghrib", to24(a.getString("maghrib")));
                d.put("isha", to24(a.getString("isha")));
                d.put("fajr_iq", to24(q.getString("fajr")));
                d.put("dhuhr_iq", to24(q.getString("zuhr")));
                d.put("asr_iq", to24(q.getString("asr")));
                d.put("maghrib_iq", to24(q.getString("maghrib")));
                d.put("isha_iq", to24(q.getString("isha")));
                days.put(k, d);
                // "7, 1448" + "Jumada Al-Awwal" -> "Jumada Al-Awwal 7, 1448"
                hj.put(k, a.optString("hijri_month") + " " + a.optString("hijri_date"));
                if (k.equals(key(Calendar.getInstance()))) {
                    for (String jk : new String[]{"jummah1", "jummah2"}) {
                        String t = q.optString(jk, "").trim();
                        if (!t.isEmpty() && !t.equals("-")) jum.put(t.replaceAll("\\s*([AP]M)", " $1"));
                    }
                }
            }
            if (salah.length() == 0) return false;
            live.put("days", days);
            live.put("hijri", hj);
            if (jum.length() > 0) live.put("jumuah", jum);
            live.put("source", "Masjidal");
            save();
            return true;
        } catch (Exception e) {
            android.util.Log.w("HomeLauncher", "masjidal refresh failed", e);
            return false;
        }
    }

    private void save() throws Exception {
        live.put("synced", System.currentTimeMillis());
        try (FileOutputStream out = new FileOutputStream(cacheFile())) {
            out.write(live.toString().getBytes(StandardCharsets.UTF_8));
        }
    }

    /** Fallback: MCA's monthly schedule page. */
    private boolean refreshSite() {
        try {
            String text = textOf(get(SCHEDULE_URL));
            Pattern row = Pattern.compile("(" + String.join("|", MONTHS) + ") (\\d{1,2})(?:st|nd|rd|th) \\w{3} "
                    + "Fajr ([\\d:]+[AP]M) Fajr \\(Iqamah\\) ([\\d:]+[AP]M) Sunrise ([\\d:]+[AP]M) "
                    + "Zuhr ([\\d:]+[AP]M) Zuhr \\(Iqamah\\) ([\\d:]+[AP]M) Asr ([\\d:]+[AP]M) "
                    + "Asr \\(Iqamah\\) ([\\d:]+[AP]M) Maghrib ([\\d:]+[AP]M) Maghrib \\(Iqamah\\) ([\\d:]+[AP]M) "
                    + "Isha ([\\d:]+[AP]M) Isha \\(Iqamah\\) ([\\d:]+[AP]M)");
            String[] keys = {"fajr", "fajr_iq", "sunrise", "dhuhr", "dhuhr_iq", "asr", "asr_iq",
                    "maghrib", "maghrib_iq", "isha", "isha_iq"};
            Calendar now = Calendar.getInstance();
            JSONObject days = live.optJSONObject("days");
            if (days == null) days = new JSONObject();
            Matcher m = row.matcher(text);
            int found = 0;
            while (m.find()) {
                int month = indexOf(MONTHS, m.group(1));
                int year = now.get(Calendar.YEAR);
                // Schedule shows the current month; handle a December page viewed in January etc.
                if (month - now.get(Calendar.MONTH) > 6) year--;
                if (now.get(Calendar.MONTH) - month > 6) year++;
                String k = String.format(Locale.US, "%04d-%02d-%02d", year, month + 1,
                        Integer.parseInt(m.group(2)));
                JSONObject d = new JSONObject();
                for (int i = 0; i < keys.length; i++) d.put(keys[i], to24(m.group(i + 3)));
                days.put(k, d);
                found++;
            }
            if (found == 0) return false;
            live.put("days", days);

            // Hijri date as the masjid shows it ("Sunday, Oct 18, 2026 Jumada Al-Awwal 7, 1448").
            Matcher h = Pattern.compile("\\w+day, \\w{3} \\d{1,2}, \\d{4} ([A-Z][\\w'-]*(?: [A-Z][\\w'-]*)*) (\\d{1,2}), (14\\d\\d)")
                    .matcher(text);
            if (h.find()) {
                JSONObject hj = live.optJSONObject("hijri");
                if (hj == null) hj = new JSONObject();
                hj.put(key(now), h.group(1) + " " + h.group(2) + ", " + h.group(3));
                live.put("hijri", hj);
            }

            try {
                String home = textOf(get(HOME_URL));
                Matcher j = Pattern.compile("Jummah \\d\\s*[:\\-]?\\s*(\\d{1,2}:\\d{2}\\s*[AP]M)").matcher(home);
                org.json.JSONArray arr = new org.json.JSONArray();
                while (j.find() && arr.length() < 2) arr.put(j.group(1).replaceAll("\\s*([AP]M)", " $1"));
                if (arr.length() > 0) live.put("jumuah", arr);
            } catch (Exception ignored) {
            }

            live.put("source", "mcabayarea.org");
            save();
            return true;
        } catch (Exception e) {
            android.util.Log.w("HomeLauncher", "prayer refresh failed", e);
            return false;
        }
    }

    public static String fmt(int minutes) {
        if (minutes < 0) return "";
        int h = (minutes / 60) % 24, m = minutes % 60;
        int h12 = h % 12 == 0 ? 12 : h % 12;
        return String.format(Locale.US, "%d:%02d", h12, m);
    }

    public static String ampm(int minutes) {
        return ((minutes / 60) % 24) < 12 ? "AM" : "PM";
    }

    private File cacheFile() {
        return new File(ctx.getFilesDir(), "mca_live.json");
    }

    static int minutes(String hhmm) {
        if (hhmm == null || !hhmm.contains(":")) return -1;
        String[] p = hhmm.split(":");
        return Integer.parseInt(p[0].trim()) * 60 + Integer.parseInt(p[1].trim());
    }

    static String to24(String t) {
        boolean pm = t.endsWith("PM");
        String[] p = t.substring(0, t.length() - 2).split(":");
        int h = Integer.parseInt(p[0]) % 12 + (pm ? 12 : 0);
        return String.format(Locale.US, "%02d:%s", h, p[1]);
    }

    private static int indexOf(String[] a, String s) {
        for (int i = 0; i < a.length; i++) if (a[i].equals(s)) return i;
        return -1;
    }

    private static String get(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(20000);
        c.setRequestProperty("User-Agent", UA);
        c.setRequestProperty("Accept", "text/html,application/xhtml+xml");
        c.setRequestProperty("Accept-Language", "en-US,en");
        if (c.getResponseCode() != 200) throw new Exception("HTTP " + c.getResponseCode() + " " + url);
        return readAll(c.getInputStream());
    }

    private static String textOf(String html) {
        String t = html.replaceAll("(?s)<script.*?</script>|<style.*?</style>", " ");
        t = t.replaceAll("<[^>]+>", " ");
        t = android.text.Html.fromHtml(t, android.text.Html.FROM_HTML_MODE_LEGACY).toString();
        return t.replaceAll("\\s+", " ");
    }

    static String readAll(InputStream in) throws Exception {
        try (InputStream is = in) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = is.read(buf)) > 0) bo.write(buf, 0, n);
            return new String(bo.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    static Date today() {
        return new Date();
    }
}
