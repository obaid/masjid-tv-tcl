package com.home.launcher;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Every installed TV app, alphabetically, in the same muted style as the home screen. */
public class AppsActivity extends Activity {
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        PackageManager pm = getPackageManager();
        Intent q = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        List<ResolveInfo> apps = new ArrayList<>(pm.queryIntentActivities(q, 0));
        apps.removeIf(r -> r.activityInfo.packageName.equals(getPackageName()));
        Collections.sort(apps, (x, y) -> String.valueOf(x.loadLabel(pm))
                .compareToIgnoreCase(String.valueOf(y.loadLabel(pm))));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{HomeActivity.BG_TOP, HomeActivity.BG_BOTTOM}));
        root.setPadding(dp(56), dp(32), dp(56), 0);

        TextView title = new TextView(this);
        title.setText("ALL APPS");
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        title.setLetterSpacing(0.18f);
        title.setTextColor(HomeActivity.MUTED);
        root.addView(title);

        GridView grid = new GridView(this);
        grid.setNumColumns(6);
        grid.setVerticalSpacing(dp(18));
        grid.setHorizontalSpacing(dp(18));
        grid.setPadding(dp(8), dp(20), dp(8), dp(32));
        grid.setClipToPadding(false);
        grid.setSelector(new GradientDrawable());
        grid.setDrawSelectorOnTop(false);
        grid.setAdapter(new BaseAdapter() {
            public int getCount() { return apps.size(); }
            public Object getItem(int i) { return apps.get(i); }
            public long getItemId(int i) { return i; }

            public View getView(int i, View old, ViewGroup parent) {
                ResolveInfo r = apps.get(i);
                LinearLayout cell = new LinearLayout(AppsActivity.this);
                cell.setOrientation(LinearLayout.VERTICAL);
                cell.setGravity(Gravity.CENTER_HORIZONTAL);
                FrameLayout card = new FrameLayout(AppsActivity.this);
                GradientDrawable bg = new GradientDrawable();
                bg.setColor(HomeActivity.PANEL);
                bg.setCornerRadius(dp(10));
                card.setBackground(bg);
                card.setClipToOutline(true);
                ImageView iv = new ImageView(AppsActivity.this);
                android.graphics.drawable.Drawable art = r.activityInfo.loadBanner(pm);
                if (art == null) art = r.activityInfo.loadIcon(pm);
                iv.setImageDrawable(art);
                iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
                card.addView(iv, new FrameLayout.LayoutParams(-1, -1));
                cell.addView(card, new LinearLayout.LayoutParams(-1, dp(72)));
                TextView name = new TextView(AppsActivity.this);
                name.setText(r.loadLabel(pm));
                name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
                name.setTextColor(HomeActivity.MUTED);
                name.setSingleLine(true);
                name.setPadding(0, dp(6), 0, 0);
                cell.addView(name);
                return cell;
            }
        });
        grid.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            View last;

            public void onItemSelected(android.widget.AdapterView<?> p, View v, int pos, long id) {
                if (last != null) last.animate().scaleX(1f).scaleY(1f).setDuration(120).start();
                v.animate().scaleX(1.08f).scaleY(1.08f).setDuration(120).start();
                last = v;
            }

            public void onNothingSelected(android.widget.AdapterView<?> p) {
            }
        });
        grid.setOnItemClickListener((p, v, pos, id) -> {
            ResolveInfo r = apps.get(pos);
            Intent go = pm.getLeanbackLaunchIntentForPackage(r.activityInfo.packageName);
            if (go == null) go = pm.getLaunchIntentForPackage(r.activityInfo.packageName);
            if (go != null) startActivity(go.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        });
        root.addView(grid, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);
    }

    int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
