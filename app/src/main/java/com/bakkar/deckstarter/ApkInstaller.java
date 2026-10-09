package com.bakkar.deckstarter;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.content.pm.SigningInfo;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;

/** Downloads a release APK, verifies it and hands it to the system package installer. */
final class ApkInstaller {

    interface Progress {
        void onProgress(long done, long total);
    }

    private ApkInstaller() {}

    /** Downloads the asset into the cache and checks its SHA-256 when GitHub supplied one. Blocking. */
    static File download(Context context, GitHubReleases.Asset asset, Progress progress) throws IOException {
        if (!asset.downloadUrl.startsWith("https://github.com/")) {
            throw new IOException("Refusing to download from outside github.com: " + asset.downloadUrl);
        }
        File dir = new File(context.getCacheDir(), "downloads");
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("Cannot create " + dir);
        // Only ever keep the APK being installed.
        File[] old = dir.listFiles();
        if (old != null) for (File f : old) f.delete();
        File target = new File(dir, "droiddeck.apk");

        MessageDigest sha256;
        try {
            sha256 = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IOException(e);
        }

        HttpURLConnection conn = (HttpURLConnection) new URL(asset.downloadUrl).openConnection();
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(60000);
        conn.setRequestProperty("User-Agent", "DeckStarter/" + BuildConfig.VERSION_NAME);
        try {
            int code = conn.getResponseCode();
            if (code != 200) throw new IOException("Download failed: HTTP " + code);
            long total = conn.getContentLengthLong() > 0 ? conn.getContentLengthLong() : asset.size;
            long done = 0;
            long lastReport = 0;
            try (InputStream in = conn.getInputStream(); OutputStream out = new FileOutputStream(target)) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    sha256.update(buf, 0, n);
                    done += n;
                    if (done - lastReport >= 512 * 1024) {
                        lastReport = done;
                        progress.onProgress(done, total);
                    }
                }
            }
            progress.onProgress(done, total);
            if (asset.size > 0 && done != asset.size) {
                throw new IOException("Download incomplete (" + done + " of " + asset.size + " bytes)");
            }
        } catch (IOException e) {
            target.delete();
            throw e;
        } finally {
            conn.disconnect();
        }

        if (asset.sha256 != null) {
            String actual = toHex(sha256.digest());
            if (!actual.equals(asset.sha256)) {
                target.delete();
                throw new IOException("Checksum mismatch: the download is corrupt or was tampered with");
            }
        }
        return target;
    }

    /**
     * Reads the package name, version and signers out of an APK file, or null if it is not a
     * valid APK.
     */
    static PackageInfo inspect(Context context, File apk) {
        return context.getPackageManager()
                .getPackageArchiveInfo(apk.getPath(), PackageManager.GET_SIGNING_CERTIFICATES);
    }

    enum SignerResult { MATCH, MISMATCH, UNKNOWN }

    /**
     * Checks that the APK is signed by the certificate with the given SHA-256, either directly or
     * through a signed key-rotation lineage (which only the holder of that key can produce).
     */
    static SignerResult checkSigner(PackageInfo info, String expectedSha256) {
        SigningInfo signing = info.signingInfo;
        if (signing == null) return SignerResult.UNKNOWN;
        Signature[] current = signing.getApkContentsSigners();
        Signature[] history = signing.hasMultipleSigners() ? null : signing.getSigningCertificateHistory();
        if ((current == null || current.length == 0) && (history == null || history.length == 0)) {
            return SignerResult.UNKNOWN;
        }
        if (contains(current, expectedSha256) || contains(history, expectedSha256)) return SignerResult.MATCH;
        return SignerResult.MISMATCH;
    }

    private static boolean contains(Signature[] signatures, String sha256) {
        if (signatures == null) return false;
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            for (Signature s : signatures) {
                if (toHex(md.digest(s.toByteArray())).equals(sha256)) return true;
            }
        } catch (NoSuchAlgorithmException e) {
            return false;
        }
        return false;
    }

    /**
     * Streams the APK into a PackageInstaller session and commits it. The result (including the
     * "ask the user to confirm" step) arrives at {@link InstallResultReceiver}. Blocking.
     */
    static void install(Context context, File apk) throws IOException {
        PackageInstaller installer = context.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams params =
                new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setSize(apk.length());
        int sessionId = installer.createSession(params);
        PackageInstaller.Session session = installer.openSession(sessionId);
        try {
            try (InputStream in = new FileInputStream(apk);
                 OutputStream out = session.openWrite("base.apk", 0, apk.length())) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                session.fsync(out);
            }
            Intent callback = new Intent(context, InstallResultReceiver.class)
                    .setAction(InstallResultReceiver.ACTION);
            PendingIntent pending = PendingIntent.getBroadcast(context, sessionId, callback,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);
            session.commit(pending.getIntentSender());
        } catch (IOException | RuntimeException e) {
            session.abandon();
            throw e;
        } finally {
            session.close();
        }
    }

    /** Returns installed package info, or null if the package is not installed or not visible. */
    static PackageInfo installedInfo(Context context, String packageName) {
        if (packageName == null || packageName.isEmpty()) return null;
        try {
            return context.getPackageManager().getPackageInfo(packageName, 0);
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) sb.append(String.format(Locale.ROOT, "%02x", b));
        return sb.toString();
    }
}
