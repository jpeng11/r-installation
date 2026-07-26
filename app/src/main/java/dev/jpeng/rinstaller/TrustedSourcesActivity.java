package dev.jpeng.rinstaller;

import android.app.Activity;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class TrustedSourcesActivity extends Activity {
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private TrustedStore store;
    private SourceAdapter adapter;
    private TextView summary;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        store = new TrustedStore(this);
        buildUi();
        loadApps();
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int padding = Ui.dp(this, 16);
        root.setPadding(padding, padding, padding, padding);
        setContentView(root);

        root.addView(Ui.title(this, "Trusted source apps"));
        root.addView(Ui.text(this,
                "Checked apps may trigger silent installation only when their identity can be "
                        + "verified. Their signing certificate is pinned now and checked on every request.",
                14));
        summary = Ui.text(this, "Loading installed apps…", 14);
        summary.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 8));
        root.addView(summary);

        EditText search = new EditText(this);
        search.setHint("Search app name or package");
        search.setSingleLine(true);
        root.addView(search, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        ListView list = new ListView(this);
        adapter = new SourceAdapter(this);
        list.setAdapter(adapter);
        root.addView(list, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                adapter.filter(s.toString());
            }
            @Override public void afterTextChanged(Editable s) {}
        });
    }

    private void loadApps() {
        executor.execute(() -> {
            PackageManager pm = getPackageManager();
            Set<String> trusted = store.packages();
            List<AppEntry> entries = new ArrayList<>();
            for (ApplicationInfo info : pm.getInstalledApplications(0)) {
                if (!info.enabled || info.packageName.equals(getPackageName())) {
                    continue;
                }
                CharSequence labelValue = info.loadLabel(pm);
                String label = labelValue == null ? info.packageName : labelValue.toString();
                boolean system = (info.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
                entries.add(new AppEntry(label, info.packageName, system,
                        trusted.contains(info.packageName)));
            }
            entries.sort(Comparator
                    .comparing((AppEntry value) -> !value.trusted)
                    .thenComparing(value -> value.label.toLowerCase(Locale.ROOT))
                    .thenComparing(value -> value.packageName));
            runOnUiThread(() -> {
                adapter.setEntries(entries);
                updateSummary();
            });
        });
    }

    private void updateSummary() {
        summary.setText(store.packages().size() + " trusted source app(s)");
    }

    private record AppEntry(String label, String packageName, boolean system, boolean trusted) {
        AppEntry withTrusted(boolean value) {
            return new AppEntry(label, packageName, system, value);
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
            query = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
            applyFilter();
        }

        private void applyFilter() {
            visible.clear();
            for (AppEntry entry : all) {
                if (query.isEmpty()
                        || entry.label.toLowerCase(Locale.ROOT).contains(query)
                        || entry.packageName.toLowerCase(Locale.ROOT).contains(query)) {
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
            CheckBox box = convertView instanceof CheckBox
                    ? (CheckBox) convertView
                    : new CheckBox(context);
            AppEntry entry = getItem(position);
            box.setOnCheckedChangeListener(null);
            box.setPadding(Ui.dp(context, 6), Ui.dp(context, 8),
                    Ui.dp(context, 6), Ui.dp(context, 8));
            box.setText(entry.label + (entry.system ? " · system" : "")
                    + "\n" + entry.packageName);
            box.setChecked(store.isTrusted(entry.packageName));
            box.setOnCheckedChangeListener((button, checked) -> {
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
                    Toast.makeText(context, "Unable to pin this app’s signing certificate.",
                            Toast.LENGTH_LONG).show();
                }
                replaceEntry(entry.packageName, checked && success);
                updateSummary();
            });
            return box;
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
}
