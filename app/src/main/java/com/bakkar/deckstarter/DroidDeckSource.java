package com.bakkar.deckstarter;

import java.util.Locale;

/**
 * Facts about the official DroidDeck project (github.com/Droid-Deck/DroidDeck), taken from its
 * README, tools/release/variants.txt and keystore/release-signer.sha256.
 */
final class DroidDeckSource {

    static final String OFFICIAL_REPO = "Droid-Deck/DroidDeck";
    static final String DISCORD_URL = "https://discord.gg/JRGAvawjsm";

    /** SHA-256 of the certificate DroidDeck signs its releases with (keystore/release-signer.sha256). */
    static final String RELEASE_SIGNER_SHA256 =
            "b241ea7d77822d39b89d3227e06f3ecf62adec6cbfd38ad06196787991b5d3f0";

    /**
     * Every release ships the same APK under several package names, because some phones only give
     * their performance modes to apps with certain names. Each installs as a separate app.
     */
    enum Variant {
        STANDARD("Standard (recommended)", "com.droiddeck.launcher", ""),
        PUBG("Performance-mode name: PUBG", "com.tencent.ig", "-pubg"),
        ANTUTU("Performance-mode name: AnTuTu", "com.antutu.benchmark.full", "-antutu"),
        LUDASHI("Performance-mode name: Ludashi", "com.ludashi.benchmark", "-ludashi");

        final String label;
        final String packageName;
        /** File-name suffix before ".apk", e.g. DroidDeck-0.3.2-pubg.apk. */
        final String suffix;

        Variant(String label, String packageName, String suffix) {
            this.label = label;
            this.packageName = packageName;
            this.suffix = suffix;
        }

        /** True if this release asset is this variant's APK. */
        boolean matches(String assetName) {
            String n = assetName.toLowerCase(Locale.ROOT);
            if (!n.endsWith(".apk")) return false;
            if (this != STANDARD) return n.endsWith(suffix + ".apk");
            for (Variant other : values()) {
                if (other != STANDARD && n.endsWith(other.suffix + ".apk")) return false;
            }
            return true;
        }

        static Variant fromName(String name) {
            for (Variant v : values()) {
                if (v.name().equals(name)) return v;
            }
            return STANDARD;
        }
    }

    private DroidDeckSource() {}

    static boolean isOfficial(String repo) {
        return OFFICIAL_REPO.equalsIgnoreCase(repo);
    }
}
