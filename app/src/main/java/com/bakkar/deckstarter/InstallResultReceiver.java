package com.bakkar.deckstarter;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.os.Build;

/**
 * Receives PackageInstaller session results. Not exported, so only the system (via our own
 * PendingIntent) can deliver to it.
 */
public final class InstallResultReceiver extends BroadcastReceiver {

    static final String ACTION = "com.bakkar.deckstarter.INSTALL_RESULT";

    interface Listener {
        void onInstallResult(boolean success, String message);
    }

    /** Set by the visible MainActivity; null when it is not showing. */
    static volatile Listener listener;

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!ACTION.equals(intent.getAction())) return;
        int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            Intent confirm = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                    ? intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent.class)
                    : legacyIntentExtra(intent);
            if (confirm != null) {
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(confirm);
            }
            return;
        }
        Listener l = listener;
        if (l == null) return;
        if (status == PackageInstaller.STATUS_SUCCESS) {
            l.onInstallResult(true, "DroidDeck installed");
        } else {
            String msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
            l.onInstallResult(false, describe(status, msg));
        }
    }

    @SuppressWarnings("deprecation")
    private static Intent legacyIntentExtra(Intent intent) {
        return intent.getParcelableExtra(Intent.EXTRA_INTENT);
    }

    private static String describe(int status, String msg) {
        switch (status) {
            case PackageInstaller.STATUS_FAILURE_ABORTED:
                return "Install cancelled";
            case PackageInstaller.STATUS_FAILURE_CONFLICT:
                return "Install blocked by a conflicting app. If DroidDeck was installed from another "
                        + "source with a different signature, uninstall it first.";
            case PackageInstaller.STATUS_FAILURE_INCOMPATIBLE:
                return "This DroidDeck build is not compatible with this device";
            case PackageInstaller.STATUS_FAILURE_STORAGE:
                return "Not enough storage to install DroidDeck";
            case PackageInstaller.STATUS_FAILURE_BLOCKED:
                return "Install blocked by the system or a device policy";
            default:
                return "Install failed" + (msg != null ? ": " + msg : "");
        }
    }
}
