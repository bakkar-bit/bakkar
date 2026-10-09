package com.bakkar.deckstarter;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** One screen that walks through: check device, install/update DroidDeck, finish setup. */
public final class MainActivity extends Activity implements InstallResultReceiver.Listener {

    private static final int BG = Color.parseColor("#0F1620");
    private static final int CARD = Color.parseColor("#1A2433");
    private static final int TEXT = Color.parseColor("#E8EEF6");
    private static final int MUTED = Color.parseColor("#9AA8BA");
    private static final int OK = Color.parseColor("#5BD18B");
    private static final int WARN = Color.parseColor("#F2C14E");
    private static final int FAIL = Color.parseColor("#FF6B6B");

    private static final String PREF_REPO = "repo";
    private static final String PREF_PRERELEASES = "prereleases";
    private static final String PREF_PACKAGE = "droiddeck_package";
    private static final int MAX_NOTES_CHARS = 4000;

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;

    private TextView deviceSummary;
    private LinearLayout deviceItems;
    private DeviceCheck.Level deviceLevel;

    private TextView sourceText;
    private TextView releaseText;
    private TextView installedText;
    private TextView statusText;
    private ProgressBar progress;
    private Button primaryButton;
    private Button notesButton;
    private TextView notesText;

    private LinearLayout setupSection;
    private EditText repoInput;
    private CheckBox prereleaseBox;

