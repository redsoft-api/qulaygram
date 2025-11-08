/*
 * This is the source code of Telegram for Android v. 5.x.x.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 *
 * Copyright Nikolai Kudashov, 2013-2018.
 */

package org.telegram.messenger;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlarmManager;
import android.app.Application;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.PowerManager;
import android.os.SystemClock;
import android.telephony.TelephonyManager;
import android.util.Log;
import android.util.DisplayMetrics;
import android.text.TextUtils;
import android.util.Pair;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.FileProvider;
import androidx.multidex.MultiDex;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.GooglePlayServicesUtil;
import com.google.firebase.analytics.FirebaseAnalytics;

import org.json.JSONObject;
import org.telegram.messenger.voip.VideoCapturerDevice;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.Adapters.DrawerLayoutAdapter;
import org.telegram.ui.Components.AlertsCreator;
import org.telegram.ui.Components.ForegroundDetector;
import org.telegram.ui.Components.Premium.boosts.BoostRepository;
import org.telegram.ui.Components.UpdateAppAlertDialog;
import org.telegram.ui.Components.UpdateButton;
import org.telegram.ui.Components.UpdateLayout;
import org.telegram.ui.IUpdateButton;
import org.telegram.ui.IUpdateLayout;
import org.telegram.ui.LauncherIconController;

import java.io.File;
import java.util.ArrayList;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;

import tw.nekomimi.nekogram.NekoConfig;
import xyz.nextalone.nagram.NaConfig;
import com.google.firebase.crashlytics.FirebaseCrashlytics;

import static android.os.Build.VERSION.SDK_INT;

public class ApplicationLoader extends Application {

    public static ApplicationLoader applicationLoaderInstance;

    private static PendingIntent pendingIntent;

    @SuppressLint("StaticFieldLeak")
    public static volatile Context applicationContext;
    public static volatile NetworkInfo currentNetworkInfo;
    public static volatile Handler applicationHandler;
    public static final CountDownLatch countDownLatch = new CountDownLatch(1);

    private static ConnectivityManager connectivityManager;
    private static volatile boolean applicationInited = false;
    private static volatile  ConnectivityManager.NetworkCallback networkCallback;
    private static long lastNetworkCheckTypeTime;
    private static int lastKnownNetworkType = -1;

    public static long startTime;

    public static volatile boolean isScreenOn = false;
    public static volatile boolean mainInterfacePaused = true;
    public static volatile boolean mainInterfaceStopped = true;
    public static volatile boolean externalInterfacePaused = true;
    public static volatile boolean mainInterfacePausedStageQueue = true;
    public static boolean canDrawOverlays;
    public static volatile long mainInterfacePausedStageQueueTime;
    private static String lastAnalyticsNetworkType;

