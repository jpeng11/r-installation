package dev.jpeng.rinstaller;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class TrustedSourcesActivity extends LocalizedActivity {
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private TrustedStore store;
    private SourceAdapter adapter;
    private TextView helper;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        store = new TrustedStore(this);
        buildUi();
        loadApps();
    }

    @Override
    protected void onDestroy() {
        executor.shutdownNow();
        super.onDestroy();
    }

    private void buildUi() {
        LinearLayout root = Ui.screenRoot(this);
        root.addView(Ui.toolbar(this, getString(R.string.silent_install_title), true));

        EditText search = new EditText(this);
        search.setHint(R.string.search_app_name);
        search.setSingleLine(true);
        search.setTextSize(16);
        search.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        search.setBackgroundResource(R.drawable.bg_search_field);
        search.setCompoundDrawablesRelativeWithIntrinsicBounds(
                R.drawable.ic_search, 0, 0, 0);
        search.setCompoundDrawablePadding(Ui.dp(this, 10));
        search.setPadding(Ui.dp(this, 16), 0, Ui.dp(this, 16), 0);
        LinearLayout.LayoutParams searchParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 50));
        searchParams.setMargins(Ui.dp(this, 12), Ui.dp(this, 10),
                Ui.dp(this, 12), 0);
        root.addView(search, searchParams);

        helper = Ui.text(this, getString(R.string.loading_apps), 13);
        helper.setTextColor(getColor(R.color.text_secondary));
        helper.setPadding(Ui.dp(this, 20), Ui.dp(this, 8),
                Ui.dp(this, 20), Ui.dp(this, 6));
        root.addView(helper);

        ListView list = new ListView(this);
        adapter = new SourceAdapter(this);
        list.setAdapter(adapter);
        list.setDivider(null);
        list.setClipToPadding(false);
        list.setPadding(0, 0, 0, Ui.dp(this, 16));
        root.addView(list, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                adapter.filter(s == null ? "" : s.toString());
            }
            @Override public void afterTextChanged(Editable s) {}
        });
    }

    private void loadApps() {
        executor.execute(() -> {
            PackageManager packageManager = getPackageManager();
            Set<String> trusted = store.packages();
            List<AppEntry> entries = new ArrayList<>();
            for (ApplicationInfo info : packageManager.getInstalledApplications(0)) {
                if (!info.enabled || info.packageName.equals(getPackageName())) {
                    continue;
                }
                CharSequence labelValue = info.loadLabel(packageManager);
                String label = labelValue == null ? info.packageName : labelValue.toString();
                entries.add(new AppEntry(
                        label,
                        info.packageName,
                        info,
                        trusted.contains(info.packageName)));
            }
            entries.sort(Comparator
                    .comparing((AppEntry value) -> !value.trusted)
                    .thenComparing(value -> value.label.toLowerCase(Locale.ROOT))
                    .thenComparing(value -> value.packageName));
            runOnUiThread(() -> {
                adapter.setEntries(entries);
                updateHelper();
            });
        });
    }

    private void updateHelper() {
        int count = store.packages().size();
        String authorized = getResources().getQuantityString(
                R.plurals.authorized_apps, count, count);
        helper.setText(getString(R.string.search_helper, authorized));
    }

    private record AppEntry(
            String label,
            String packageName,
            ApplicationInfo applicationInfo,
            boolean trusted
    ) {
        AppEntry withTrusted(boolean value) {
            return new AppEntry(label, packageName, applicationInfo, value);
        }
    }

    private final class SourceAdapter extends BaseAdapter {
        private final Context context;
        private final List<AppEntry> all = new ArrayList<>();
        private final List<AppEntry> visible = new ArrayList<>();
        private String query = "";

        SourceAdapter(Context context) {
            this.context = context;
        }

        void setEntries(List<AppEntry> entries) {
            all.clear();
            all.addAll(entries);
            applyFilter();
        }

        void filter(String value) {
            query = value == null ? "" : value;
            applyFilter();
        }

        private void applyFilter() {
            visible.clear();
            for (AppEntry entry : all) {
                if (AppSearch.matches(entry.label, entry.packageName, query)) {
                    visible.add(entry);
                }
            }
            notifyDataSetChanged();
        }

        @Override public int getCount() { return visible.size(); }
        @Override public AppEntry getItem(int position) { return visible.get(position); }
        @Override public long getItemId(int position) { return position; }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            AppRow row = convertView instanceof AppRow
                    ? (AppRow) convertView
                    : new AppRow(context);
            AppEntry entry = getItem(position);

            row.icon.setImageDrawable(entry.applicationInfo.loadIcon(getPackageManager()));
            row.name.setText(entry.label);
            row.packageName.setText(entry.packageName);
            row.toggle.setOnCheckedChangeListener(null);
            row.toggle.setChecked(store.isTrusted(entry.packageName));
            row.toggle.setOnCheckedChangeListener((button, checked) -> {
                boolean success;
                if (checked) {
                    success = store.trust(entry.packageName);
                } else {
                    store.untrust(entry.packageName);
                    success = true;
                }
                if (!success) {
                    button.setOnCheckedChangeListener(null);
                    button.setChecked(false);
                    Toast.makeText(context, R.string.cannot_pin_certificate,
                            Toast.LENGTH_LONG).show();
                }
                replaceEntry(entry.packageName, checked && success);
                updateHelper();
            });
            row.setContentDescription(entry.label + ", " + entry.packageName);
            row.setOnClickListener(view -> row.toggle.toggle());
            return row;
        }

        private void replaceEntry(String packageName, boolean trusted) {
            for (int index = 0; index < all.size(); index++) {
                AppEntry entry = all.get(index);
                if (entry.packageName.equals(packageName)) {
                    all.set(index, entry.withTrusted(trusted));
                    break;
                }
            }
        }
    }

    private static final class AppRow extends LinearLayout {
        final ImageView icon;
        final TextView name;
        final TextView packageName;
        final Switch toggle;

        AppRow(Context context) {
            super(context);
            setOrientation(HORIZONTAL);
            setGravity(Gravity.CENTER_VERTICAL);
            setMinimumHeight(Ui.dp(context, 76));
            setPadding(Ui.dp(context, 16), Ui.dp(context, 9),
                    Ui.dp(context, 16), Ui.dp(context, 9));
            setBackground(Ui.selectableBackground(context));
            setClickable(true);
            setFocusable(true);

            icon = new ImageView(context);
            addView(icon, new LayoutParams(Ui.dp(context, 44), Ui.dp(context, 44)));

            LinearLayout copy = new LinearLayout(context);
            copy.setOrientation(VERTICAL);
            LayoutParams copyParams = new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1);
            copyParams.setMarginStart(Ui.dp(context, 16));
            addView(copy, copyParams);

            name = Ui.text(context, "", 16);
            name.setTypeface(android.graphics.Typeface.create(
                    "sans-serif-medium", android.graphics.Typeface.NORMAL));
            name.setSingleLine(true);
            name.setEllipsize(TextUtils.TruncateAt.END);
            copy.addView(name);

            packageName = Ui.text(context, "", 14);
            packageName.setTextColor(context.getColor(R.color.text_secondary));
            packageName.setSingleLine(true);
            packageName.setEllipsize(TextUtils.TruncateAt.MIDDLE);
            packageName.setPadding(0, Ui.dp(context, 3), 0, 0);
            copy.addView(packageName);

            toggle = new Switch(context);
            toggle.setFocusable(false);
            toggle.setClickable(false);
            addView(toggle);
        }
    }
}
