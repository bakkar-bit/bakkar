package com.bakkar.deckstarter;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.regex.Pattern;

/** Reads release metadata for a GitHub repository through the public REST API. */
final class GitHubReleases {

    static final Pattern REPO = Pattern.compile("[A-Za-z0-9-]{1,39}/[A-Za-z0-9._-]{1,100}");

    static final class Release {
        String tag;
        String name;
        String publishedAt;
        String notes;
        String pageUrl;
        boolean prerelease;
        Asset apk;
    }

    static final class Asset {
        String name;
        long size;
        String downloadUrl;
        /** Lowercase hex SHA-256 from GitHub's asset digest, or null if GitHub did not report one. */
        String sha256;
    }

    private GitHubReleases() {}

    static boolean isValidRepo(String repo) {
        return repo != null && REPO.matcher(repo).matches()
                && !repo.endsWith("/.") && !repo.endsWith("/..");
    }

    /** Fetches the newest published release, optionally including pre-releases. Blocking. */
    static Release fetchLatest(String repo, boolean includePrereleases) throws IOException {
        if (!isValidRepo(repo)) throw new IOException("Invalid repository: " + repo);
        try {
            JSONObject json;
            if (includePrereleases) {
                JSONArray list = new JSONArray(get("https://api.github.com/repos/" + repo + "/releases?per_page=10"));
                json = null;
                for (int i = 0; i < list.length(); i++) {
                    JSONObject candidate = list.getJSONObject(i);
                    if (!candidate.optBoolean("draft")) {
                        json = candidate;
                        break;
                    }
                }
                if (json == null) throw new IOException("No releases published in " + repo);
            } else {
                json = new JSONObject(get("https://api.github.com/repos/" + repo + "/releases/latest"));
            }
            return parse(json);
        } catch (JSONException e) {
            throw new IOException("Unexpected response from GitHub", e);
        }
    }

    private static Release parse(JSONObject json) throws JSONException {
        Release r = new Release();
        r.tag = json.optString("tag_name");
        r.name = json.optString("name", r.tag);
        r.publishedAt = json.optString("published_at");
        r.notes = json.optString("body");
        r.pageUrl = json.optString("html_url");
        r.prerelease = json.optBoolean("prerelease");

        JSONArray assets = json.optJSONArray("assets");
        int bestScore = Integer.MIN_VALUE;
        for (int i = 0; assets != null && i < assets.length(); i++) {
            JSONObject a = assets.getJSONObject(i);
            String name = a.optString("name");
            int score = scoreApk(name);
            if (score == Integer.MIN_VALUE || score <= bestScore) continue;
            bestScore = score;
            Asset asset = new Asset();
            asset.name = name;
            asset.size = a.optLong("size");
            asset.downloadUrl = a.optString("browser_download_url");
            String digest = a.isNull("digest") ? "" : a.optString("digest");
            asset.sha256 = digest.startsWith("sha256:") ? digest.substring(7).toLowerCase(Locale.ROOT) : null;
            r.apk = asset;
        }
        return r;
    }

    /** Ranks release assets so an arm64 or universal APK wins; MIN_VALUE means "not usable". */
    static int scoreApk(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        if (!n.endsWith(".apk")) return Integer.MIN_VALUE;
        if (n.contains("x86") || n.contains("armeabi") || n.contains("armv7")) return Integer.MIN_VALUE;
        int score = 0;
        if (n.contains("arm64") || n.contains("aarch64")) score += 3;
        if (n.contains("universal")) score += 2;
        if (n.contains("release")) score += 1;
        if (n.contains("debug")) score -= 2;
        return score;
    }

    private static String get(String url) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(30000);
        conn.setRequestProperty("Accept", "application/vnd.github+json");
        conn.setRequestProperty("X-GitHub-Api-Version", "2022-11-28");
        conn.setRequestProperty("User-Agent", "DeckStarter/" + BuildConfig.VERSION_NAME);
        try {
            int code = conn.getResponseCode();
            if (code == 404) throw new IOException("No published release found (is the repository name right?)");
            if (code == 403 || code == 429) throw new IOException("GitHub rate limit reached; try again later");
            if (code != 200) throw new IOException("GitHub returned HTTP " + code);
            try (InputStream in = conn.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                return out.toString(StandardCharsets.UTF_8.name());
            }
        } finally {
            conn.disconnect();
        }
    }

    /** Compares dotted version strings numerically, ignoring a leading "v" and any suffix. */
    static int compareVersions(String a, String b) {
        String[] pa = normalize(a).split("\\.");
        String[] pb = normalize(b).split("\\.");
        for (int i = 0; i < Math.max(pa.length, pb.length); i++) {
            int x = i < pa.length ? leadingInt(pa[i]) : 0;
            int y = i < pb.length ? leadingInt(pb[i]) : 0;
            if (x != y) return Integer.compare(x, y);
        }
        return 0;
    }

    private static String normalize(String v) {
        v = v == null ? "" : v.trim();
        if (v.startsWith("v") || v.startsWith("V")) v = v.substring(1);
        return v;
    }

    private static int leadingInt(String s) {
        int end = 0;
        while (end < s.length() && end < 9 && Character.isDigit(s.charAt(end))) end++;
        return end == 0 ? 0 : Integer.parseInt(s.substring(0, end));
    }
}
