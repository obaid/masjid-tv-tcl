package com.home.launcher;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Upcoming events from MCA's public Google Calendar (the one linked from the masjid's
 * Masjidal profile). One-off events only; recurring entries (RRULE) are skipped.
 */
public class Events {
    private static final String ICS = "https://calendar.google.com/calendar/ical/"
            + "c_0f12fe7cae6832644dd87c48e910a7e82060b70a81d12cd9def13d6764be61bf%40group.calendar.google.com"
            + "/public/basic.ics";

    private final File cache;

    public Events(Context c) {
        cache = new File(c.getFilesDir(), "mca_events.json");
    }

    /** [{"t": epochMillis, "allDay": bool, "title": "..."}] sorted, future only. */
    public List<JSONObject> upcoming(int max) {
        List<JSONObject> out = new ArrayList<>();
        try {
            JSONObject root = new JSONObject(Prayer.readAll(new FileInputStream(cache)));
            JSONArray a = root.getJSONArray("events");
            long now = System.currentTimeMillis() - 2 * 3600 * 1000L;
            for (int i = 0; i < a.length() && out.size() < max; i++) {
                JSONObject e = a.getJSONObject(i);
                long end = e.getLong("t") + (e.optBoolean("allDay") ? 86400000L : 0);
                if (end >= now) out.add(e);
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    public long lastSync() {
        try {
            return new JSONObject(Prayer.readAll(new FileInputStream(cache))).optLong("synced", 0);
        } catch (Exception e) {
            return 0;
        }
    }

    /** Call off the main thread. */
    public boolean refresh() {
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(ICS).openConnection();
            c.setConnectTimeout(15000);
            c.setReadTimeout(30000);
            String ics = Prayer.readAll(c.getInputStream()).replace("\r\n ", "").replace("\n ", "");
            Matcher ev = Pattern.compile("BEGIN:VEVENT(.*?)END:VEVENT", Pattern.DOTALL).matcher(ics);
            Pattern start = Pattern.compile("^DTSTART(;[^:\\r\\n]*)?:(\\d{8})(T(\\d{6})(Z)?)?", Pattern.MULTILINE);
            Pattern tzid = Pattern.compile("TZID=([^;:]+)");
            Pattern summary = Pattern.compile("^SUMMARY:(.*)$", Pattern.MULTILINE);
            long horizon = System.currentTimeMillis() - 86400000L;
            List<JSONObject> events = new ArrayList<>();
            while (ev.find()) {
                String body = ev.group(1);
                if (body.contains("RRULE:")) continue;
                Matcher s = start.matcher(body), n = summary.matcher(body);
                if (!s.find() || !n.find()) continue;
                boolean allDay = s.group(3) == null;
                String zone = "America/Los_Angeles";
                if (s.group(1) != null) {
                    Matcher z = tzid.matcher(s.group(1));
                    if (z.find()) zone = z.group(1);
                }
                SimpleDateFormat f = new SimpleDateFormat(allDay ? "yyyyMMdd" : "yyyyMMddHHmmss", Locale.US);
                f.setTimeZone(TimeZone.getTimeZone(s.group(5) != null ? "UTC" : zone));
                Date d = f.parse(allDay ? s.group(2) : s.group(2) + s.group(4));
                if (d == null || d.getTime() < horizon) continue;
                JSONObject e = new JSONObject();
                e.put("t", d.getTime());
                e.put("allDay", allDay);
                e.put("title", n.group(1).trim().replace("\\,", ",").replace("\\;", ";"));
                events.add(e);
            }
            Collections.sort(events, (x, y) -> Long.compare(x.optLong("t"), y.optLong("t")));
            JSONArray arr = new JSONArray();
            for (int i = 0; i < events.size() && i < 50; i++) arr.put(events.get(i));
            JSONObject root = new JSONObject();
            root.put("events", arr);
            root.put("synced", System.currentTimeMillis());
            try (FileOutputStream out = new FileOutputStream(cache)) {
                out.write(root.toString().getBytes(StandardCharsets.UTF_8));
            }
            return true;
        } catch (Exception e) {
            android.util.Log.w("HomeLauncher", "events refresh failed", e);
            return false;
        }
    }
}
