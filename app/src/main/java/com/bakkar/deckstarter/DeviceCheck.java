package com.bakkar.deckstarter;

import android.app.ActivityManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import android.opengl.GLES20;
import android.os.Build;
import android.os.Environment;
import android.os.StatFs;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Checks the device against DroidDeck's published requirements. */
final class DeviceCheck {

    enum Level { OK, WARN, FAIL }

    static final class Item {
        final Level level;
        final String label;
        final String detail;

        Item(Level level, String label, String detail) {
            this.level = level;
            this.label = label;
            this.detail = detail;
        }
    }

    /** Lowest Adreno GPU that DroidDeck's requirements list. */
    static final int MIN_ADRENO = 730;
    static final int MIN_SDK = Build.VERSION_CODES.P;
    /** Rule of thumb for Steam, Proton and a few games; not an official figure. */
    static final long RECOMMENDED_FREE_BYTES = 16L * 1024 * 1024 * 1024;
    static final long RECOMMENDED_RAM_BYTES = 8L * 1024 * 1024 * 1024;

    private static final Pattern ADRENO = Pattern.compile("Adreno\\D*(\\d{3,4})", Pattern.CASE_INSENSITIVE);
    /** Vulkan 1.1, the floor for DXVK/VKD3D under Proton. */
    private static final int VULKAN_1_1 = 0x401000;

    private DeviceCheck() {}

    /** Runs every check. Creates a GL context, so call it off the main thread. */
    static List<Item> run(Context context) {
        List<Item> items = new ArrayList<>();
        items.add(checkAndroid());
        items.add(checkAbi());
        items.add(checkGpu());
        items.add(checkVulkan(context));
        items.add(checkRam(context));
        items.add(checkStorage());
        return items;
    }

    static Level worst(List<Item> items) {
        Level worst = Level.OK;
        for (Item item : items) {
            if (item.level.ordinal() > worst.ordinal()) worst = item.level;
        }
        return worst;
    }

    private static Item checkAndroid() {
        String detail = "Android " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")";
        return new Item(Build.VERSION.SDK_INT >= MIN_SDK ? Level.OK : Level.FAIL, "Android 9 or newer", detail);
    }

    private static Item checkAbi() {
        boolean arm64 = Arrays.asList(Build.SUPPORTED_64_BIT_ABIS).contains("arm64-v8a");
        String detail = String.join(", ", Build.SUPPORTED_ABIS);
        return new Item(arm64 ? Level.OK : Level.FAIL, "64-bit ARM CPU", detail);
    }

    private static Item checkGpu() {
        String renderer = queryGlRenderer();
        String soc = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                ? Build.SOC_MANUFACTURER + " " + Build.SOC_MODEL : Build.HARDWARE;
        if (renderer == null) {
            return new Item(Level.WARN, "Qualcomm Adreno " + MIN_ADRENO + "+ GPU",
                    "Could not read the GPU name (chip: " + soc + ")");
        }
        String detail = renderer + " (chip: " + soc + ")";
        int model = adrenoModel(renderer);
        if (model < 0) {
            return new Item(Level.FAIL, "Qualcomm Adreno " + MIN_ADRENO + "+ GPU",
                    detail + ". DroidDeck only supports Adreno GPUs.");
        }
        if (model < MIN_ADRENO) {
            return new Item(Level.WARN, "Qualcomm Adreno " + MIN_ADRENO + "+ GPU",
                    detail + ". Below the reported minimum; DroidDeck may not run.");
        }
        return new Item(Level.OK, "Qualcomm Adreno " + MIN_ADRENO + "+ GPU", detail);
    }

    /** Returns the Adreno model number, or -1 if the renderer is not an Adreno GPU. */
    static int adrenoModel(String renderer) {
        Matcher m = ADRENO.matcher(renderer);
        return m.find() ? Integer.parseInt(m.group(1)) : -1;
    }

    private static Item checkVulkan(Context context) {
        boolean vk11 = context.getPackageManager()
                .hasSystemFeature(PackageManager.FEATURE_VULKAN_HARDWARE_VERSION, VULKAN_1_1);
        return new Item(vk11 ? Level.OK : Level.FAIL, "Vulkan 1.1 or newer",
                vk11 ? "Supported" : "Not reported by the system");
    }

    private static Item checkRam(Context context) {
        ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo info = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(info);
        // totalMem excludes memory reserved by the kernel, so allow some slack below 8 GB.
        boolean enough = info.totalMem >= RECOMMENDED_RAM_BYTES * 85 / 100;
        return new Item(enough ? Level.OK : Level.WARN, "8 GB RAM recommended", formatBytes(info.totalMem));
    }

    private static Item checkStorage() {
        StatFs stat = new StatFs(Environment.getDataDirectory().getPath());
        long free = stat.getAvailableBytes();
        return new Item(free >= RECOMMENDED_FREE_BYTES ? Level.OK : Level.WARN,
                "16 GB free storage recommended", formatBytes(free) + " free");
    }

    /** Creates a throwaway 1x1 GLES context to read GL_RENDERER (e.g. "Adreno (TM) 740"). */
    private static String queryGlRenderer() {
        EGLDisplay display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        if (display == EGL14.EGL_NO_DISPLAY) return null;
        int[] version = new int[2];
        if (!EGL14.eglInitialize(display, version, 0, version, 1)) return null;
        EGLContext ctx = EGL14.EGL_NO_CONTEXT;
        EGLSurface surface = EGL14.EGL_NO_SURFACE;
        try {
            int[] configAttrs = {
                    EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                    EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                    EGL14.EGL_NONE
            };
            EGLConfig[] configs = new EGLConfig[1];
            int[] count = new int[1];
            if (!EGL14.eglChooseConfig(display, configAttrs, 0, configs, 0, 1, count, 0) || count[0] == 0) {
                return null;
            }
            ctx = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT,
                    new int[]{EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE}, 0);
            surface = EGL14.eglCreatePbufferSurface(display, configs[0],
                    new int[]{EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE}, 0);
            if (ctx == EGL14.EGL_NO_CONTEXT || surface == EGL14.EGL_NO_SURFACE) return null;
            if (!EGL14.eglMakeCurrent(display, surface, surface, ctx)) return null;
            return GLES20.glGetString(GLES20.GL_RENDERER);
        } finally {
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
            if (surface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, surface);
            if (ctx != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, ctx);
            EGL14.eglTerminate(display);
        }
    }

    static String formatBytes(long bytes) {
        if (bytes < 1024 * 1024) return (bytes / 1024) + " KB";
        if (bytes < 1024L * 1024 * 1024) return String.format("%.0f MB", bytes / (1024.0 * 1024));
        return String.format("%.1f GB", bytes / (1024.0 * 1024 * 1024));
    }
}