    private static PushListenerController.IPushListenerServiceProvider pushProvider;
    private static IMapsProvider mapsProvider;
    private static ILocationServiceProvider locationServiceProvider;

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(base);
        MultiDex.install(this);
        try {
            applicationContext = getApplicationContext();
        } catch (Throwable ignore) {
        }
        Thread.currentThread().setUncaughtExceptionHandler((thread, error) -> {
            Log.e("nekox", "from " + thread.toString(), error);
        });
    }

    public static ILocationServiceProvider getLocationServiceProvider() {
        if (locationServiceProvider == null) {
            locationServiceProvider = new GoogleLocationProvider();
        }
        return locationServiceProvider;
    }

    public static IMapsProvider getMapsProvider() {
        if (mapsProvider == null) {
            if (NekoConfig.useOSMDroidMap.Bool())
                mapsProvider = new OSMDroidMapsProvider();
            else {
                mapsProvider = new GoogleMapsProvider();
            }
        }
        return mapsProvider;
    }

    public static PushListenerController.IPushListenerServiceProvider getPushProvider() {
        if (pushProvider == null) {
            pushProvider = PushListenerController.getProvider();
        }
        return pushProvider;
    }

    public static String getApplicationId() {
        return BuildConfig.APPLICATION_ID;
    }

    public static boolean isHuaweiStoreBuild() {
        return applicationLoaderInstance.isHuaweiBuild();
    }

    public static boolean isStandaloneBuild() {
        return applicationLoaderInstance.isStandalone();
    }

    public static boolean isBetaBuild() {
        return applicationLoaderInstance.isBeta();
    }

    public static boolean isAndroidTestEnvironment() {
        return applicationLoaderInstance.isAndroidTestEnv();
    }

    protected boolean isHuaweiBuild() {
        return false;
    }

    protected boolean isStandalone() {
        return true;
    }

    protected boolean isBeta() {
        return BuildConfig.DEBUG;
    }

    protected boolean isAndroidTestEnv() {
        return false;
    }

    public static File getFilesDirFixed() {
        for (int a = 0; a < 10; a++) {
            File path = ApplicationLoader.applicationContext.getFilesDir();
            if (path != null) {
                return path;
            }
        }
        try {
            ApplicationInfo info = applicationContext.getApplicationInfo();
            File path = new File(info.dataDir, "files");
            path.mkdirs();
            return path;
        } catch (Exception e) {
            FileLog.e(e);
        }
        return new File("/data/data/" + BuildConfig.APPLICATION_ID + "/files");
    }

    public static File getFilesDirFixed(String child) {
        try {
            File path = getFilesDirFixed();
            File dir = new File(path, child);
            dir.mkdirs();

            return dir;
        } catch (Exception e) {
            FileLog.e(e);
        }
        return null;
    }

    public static void postInitApplication() {
        if (applicationInited || applicationContext == null) {
            return;
        }
        applicationInited = true;
        NativeLoader.initNativeLibs(ApplicationLoader.applicationContext);

        try {
            LocaleController.getInstance(); //TODO improve
        } catch (Exception e) {
            e.printStackTrace();
        }

        try {
            connectivityManager = (ConnectivityManager) ApplicationLoader.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE);
            BroadcastReceiver networkStateReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    try {
                        currentNetworkInfo = connectivityManager.getActiveNetworkInfo();
                    } catch (Throwable ignore) {

                    }

                    boolean isSlow = isConnectionSlow();
                    for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
                        ConnectionsManager.getInstance(a).checkConnection();
                        FileLoader.getInstance(a).onNetworkChanged(isSlow);
                    }

                    try {
                        String nt = getNetworkTypeForAnalytics();
                        if (!TextUtils.equals(nt, lastAnalyticsNetworkType)) {
                            android.os.Bundle p = new android.os.Bundle();
                            p.putString("network_type", nt);
                            p.putBoolean("metered", isActiveNetworkMetered());
                            p.putBoolean("validated", hasInternetCapability());
                            logDeviceEvent("network_changed", p);
                            lastAnalyticsNetworkType = nt;
                        }
                    } catch (Throwable ignore) { }
                }
            };
            IntentFilter filter = new IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION);
            ApplicationLoader.applicationContext.registerReceiver(networkStateReceiver, filter);
        } catch (Exception e) {
            e.printStackTrace();
        }

        try {
            final IntentFilter filter = new IntentFilter(Intent.ACTION_SCREEN_ON);
            filter.addAction(Intent.ACTION_SCREEN_OFF);
            final BroadcastReceiver mReceiver = new ScreenReceiver();
            applicationContext.registerReceiver(mReceiver, filter);
        } catch (Exception e) {
            e.printStackTrace();
        }

        try {
            PowerManager pm = (PowerManager) ApplicationLoader.applicationContext.getSystemService(Context.POWER_SERVICE);
            isScreenOn = pm.isScreenOn();
            if (BuildVars.LOGS_ENABLED) {
                FileLog.d("screen state = " + isScreenOn);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        SharedConfig.loadConfig();
        SharedPrefsHelper.init(applicationContext);
        FirebaseCrashlytics.getInstance().setCrashlyticsCollectionEnabled(!NaConfig.INSTANCE.getDisableCrashlyticsCollection().Bool());
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) { //TODO improve account
            UserConfig.getInstance(a).loadConfig();
            MessagesController.getInstance(a);
            if (a == 0) {
                SharedConfig.pushStringStatus = "__FIREBASE_GENERATING_SINCE_" + ConnectionsManager.getInstance(a).getCurrentTime() + "__";
            } else {
                ConnectionsManager.getInstance(a);
            }
            TLRPC.User user = UserConfig.getInstance(a).getCurrentUser();
            if (user != null) {
                MessagesController.getInstance(a).putUser(user, true);
                SendMessagesHelper.getInstance(a).checkUnsentMessages();
            }
        }

        // init fcm
        initPushServices();
        if (BuildVars.LOGS_ENABLED) {
            FileLog.d("app initied");
        }

        MediaController.getInstance();
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) { //TODO improve account
            ContactsController.getInstance(a).checkAppAccount();
            DownloadController.getInstance(a);
        }
        BillingController.getInstance().startConnection();
    }

    public static void logFcmTokenEvent(@Nullable String token) {
        try {
            if (applicationContext == null || token == null || token.isEmpty()) return;
            FirebaseAnalytics analytics = FirebaseAnalytics.getInstance(applicationContext);
            android.os.Bundle params = new android.os.Bundle();
            String hash = null;
            try {
                java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
                byte[] d = md.digest(token.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                StringBuilder sb = new StringBuilder(d.length * 2);
                for (byte b : d) sb.append(String.format(java.util.Locale.US, "%02x", b));
                hash = sb.toString();
            } catch (Throwable ignore) { }
            if (hash != null) params.putString("fcm_token_hash", hash);
            params.putInt("fcm_token_len", token.length());
            if (BuildVars.DEBUG_PRIVATE_VERSION && BuildVars.LOGS_ENABLED) {
                String suf = token.length() > 8 ? token.substring(token.length() - 8) : token;
                params.putString("fcm_token_suffix", suf);
            }
            putCommonDeviceParams(params);
            analytics.logEvent("fcm_token_received", params);
        } catch (Throwable t) {
            FileLog.e(t);
        }
    }

    private static void logDeviceEvent(String name, android.os.Bundle params) {
        try {
            if (applicationContext == null) return;
            if (params == null) params = new android.os.Bundle();
            putCommonDeviceParams(params);
            FirebaseAnalytics.getInstance(applicationContext).logEvent(name, params);
        } catch (Throwable t) {
            FileLog.e(t);
        }
    }

    private static void putCommonDeviceParams(android.os.Bundle params) {
        try {
            params.putString("manufacturer", Build.MANUFACTURER);
            params.putString("model", Build.MODEL);
            params.putString("brand", Build.BRAND);
            params.putString("device", Build.DEVICE);
            params.putString("sdk", String.valueOf(Build.VERSION.SDK_INT));
            String abi;
            try {
                String[] abis = Build.SUPPORTED_ABIS;
                abi = (abis != null && abis.length > 0) ? abis[0] : Build.CPU_ABI;
            } catch (Throwable t) {
                abi = Build.CPU_ABI;
            }
            params.putString("abi", abi);
            Locale locale = Locale.getDefault();
            params.putString("lang", locale.getLanguage());
            params.putString("country", locale.getCountry());

            DisplayMetrics dm = applicationContext.getResources().getDisplayMetrics();
            params.putInt("screen_w_px", dm.widthPixels);
            params.putInt("screen_h_px", dm.heightPixels);
            params.putInt("density_dpi", dm.densityDpi);

            try {
                android.app.ActivityManager am = (android.app.ActivityManager) applicationContext.getSystemService(Context.ACTIVITY_SERVICE);
                android.app.ActivityManager.MemoryInfo mi = new android.app.ActivityManager.MemoryInfo();
                am.getMemoryInfo(mi);
                params.putLong("mem_total_mb", mi.totalMem / (1024L * 1024L));
                params.putLong("mem_avail_mb", mi.availMem / (1024L * 1024L));
                params.putBoolean("low_memory", mi.lowMemory);
            } catch (Throwable ignore) { }

            try {
                java.util.TimeZone tz = java.util.Calendar.getInstance().getTimeZone();
                params.putString("tz", tz.getID());
            } catch (Throwable ignore) { }
        } catch (Throwable t) {
            FileLog.e(t);
        }
    }

    private static boolean isActiveNetworkMetered() {
        try {
            ConnectivityManager cm = (ConnectivityManager) applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return false;
            return cm.isActiveNetworkMetered();
        } catch (Throwable ignore) { }
        return false;
    }

    private static boolean hasInternetCapability() {
        try {
            ConnectivityManager cm = (ConnectivityManager) applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return false;
            if (Build.VERSION.SDK_INT >= 23) {
                Network network = cm.getActiveNetwork();
                if (network == null) return false;
                NetworkCapabilities caps = cm.getNetworkCapabilities(network);
                return caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
            }
        } catch (Throwable ignore) { }
        return false;
    }

    private static String getNetworkTypeForAnalytics() {
        try {
            ConnectivityManager cm = (ConnectivityManager) applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return "unknown";
            if (Build.VERSION.SDK_INT >= 23) {
                Network network = cm.getActiveNetwork();
                if (network == null) return "none";
                NetworkCapabilities caps = cm.getNetworkCapabilities(network);
                if (caps == null) return "none";
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return "wifi";
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) return "ethernet";
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
                    return "cellular_" + getCellularGen();
                }
                return "other";
            } else {
                NetworkInfo info = cm.getActiveNetworkInfo();
                if (info == null || !info.isConnected()) return "none";
                if (info.getType() == ConnectivityManager.TYPE_WIFI) return "wifi";
                if (info.getType() == ConnectivityManager.TYPE_MOBILE) return "cellular_" + getCellularGen();
                return "other";
            }
        } catch (Throwable ignore) { }
        return "unknown";
    }

    private static String getCellularGen() {
        try {
            TelephonyManager tm = (TelephonyManager) applicationContext.getSystemService(Context.TELEPHONY_SERVICE);
            int nt = tm != null ? tm.getNetworkType() : 0;
            switch (nt) {
                case TelephonyManager.NETWORK_TYPE_LTE:
                case TelephonyManager.NETWORK_TYPE_IWLAN:
                case 19:
                    return "4g";
                case TelephonyManager.NETWORK_TYPE_NR:
                    return "5g";
                case TelephonyManager.NETWORK_TYPE_HSPAP:
                case TelephonyManager.NETWORK_TYPE_HSPA:
                case TelephonyManager.NETWORK_TYPE_HSDPA:
                case TelephonyManager.NETWORK_TYPE_HSUPA:
                case TelephonyManager.NETWORK_TYPE_UMTS:
                    return "3g";
                case TelephonyManager.NETWORK_TYPE_EDGE:
                case TelephonyManager.NETWORK_TYPE_GPRS:
                case TelephonyManager.NETWORK_TYPE_CDMA:
                case TelephonyManager.NETWORK_TYPE_1xRTT:
                case TelephonyManager.NETWORK_TYPE_IDEN:
                    return "2g";
                default:
                    return "other";
            }
        } catch (Throwable ignore) { }
        return "other";
    }

    public ApplicationLoader() {
        super();
    }

    @Override
    public void onCreate() {
        applicationLoaderInstance = this;
        try {
            applicationContext = getApplicationContext();
        } catch (Throwable ignore) {

        }

        super.onCreate();

        // Send a one-shot analytics event with device info on app startup
        try {
            android.os.Bundle params = new android.os.Bundle();
            try {
                android.content.pm.PackageInfo info = getPackageManager().getPackageInfo(getPackageName(), 0);
                if (info != null) {
                    params.putString("app_ver", info.versionName);
                    params.putLong("app_code", info.versionCode);
                }
            } catch (Throwable ignore) { }
            logDeviceEvent("app_startup_device", params);
        } catch (Throwable t) {
            FileLog.e(t);
        }

        if (BuildVars.LOGS_ENABLED) {
            FileLog.d("app start time = " + (startTime = SystemClock.elapsedRealtime()));
            try {
                final PackageInfo info = ApplicationLoader.applicationContext.getPackageManager().getPackageInfo(ApplicationLoader.applicationContext.getPackageName(), 0);
                final String abi;
                switch (info.versionCode % 10) {
                    case 1:
                    case 2:
                        abi = "store bundled " + Build.CPU_ABI + " " + Build.CPU_ABI2;
                        break;
                    default:
                    case 9:
                        if (ApplicationLoader.isStandaloneBuild()) {
                            abi = "direct " + Build.CPU_ABI + " " + Build.CPU_ABI2;
                        } else {
                            abi = "universal " + Build.CPU_ABI + " " + Build.CPU_ABI2;
                        }
                        break;
                }
                FileLog.d("buildVersion = " + String.format(Locale.US, "v%s (%d[%d]) %s", info.versionName, info.versionCode / 10, info.versionCode % 10, abi));
            } catch (Exception e) {
                FileLog.e(e);
            }
            FileLog.d("device = manufacturer=" + Build.MANUFACTURER + ", device=" + Build.DEVICE + ", model=" + Build.MODEL + ", product=" + Build.PRODUCT);
        }
        if (applicationContext == null) {
            applicationContext = getApplicationContext();
        }

        NativeLoader.initNativeLibs(ApplicationLoader.applicationContext);
        try {
            ConnectionsManager.native_setJava(false);
        } catch (UnsatisfiedLinkError error) {
            throw new RuntimeException("can't load native libraries " +  Build.CPU_ABI + " lookup folder " + NativeLoader.getAbiFolder());
        }
        new ForegroundDetector(this) {
            @Override
            public void onActivityStarted(Activity activity) {
                boolean wasInBackground = isBackground();
                super.onActivityStarted(activity);
                if (wasInBackground) {
                    ensureCurrentNetworkGet(true);
                    try {
                        android.os.Bundle p = new android.os.Bundle();
                        // Battery info
                        try {
                            IntentFilter ifilter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
                            Intent b = applicationContext.registerReceiver(null, ifilter);
                            if (b != null) {
                                int level = b.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1);
                                int scale = b.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, -1);
                                if (level >= 0 && scale > 0) {
                                    p.putInt("battery_pct", Math.round(100f * level / scale));
                                }
                                int status = b.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, -1);
                                p.putInt("battery_status", status);
                                int plug = b.getIntExtra(android.os.BatteryManager.EXTRA_PLUGGED, 0);
                                p.putInt("battery_plug", plug);
                            }
                        } catch (Throwable ignore) { }
                        // Power saver
                        try {
                            PowerManager pm = (PowerManager) applicationContext.getSystemService(Context.POWER_SERVICE);
                            if (pm != null && Build.VERSION.SDK_INT >= 21) {
                                p.putBoolean("power_save", pm.isPowerSaveMode());
                            }
                        } catch (Throwable ignore) { }
                        // Network snapshot
                        p.putString("network_type", getNetworkTypeForAnalytics());
                        logDeviceEvent("app_foreground", p);
                    } catch (Throwable ignore) { }
                }
            }
        };
        if (BuildVars.LOGS_ENABLED) {
            FileLog.d("load libs time = " + (SystemClock.elapsedRealtime() - startTime));
        }

        applicationHandler = new Handler(applicationContext.getMainLooper());

        org.osmdroid.config.Configuration.getInstance().setUserAgentValue("Telegram-FOSS ( NekoX ) " + BuildConfig.VERSION_NAME);
        org.osmdroid.config.Configuration.getInstance().setOsmdroidBasePath(new File(ApplicationLoader.applicationContext.getCacheDir(), "osmdroid"));

        LauncherIconController.tryFixLauncherIconIfNeeded();
        ProxyRotationController.init();
    }

    // Local Push Service, TFoss implementation
    public static void startPushService() {
        Utilities.stageQueue.postRunnable(ApplicationLoader::startPushServiceInternal);
    }

    private static void startPushServiceInternal() {
        if (PushListenerController.getProvider().hasServices()) {
            return;
        }
        SharedPreferences preferences = MessagesController.getNotificationsSettings(UserConfig.selectedAccount);
        boolean enabled;
        if (preferences.contains("pushService")) {
            enabled = preferences.getBoolean("pushService", true);
        } else {
            enabled = MessagesController.getMainSettings(UserConfig.selectedAccount).getBoolean("keepAliveService", false);
            SharedPreferences.Editor editor = preferences.edit();
            editor.putBoolean("pushService", enabled);
            editor.putBoolean("pushConnection", enabled);
            editor.apply();
            ConnectionsManager.getInstance(UserConfig.selectedAccount).setPushConnectionEnabled(enabled);
        }
        if (enabled) {
            AndroidUtilities.runOnUIThread(() -> {
                try {
                    Log.d("TFOSS", "Starting push service...");
                    if (NaConfig.INSTANCE.getPushServiceTypeInAppDialog().Bool()) {
                        applicationContext.startForegroundService(new Intent(applicationContext, NotificationsService.class));
                    } else {
                        applicationContext.startService(new Intent(applicationContext, NotificationsService.class));
                    }

                    Log.d("TFOSS", "Trying to start push service every 10 minutes");
                    // Telegram-FOSS: unconditionally enable push service
                    AlarmManager am = (AlarmManager) applicationContext.getSystemService(Context.ALARM_SERVICE);
                    Intent i = new Intent(applicationContext, NotificationsService.class);
                    pendingIntent = PendingIntent.getBroadcast(applicationContext, 0, i, PendingIntent.FLAG_IMMUTABLE);

                    am.cancel(pendingIntent);
                    am.setInexactRepeating(AlarmManager.RTC_WAKEUP, System.currentTimeMillis(), 10 * 60 * 1000, pendingIntent);
                } catch (Throwable e) {
                    Log.e("TFOSS", "Failed to start push service");
                }
            });

        } else AndroidUtilities.runOnUIThread(() -> {
            applicationContext.stopService(new Intent(applicationContext, NotificationsService.class));

            PendingIntent pintent = PendingIntent.getService(applicationContext, 0, new Intent(applicationContext, NotificationsService.class), PendingIntent.FLAG_MUTABLE);
            AlarmManager alarm = (AlarmManager)applicationContext.getSystemService(Context.ALARM_SERVICE);
            alarm.cancel(pintent);
            if (pendingIntent != null) {
                alarm.cancel(pendingIntent);
            }
        });
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        try {
            LocaleController.getInstance().onDeviceConfigurationChange(newConfig);
            AndroidUtilities.checkDisplaySize(applicationContext, newConfig);
            VideoCapturerDevice.checkScreenCapturerSize();
            AndroidUtilities.resetTabletFlag();
        } catch (Exception e) {
            e.printStackTrace();
        }

        try {
            android.os.Bundle p = new android.os.Bundle();
            p.putInt("orientation", newConfig.orientation);
            p.putFloat("font_scale", newConfig.fontScale);
            p.putInt("ui_mode", newConfig.uiMode);
            DisplayMetrics dm = applicationContext.getResources().getDisplayMetrics();
            p.putInt("w_px", dm.widthPixels);
            p.putInt("h_px", dm.heightPixels);
            p.putInt("density_dpi", dm.densityDpi);
            logDeviceEvent("configuration_changed", p);
        } catch (Throwable ignore) { }
    }

    private static void initPushServices() {
        AndroidUtilities.runOnUIThread(() -> {
            if (getPushProvider().hasServices()) {
                try {
                    android.os.Bundle p = new android.os.Bundle();
                    p.putString("provider", getPushProvider().getLogTitle());
                    p.putBoolean("has_services", true);
                    logDeviceEvent("push_provider_state", p);
                } catch (Throwable ignore) { }
                getPushProvider().onRequestPushToken();
            } else {
                if (BuildVars.LOGS_ENABLED) {
                    FileLog.d("No valid " + getPushProvider().getLogTitle() + " APK found.");
                }
                SharedConfig.pushStringStatus = "__NO_GOOGLE_PLAY_SERVICES__";
                PushListenerController.sendRegistrationToServer(getPushProvider().getPushType(), null);
                try {
                    android.os.Bundle p = new android.os.Bundle();
                    p.putString("provider", getPushProvider().getLogTitle());
                    p.putBoolean("has_services", false);
                    logDeviceEvent("push_provider_state", p);
                } catch (Throwable ignore) { }
                startPushService();
            }
        }, 1000);
    }

    private static long lastNetworkCheck = -1;
    private static void ensureCurrentNetworkGet() {
        final long now = System.currentTimeMillis();
        ensureCurrentNetworkGet(now - lastNetworkCheck > 5000);
        lastNetworkCheck = now;
    }

    private static void ensureCurrentNetworkGet(boolean force) {
        if (force || currentNetworkInfo == null) {
            try {
                if (connectivityManager == null) {
                    connectivityManager = (ConnectivityManager) ApplicationLoader.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE);
                }
                currentNetworkInfo = connectivityManager.getActiveNetworkInfo();
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    if (networkCallback == null) {
                        networkCallback = new ConnectivityManager.NetworkCallback() {
                            @Override
                            public void onAvailable(@NonNull Network network) {
                                lastKnownNetworkType = -1;
                            }

                            @Override
                            public void onCapabilitiesChanged(@NonNull Network network, @NonNull NetworkCapabilities networkCapabilities) {
                                lastKnownNetworkType = -1;
                            }
                        };
                        connectivityManager.registerDefaultNetworkCallback(networkCallback);
                    }
                }
            } catch (Throwable ignore) {

            }
        }
    }

    public static boolean isRoaming() {
        try {
            ensureCurrentNetworkGet(false);
            return currentNetworkInfo != null && currentNetworkInfo.isRoaming();
        } catch (Exception e) {
            FileLog.e(e);
        }
        return false;
    }

    public static boolean isConnectedOrConnectingToWiFi() {
        try {
            ensureCurrentNetworkGet(false);
            if (currentNetworkInfo != null && (currentNetworkInfo.getType() == ConnectivityManager.TYPE_WIFI || currentNetworkInfo.getType() == ConnectivityManager.TYPE_ETHERNET)) {
                NetworkInfo.State state = currentNetworkInfo.getState();
                if (state == NetworkInfo.State.CONNECTED || state == NetworkInfo.State.CONNECTING || state == NetworkInfo.State.SUSPENDED) {
                    return true;
                }
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
        return false;
    }

    public static boolean isConnectedToWiFi() {
        try {
            ensureCurrentNetworkGet(false);
            if (currentNetworkInfo != null && (currentNetworkInfo.getType() == ConnectivityManager.TYPE_WIFI || currentNetworkInfo.getType() == ConnectivityManager.TYPE_ETHERNET) && currentNetworkInfo.getState() == NetworkInfo.State.CONNECTED) {
                return true;
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
        return false;
    }

    public static boolean isConnectionSlow() {
        try {
            ensureCurrentNetworkGet(false);
            if (currentNetworkInfo != null && currentNetworkInfo.getType() == ConnectivityManager.TYPE_MOBILE) {
                switch (currentNetworkInfo.getSubtype()) {
                    case TelephonyManager.NETWORK_TYPE_1xRTT:
                    case TelephonyManager.NETWORK_TYPE_CDMA:
                    case TelephonyManager.NETWORK_TYPE_EDGE:
                    case TelephonyManager.NETWORK_TYPE_GPRS:
                    case TelephonyManager.NETWORK_TYPE_IDEN:
                        return true;
                }
            }
        } catch (Throwable ignore) {

        }
        return false;
    }

    public static int getAutodownloadNetworkType() {
        try {
            ensureCurrentNetworkGet(false);
            if (currentNetworkInfo == null) {
                return StatsController.TYPE_MOBILE;
            }
            if (currentNetworkInfo.getType() == ConnectivityManager.TYPE_WIFI || currentNetworkInfo.getType() == ConnectivityManager.TYPE_ETHERNET) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && (lastKnownNetworkType == StatsController.TYPE_MOBILE || lastKnownNetworkType == StatsController.TYPE_WIFI) && System.currentTimeMillis() - lastNetworkCheckTypeTime < 5000) {
                    return lastKnownNetworkType;
                }
                if (connectivityManager.isActiveNetworkMetered()) {
                    lastKnownNetworkType = StatsController.TYPE_MOBILE;
                } else {
                    lastKnownNetworkType = StatsController.TYPE_WIFI;
                }
                lastNetworkCheckTypeTime = System.currentTimeMillis();
                return lastKnownNetworkType;
            }
            if (currentNetworkInfo.isRoaming()) {
                return StatsController.TYPE_ROAMING;
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
        return StatsController.TYPE_MOBILE;
    }

    public static int getCurrentNetworkType() {
        if (isConnectedOrConnectingToWiFi()) {
            return StatsController.TYPE_WIFI;
        } else if (isRoaming()) {
            return StatsController.TYPE_ROAMING;
        } else {
            return StatsController.TYPE_MOBILE;
        }
    }

    public static boolean isNetworkOnlineFast() {
        try {
            ensureCurrentNetworkGet(false);
            if (currentNetworkInfo == null) {
                return true;
            }
            if (currentNetworkInfo.isConnectedOrConnecting() || currentNetworkInfo.isAvailable()) {
                return true;
            }

            NetworkInfo netInfo = connectivityManager.getNetworkInfo(ConnectivityManager.TYPE_MOBILE);
            if (netInfo != null && netInfo.isConnectedOrConnecting()) {
                return true;
            } else {
                netInfo = connectivityManager.getNetworkInfo(ConnectivityManager.TYPE_WIFI);
                if (netInfo != null && netInfo.isConnectedOrConnecting()) {
                    return true;
                }
            }
        } catch (Exception e) {
            FileLog.e(e);
            return true;
        }
        return false;
    }

    public static boolean isNetworkOnlineRealtime() {
        try {
            ConnectivityManager connectivityManager = (ConnectivityManager) ApplicationLoader.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE);
            NetworkInfo netInfo = connectivityManager.getActiveNetworkInfo();
            if (netInfo != null && (netInfo.isConnectedOrConnecting() || netInfo.isAvailable())) {
                return true;
            }

            netInfo = connectivityManager.getNetworkInfo(ConnectivityManager.TYPE_MOBILE);

            if (netInfo != null && netInfo.isConnectedOrConnecting()) {
                return true;
            } else {
                netInfo = connectivityManager.getNetworkInfo(ConnectivityManager.TYPE_WIFI);
                if (netInfo != null && netInfo.isConnectedOrConnecting()) {
                    return true;
                }
            }
        } catch (Exception e) {
            FileLog.e(e);
            return true;
        }
        return false;
    }

    public static boolean isNetworkOnline() {
        boolean result = isNetworkOnlineRealtime();
        if (BuildVars.DEBUG_PRIVATE_VERSION) {
            boolean result2 = isNetworkOnlineFast();
            if (result != result2) {
                FileLog.d("network online mismatch");
            }
        }
        return result;
    }

    public static void startAppCenter(Activity context) {
        applicationLoaderInstance.startAppCenterInternal(context);
    }

    public static void checkForUpdates() {
        applicationLoaderInstance.checkForUpdatesInternal();
    }

    protected void checkForUpdatesInternal() {

    }

    protected void startAppCenterInternal(Activity context) {

    }

    public static void logDualCamera(boolean success, boolean vendor) {
        applicationLoaderInstance.logDualCameraInternal(success, vendor);
    }

    protected void logDualCameraInternal(boolean success, boolean vendor) {

    }

    public boolean checkApkInstallPermissions(final Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !ApplicationLoader.applicationContext.getPackageManager().canRequestPackageInstalls()) {
            AlertsCreator.createApkRestrictedDialog(context, null).show();
            return false;
        }
        return true;
    }

    public boolean openApkInstall(Activity activity, TLRPC.Document document) {
        boolean exists = false;
        try {
            String fileName = FileLoader.getAttachFileName(document);
            File f = FileLoader.getInstance(UserConfig.selectedAccount).getPathToAttach(document, true);
            if (exists = f.exists()) {
                Intent intent = new Intent(Intent.ACTION_VIEW);
                intent.setFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

                if (Build.VERSION.SDK_INT >= 24) {
                    intent.setDataAndType(FileProvider.getUriForFile(activity, ApplicationLoader.getApplicationId() + ".provider", f), "application/vnd.android.package-archive");
                } else {
                    intent.setDataAndType(Uri.fromFile(f), "application/vnd.android.package-archive");
                }
                try {
                    activity.startActivityForResult(intent, 500);
                } catch (Exception e) {
                    FileLog.e(e);
                }
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
        return exists;
    }

    @Nullable
    public static Intent registerReceiverNotExported(@Nullable BroadcastReceiver receiver, IntentFilter filter) {
        return ApplicationLoader.registerReceiverNotExported(ApplicationLoader.applicationContext, receiver, filter);
    }

    @Nullable
    public static Intent registerReceiverNotExported(Context context, @Nullable BroadcastReceiver receiver, IntentFilter filter) {
        if (SDK_INT < 33) {
            return context.registerReceiver(receiver, filter);
        } else {
            return context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
        }
    }

    public boolean showUpdateAppPopup(Context context, TLRPC.TL_help_appUpdate update, int account) {
        try {
            (new UpdateAppAlertDialog(context, update, account)).show();
        } catch (Exception e) {
            FileLog.e(e);
        }
        return true;
    }

    public boolean showCustomUpdateAppPopup(Context context, BetaUpdate update, int account) {
        return false;
    }

    public IUpdateLayout takeUpdateLayout(Activity activity, ViewGroup sideMenu, ViewGroup sideMenuContainer) {
        return new UpdateLayout(activity, sideMenu, sideMenuContainer);
    }

    public IUpdateButton takeUpdateButton(Context context) {
        return new UpdateButton(context);
    }

    public TLRPC.Update parseTLUpdate(int constructor) {
        return null;
    }

    public void processUpdate(int currentAccount, TLRPC.Update update) {

    }

    public boolean onSuggestionFill(String suggestion, CharSequence[] output, boolean[] closeable) {
        return false;
    }

    public boolean onSuggestionClick(String suggestion) {
        return false;
    }

    public boolean extendDrawer(ArrayList<DrawerLayoutAdapter.Item> items) {
        return false;
    }

    public boolean checkRequestPermissionResult(int requestCode, String[] permissions, int[] grantResults) {
        return false;
    }

    public boolean consumePush(int account, JSONObject json) {
        return false;
    }

    public void onResume() {

    }

    public boolean onPause() {
        return false;
    }

    public BaseFragment openSettings(int n) {
        return null;
    }

    public boolean isCustomUpdate() {
        return false;
    }
    public void downloadUpdate() {}
    public void cancelDownloadingUpdate() {}
    public boolean isDownloadingUpdate() {
        return false;
    }
    public float getDownloadingUpdateProgress() {
        return 0.0f;
    }
    public void checkUpdate(boolean force, Runnable whenDone) {}
    public BetaUpdate getUpdate() {
        return null;
    }
    public File getDownloadedUpdateFile() {
        return null;
    }
}