    private GitHubReleases.Release release;
    private boolean busy;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("deckstarter", MODE_PRIVATE);
        if (!prefs.contains(PREF_REPO)) {
            prefs.edit().putString(PREF_REPO, BuildConfig.DEFAULT_DROIDDECK_REPO).apply();
        }
        setContentView(buildUi());
        InstallResultReceiver.listener = this;
        runDeviceCheck();
        refreshRelease();
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateInstalledState();
    }

    @Override
    protected void onDestroy() {
        if (InstallResultReceiver.listener == this) InstallResultReceiver.listener = null;
        worker.shutdownNow();
        super.onDestroy();
    }

    // ---------------------------------------------------------------- UI construction

    private View buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(BG);
        scroll.setFillViewport(true);
        scroll.setOnApplyWindowInsetsListener((v, insets) -> {
            @SuppressWarnings("deprecation")
            int l = insets.getSystemWindowInsetLeft(), t = insets.getSystemWindowInsetTop(),
                    r = insets.getSystemWindowInsetRight(), b = insets.getSystemWindowInsetBottom();
            v.setPadding(l, t, r, b);
            return insets;
        });

        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        column.setPadding(pad, pad, pad, pad);
        scroll.addView(column, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView title = text("DeckStarter", 26, TEXT);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        column.addView(title);
        column.addView(text("Unofficial setup helper for DroidDeck. Not affiliated with the DroidDeck "
                + "project or Valve. DroidDeck is always downloaded from its GitHub releases.", 13, MUTED));

        // 1. Device check
        LinearLayout device = card(column, "1. Check this device");
        deviceSummary = text("Checking…", 15, TEXT);
        device.addView(deviceSummary);
        deviceItems = new LinearLayout(this);
        deviceItems.setOrientation(LinearLayout.VERTICAL);
        device.addView(deviceItems);

        // 2. Install / update
        LinearLayout get = card(column, "2. Install DroidDeck");
        sourceText = text("", 13, MUTED);
        releaseText = text("", 15, TEXT);
        installedText = text("", 15, TEXT);
        statusText = text("", 13, MUTED);
        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setVisibility(View.GONE);
        primaryButton = button("Install DroidDeck", v -> onPrimary());
        Button checkButton = button("Check for updates", v -> refreshRelease());
        notesButton = button("Show release notes", v -> toggleNotes());
        notesText = text("", 13, MUTED);
        notesText.setVisibility(View.GONE);
        notesText.setTextIsSelectable(true);
        get.addView(sourceText);
        get.addView(releaseText);
        get.addView(installedText);
        get.addView(progress, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(24)));
        get.addView(statusText);
        get.addView(primaryButton);
        get.addView(checkButton);
        get.addView(notesButton);
        get.addView(notesText);

        // 3. Finish setup
        setupSection = card(column, "3. Finish setup");
        setupSection.addView(text("DroidDeck downloads Steam, Proton and GPU drivers itself on first "
                + "launch. Keep the screen on and stay on Wi-Fi until it finishes.", 13, MUTED));
        setupSection.addView(button("Open DroidDeck", v -> launchDroidDeck()));
        setupSection.addView(button("Use DroidDeck as home screen (optional)",
                v -> openSettings(new Intent(Settings.ACTION_HOME_SETTINGS))));
        setupSection.addView(button("Stop battery saver killing DroidDeck",
                v -> openSettings(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))));
        setupSection.addView(button("DroidDeck permissions & storage", v -> openDroidDeckAppInfo()));
        setupSection.addView(button("Official DroidDeck project page", v -> openRepoPage()));

        // Settings
        LinearLayout settings = card(column, "Settings");
        settings.addView(text("Official DroidDeck GitHub repository (owner/name). Only change this if "
                + "you know the project moved.", 13, MUTED));
        repoInput = new EditText(this);
        repoInput.setSingleLine(true);
        repoInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        repoInput.setHint("owner/DroidDeck");
        repoInput.setTextColor(TEXT);
        repoInput.setHintTextColor(MUTED);
        repoInput.setText(repo());
        settings.addView(repoInput);
        prereleaseBox = new CheckBox(this);
        prereleaseBox.setText("Include pre-releases");
        prereleaseBox.setTextColor(TEXT);
        prereleaseBox.setChecked(prefs.getBoolean(PREF_PRERELEASES, false));
        settings.addView(prereleaseBox);
        settings.addView(button("Save", v -> saveSettings()));
        settings.addView(text("DeckStarter " + BuildConfig.VERSION_NAME, 12, MUTED));

        primaryButton.requestFocus();
        return scroll;
    }

    private LinearLayout card(LinearLayout parent, String heading) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        card.setPadding(pad, pad, pad, pad);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(CARD);
        bg.setCornerRadius(dp(12));
        card.setBackground(bg);
        TextView h = text(heading, 18, TEXT);
        h.setTypeface(Typeface.DEFAULT_BOLD);
        card.addView(h);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(16);
        parent.addView(card, lp);
        return card;
    }

    private TextView text(String s, int sp, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setPadding(0, dp(4), 0, dp(4));
        return t;
    }

    private Button button(String label, View.OnClickListener onClick) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setOnClickListener(onClick);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(8);
        b.setLayoutParams(lp);
        return b;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    // ---------------------------------------------------------------- Step 1: device

    private void runDeviceCheck() {
        worker.execute(() -> {
            List<DeviceCheck.Item> items = DeviceCheck.run(getApplicationContext());
            main.post(() -> showDeviceCheck(items));
        });
    }

    private void showDeviceCheck(List<DeviceCheck.Item> items) {
        deviceLevel = DeviceCheck.worst(items);
        switch (deviceLevel) {
            case OK:
                deviceSummary.setText("This device meets DroidDeck's requirements.");
                deviceSummary.setTextColor(OK);
                break;
            case WARN:
                deviceSummary.setText("This device may run DroidDeck, with caveats below.");
                deviceSummary.setTextColor(WARN);
                break;
            default:
                deviceSummary.setText("This device does not meet DroidDeck's requirements.");
                deviceSummary.setTextColor(FAIL);
        }
        deviceItems.removeAllViews();
        for (DeviceCheck.Item item : items) {
            String mark = item.level == DeviceCheck.Level.OK ? "✓" : item.level == DeviceCheck.Level.WARN ? "!" : "✗";
            int color = item.level == DeviceCheck.Level.OK ? OK : item.level == DeviceCheck.Level.WARN ? WARN : FAIL;
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            TextView m = text(mark, 15, color);
            m.setTypeface(Typeface.DEFAULT_BOLD);
            m.setMinWidth(dp(24));
            m.setGravity(Gravity.CENTER_HORIZONTAL);
            row.addView(m);
            LinearLayout col = new LinearLayout(this);
            col.setOrientation(LinearLayout.VERTICAL);
            col.addView(text(item.label, 14, TEXT));
            TextView d = text(item.detail, 12, MUTED);
            d.setPadding(0, 0, 0, dp(4));
            col.addView(d);
            row.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            deviceItems.addView(row);
        }
    }

    // ---------------------------------------------------------------- Step 2: release + install

    private String repo() {
        return prefs.getString(PREF_REPO, "").trim();
    }

    private void refreshRelease() {
        String repo = repo();
        boolean pre = prefs.getBoolean(PREF_PRERELEASES, false);
        release = null;
        if (!GitHubReleases.isValidRepo(repo)) {
            sourceText.setText("Source: not set");
            releaseText.setText("Enter the official DroidDeck GitHub repository in Settings below.");
            updateInstalledState();
            return;
        }
        sourceText.setText("Source: github.com/" + repo);
        releaseText.setText("Looking for the latest release…");
        updateInstalledState();
        worker.execute(() -> {
            try {
                GitHubReleases.Release r = GitHubReleases.fetchLatest(repo, pre);
                main.post(() -> {
                    release = r;
                    showRelease();
                });
            } catch (Exception e) {
                main.post(() -> releaseText.setText("Could not check GitHub: " + e.getMessage()));
            }
        });
    }

    private void showRelease() {
        GitHubReleases.Release r = release;
        String date = r.publishedAt.length() >= 10 ? r.publishedAt.substring(0, 10) : r.publishedAt;
        StringBuilder sb = new StringBuilder("Latest: ").append(r.tag);
        if (r.prerelease) sb.append(" (pre-release)");
        if (!date.isEmpty()) sb.append(" · ").append(date);
        if (r.apk != null) {
            sb.append("\n").append(r.apk.name).append(" · ").append(DeviceCheck.formatBytes(r.apk.size));
            if (r.apk.sha256 == null) sb.append("\n(GitHub gave no checksum for this file; it will not be verified)");
        } else {
            sb.append("\nThis release has no Android APK attached.");
        }
        releaseText.setText(sb);
        String notes = r.notes == null ? "" : r.notes.trim();
        if (notes.length() > MAX_NOTES_CHARS) notes = notes.substring(0, MAX_NOTES_CHARS) + "…";
        notesText.setText(notes.isEmpty() ? "No release notes." : notes);
        updateInstalledState();
    }

    private void toggleNotes() {
        boolean show = notesText.getVisibility() != View.VISIBLE;
        notesText.setVisibility(show ? View.VISIBLE : View.GONE);
        notesButton.setText(show ? "Hide release notes" : "Show release notes");
    }

    /** Finds DroidDeck: the package we installed, else a launcher app labelled "DroidDeck". */
    private PackageInfo findDroidDeck() {
        PackageInfo info = ApkInstaller.installedInfo(this, prefs.getString(PREF_PACKAGE, null));
        if (info != null) return info;
        PackageManager pm = getPackageManager();
        Intent launcher = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        for (ResolveInfo ri : pm.queryIntentActivities(launcher, 0)) {
            String label = String.valueOf(ri.loadLabel(pm)).toLowerCase(Locale.ROOT);
            String pkg = ri.activityInfo.packageName;
            if (label.replace(" ", "").contains("droiddeck") && !pkg.equals(getPackageName())) {
                prefs.edit().putString(PREF_PACKAGE, pkg).apply();
                return ApkInstaller.installedInfo(this, pkg);
            }
        }
        return null;
    }

    private void updateInstalledState() {
        if (installedText == null) return;
        PackageInfo installed = findDroidDeck();
        setupSection.setVisibility(installed != null ? View.VISIBLE : View.GONE);
        if (installed == null) {
            installedText.setText("Installed: not yet");
            primaryButton.setText("Download & install DroidDeck");
        } else {
            installedText.setText("Installed: " + installed.versionName);
            boolean newer = release != null && release.apk != null
                    && GitHubReleases.compareVersions(release.tag, installed.versionName) > 0;
            primaryButton.setText(newer ? "Update DroidDeck to " + release.tag : "Open DroidDeck");
        }
        primaryButton.setEnabled(!busy);
    }

    private void onPrimary() {
        PackageInfo installed = findDroidDeck();
        boolean newer = installed != null && release != null && release.apk != null
                && GitHubReleases.compareVersions(release.tag, installed.versionName) > 0;
        if (installed != null && !newer) {
            launchDroidDeck();
            return;
        }
        if (release == null) {
            toast(GitHubReleases.isValidRepo(repo())
                    ? "Still checking GitHub; try again in a moment" : "Set the DroidDeck repository in Settings first");
            return;
        }
        if (release.apk == null) {
            toast("The latest release has no APK to install");
            return;
        }
        if (!getPackageManager().canRequestPackageInstalls()) {
            new AlertDialog.Builder(this)
                    .setTitle("Allow installing apps")
                    .setMessage("Android needs your permission for DeckStarter to install DroidDeck. "
                            + "Turn on \"Allow from this source\", then come back and tap the button again.")
                    .setPositiveButton("Open settings", (d, w) -> openSettings(new Intent(
                            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + getPackageName()))))
                    .setNegativeButton("Cancel", null)
                    .show();
            return;
        }
        if (deviceLevel == DeviceCheck.Level.FAIL && installed == null) {
            new AlertDialog.Builder(this)
                    .setTitle("Device not supported")
                    .setMessage("This device fails DroidDeck's requirements (see step 1). It will "
                            + "probably not work. Install anyway?")
                    .setPositiveButton("Install anyway", (d, w) -> downloadAndInstall(release.apk))
                    .setNegativeButton("Cancel", null)
                    .show();
            return;
        }
        downloadAndInstall(release.apk);
    }

    private void downloadAndInstall(GitHubReleases.Asset asset) {
        setBusy(true);
        progress.setVisibility(View.VISIBLE);
        progress.setIndeterminate(asset.size <= 0);
        progress.setMax(1000);
        progress.setProgress(0);
        statusText.setText("Downloading…");
        String expectedPackage = prefs.getString(PREF_PACKAGE, null);
        worker.execute(() -> {
            try {
                File apk = ApkInstaller.download(getApplicationContext(), asset, (done, total) -> main.post(() -> {
                    if (total > 0) progress.setProgress((int) (done * 1000 / total));
                    statusText.setText("Downloading… " + DeviceCheck.formatBytes(done)
                            + (total > 0 ? " of " + DeviceCheck.formatBytes(total) : ""));
                }));
                PackageInfo info = ApkInstaller.inspect(getApplicationContext(), apk);
                if (info == null) throw new java.io.IOException("The downloaded file is not a valid APK");
                if (expectedPackage != null && !expectedPackage.equals(info.packageName)) {
                    throw new java.io.IOException("The download is " + info.packageName
                            + ", but the installed DroidDeck is " + expectedPackage + ". Check the repository in Settings.");
                }
                prefs.edit().putString(PREF_PACKAGE, info.packageName).apply();
                main.post(() -> statusText.setText(asset.sha256 != null
                        ? "Checksum verified. Installing…" : "Installing…"));
                ApkInstaller.install(getApplicationContext(), apk);
            } catch (Exception e) {
                main.post(() -> {
                    setBusy(false);
                    progress.setVisibility(View.GONE);
                    statusText.setText(e.getMessage());
                    statusText.setTextColor(FAIL);
                });
            }
        });
    }

    @Override
    public void onInstallResult(boolean success, String message) {
        main.post(() -> {
            setBusy(false);
            progress.setVisibility(View.GONE);
            statusText.setText(success ? message + ". Continue with step 3." : message);
            statusText.setTextColor(success ? OK : FAIL);
            updateInstalledState();
        });
    }

    private void setBusy(boolean value) {
        busy = value;
        if (value) statusText.setTextColor(MUTED);
        primaryButton.setEnabled(!value);
    }

    // ---------------------------------------------------------------- Step 3: setup helpers

    private void launchDroidDeck() {
        PackageInfo info = findDroidDeck();
        Intent launch = info == null ? null : getPackageManager().getLaunchIntentForPackage(info.packageName);
        if (launch == null) {
            toast("DroidDeck is not installed yet");
            return;
        }
        startActivity(launch);
    }

    private void openDroidDeckAppInfo() {
        PackageInfo info = findDroidDeck();
        if (info == null) {
            toast("DroidDeck is not installed yet");
            return;
        }
        openSettings(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:" + info.packageName)));
    }

    private void openRepoPage() {
        if (!GitHubReleases.isValidRepo(repo())) {
            toast("Set the DroidDeck repository in Settings first");
            return;
        }
        openSettings(new Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/" + repo())));
    }

    private void openSettings(Intent intent) {
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException e) {
            toast("This device has no screen for that setting");
        }
    }

    // ---------------------------------------------------------------- Settings

    private void saveSettings() {
        String value = repoInput.getText().toString().trim()
                .replaceFirst("^(https?://)?(www\\.)?github\\.com/", "")
                .replaceFirst("(\\.git)?/*$", "");
        if (!value.isEmpty() && !GitHubReleases.isValidRepo(value)) {
            toast("Use the form owner/name, e.g. someone/DroidDeck");
            return;
        }
        boolean repoChanged = !value.equals(repo());
        SharedPreferences.Editor edit = prefs.edit()
                .putString(PREF_REPO, value)
                .putBoolean(PREF_PRERELEASES, prereleaseBox.isChecked());
        // A different source may ship a different package; forget the remembered one.
        if (repoChanged) edit.remove(PREF_PACKAGE);
        edit.apply();
        repoInput.setText(value);
        toast("Saved");
        refreshRelease();
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }
}
