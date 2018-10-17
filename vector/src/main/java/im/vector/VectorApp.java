/*
 * Copyright 2014 OpenMarket Ltd
 * Copyright 2017 Vector Creations Ltd
 * Copyright 2018 New Vector Ltd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.neocrypto.chat;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.preference.PreferenceManager;
import android.support.multidex.MultiDex;
import android.support.multidex.MultiDexApplication;
import android.text.TextUtils;
import android.util.Pair;

import com.facebook.stetho.Stetho;

import org.matrix.androidsdk.MXSession;
import org.matrix.androidsdk.util.Log;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.Timer;
import java.util.TimerTask;

import io.neocrypto.chat.activity.CommonActivityUtils;
import io.neocrypto.chat.activity.JitsiCallActivity;
import io.neocrypto.chat.activity.VectorCallViewActivity;
import io.neocrypto.chat.activity.VectorMediasPickerActivity;
import io.neocrypto.chat.activity.WidgetActivity;
import io.neocrypto.chat.analytics.Analytics;
import io.neocrypto.chat.analytics.AppAnalytics;
import io.neocrypto.chat.analytics.PiwikAnalytics;
import io.neocrypto.chat.analytics.e2e.DecryptionFailureTracker;
import io.neocrypto.chat.contacts.ContactsManager;
import io.neocrypto.chat.contacts.PIDsRetriever;
import io.neocrypto.chat.gcm.GcmRegistrationManager;
import io.neocrypto.chat.services.EventStreamService;
import io.neocrypto.chat.settings.FontScale;
import io.neocrypto.chat.util.CallsManager;
import io.neocrypto.chat.util.PermissionsToolsKt;
import io.neocrypto.chat.util.PhoneNumberUtils;
import io.neocrypto.chat.util.PreferencesManager;
import io.neocrypto.chat.util.RageShake;
import io.neocrypto.chat.util.ThemeUtils;
import io.neocrypto.chat.util.VectorMarkdownParser;

/**
 * The main application injection point
 */
public class VectorApp extends MultiDexApplication {
    private static final String LOG_TAG = VectorApp.class.getSimpleName();

    // key to save the crash status
    private static final String PREFS_CRASH_KEY = "PREFS_CRASH_KEY";

    /**
     * The current instance.
     */
    private static VectorApp instance = null;

    /**
     * Rage shake detection to send a bug report.
     */
    private RageShake mRageShake;

    /**
     * Delay to detect if the application is in background.
     * If there is no active activity during the elapsed time, it means that the application is in background.
     */
    private static final long MAX_ACTIVITY_TRANSITION_TIME_MS = 4000;

    /**
     * The current active activity
     */
    private static Activity mCurrentActivity = null;

    /**
     * Background application detection
     */
    private Timer mActivityTransitionTimer;
    private TimerTask mActivityTransitionTimerTask;
    private boolean mIsInBackground = true;

    /**
     * Google analytics information.
     */
    private static String VECTOR_VERSION_STRING = "";
    private static String SDK_VERSION_STRING = "";

    /**
     * Tells if there a pending call whereas the application is backgrounded.
     */
    private boolean mIsCallingInBackground = false;

    /**
     * Monitor the created activities to detect memory leaks.
     */
    private final List<String> mCreatedActivities = new ArrayList<>();

    /**
     * Markdown parser
     */
    private VectorMarkdownParser mMarkdownParser;

    /**
     * Calls manager
     */
    private CallsManager mCallsManager;

    private Analytics mAppAnalytics;
    private DecryptionFailureTracker mDecryptionFailureTracker;

    /**
     * @return the current instance
     */
    public static VectorApp getInstance() {
        return instance;
    }

    /**
     * The directory in which the logs are stored
     */
    public static File mLogsDirectoryFile = null;

    /**
     * The last time that removeMediasBefore has been called.
     */
    private long mLastMediasCheck = 0;

    private final BroadcastReceiver mLanguageReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!TextUtils.equals(Locale.getDefault().toString(), getApplicationLocale().toString())) {
                Log.d(LOG_TAG, "## onReceive() : the locale has been updated to " + Locale.getDefault().toString()
                        + ", restore the expected value " + getApplicationLocale().toString());
                updateApplicationSettings(getApplicationLocale(),
                        FontScale.INSTANCE.getFontScalePrefValue(),
                        ThemeUtils.INSTANCE.getApplicationTheme(context));

                if (null != getCurrentActivity()) {
                    restartActivity(getCurrentActivity());
                }
            }
        }
    };

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(base);
        MultiDex.install(this);
    }

    @Override
    public void onCreate() {
        Log.d(LOG_TAG, "onCreate");
        super.onCreate();

        if (BuildConfig.DEBUG) {
            Stetho.initializeWithDefaults(this);
        }

        // init the REST client
        MXSession.initUserAgent(this);

        instance = this;
        mCallsManager = new CallsManager(this);
        mAppAnalytics = new AppAnalytics(this, new PiwikAnalytics(this));
        mDecryptionFailureTracker = new DecryptionFailureTracker(mAppAnalytics);

        mActivityTransitionTimer = null;
        mActivityTransitionTimerTask = null;

        VECTOR_VERSION_STRING = Matrix.getInstance(this).getVersion(true, true);
        // not the first launch
        if (null != Matrix.getInstance(this).getDefaultSession()) {
            SDK_VERSION_STRING = Matrix.getInstance(this).getDefaultSession().getVersion(true);
        } else {
            SDK_VERSION_STRING = "";
        }
        mLogsDirectoryFile = new File(getCacheDir().getAbsolutePath() + "/logs");

        org.matrix.androidsdk.util.Log.setLogDirectory(mLogsDirectoryFile);
        org.matrix.androidsdk.util.Log.init("RiotLog");

        // log the application version to trace update
        // useful to track backward compatibility issues

        Log.d(LOG_TAG, "----------------------------------------------------------------");
        Log.d(LOG_TAG, "----------------------------------------------------------------");
        Log.d(LOG_TAG, " Application version: " + VECTOR_VERSION_STRING);
        Log.d(LOG_TAG, " SDK version: " + SDK_VERSION_STRING);
        Log.d(LOG_TAG, " Local time: " + (new SimpleDateFormat("MM-dd HH:mm:ss.SSSZ", Locale.US)).format(new Date()));
        Log.d(LOG_TAG, "----------------------------------------------------------------");
        Log.d(LOG_TAG, "----------------------------------------------------------------\n\n\n\n");

        mRageShake = new RageShake(this);

        registerActivityLifecycleCallbacks(new ActivityLifecycleCallbacks() {
            final Map<String, String> mLocalesByActivity = new HashMap<>();

            @Override
            public void onActivityCreated(Activity activity, Bundle savedInstanceState) {
                Log.d(LOG_TAG, "onActivityCreated " + activity);
                mCreatedActivities.add(activity.toString());
                // piwik
                onNewScreen(activity);
            }

            @Override
            public void onActivityStarted(Activity activity) {
                Log.d(LOG_TAG, "onActivityStarted " + activity);
            }

            /**
             * Compute the locale status value
             * @param activity the activity
             * @return the local status value
             */
            private String getActivityLocaleStatus(Activity activity) {
                return getApplicationLocale().toString()
                        + "_" + FontScale.INSTANCE.getFontScalePrefValue()
                        + "_" + ThemeUtils.INSTANCE.getApplicationTheme(activity);
            }

            @Override
            public void onActivityResumed(final Activity activity) {
                Log.d(LOG_TAG, "onActivityResumed " + activity);
                setCurrentActivity(activity);

                String activityKey = activity.toString();

                if (mLocalesByActivity.containsKey(activityKey)) {
                    String prevActivityLocale = mLocalesByActivity.get(activityKey);

                    if (!TextUtils.equals(prevActivityLocale, getActivityLocaleStatus(activity))) {
                        Log.d(LOG_TAG, "## onActivityResumed() : restart the activity " + activity
                                + " because of the locale update from " + prevActivityLocale + " to " + getActivityLocaleStatus(activity));
                        restartActivity(activity);
                        return;
                    }
                }

                // it should never happen as there is a broadcast receiver (mLanguageReceiver)
                if (!TextUtils.equals(Locale.getDefault().toString(), getApplicationLocale().toString())) {
                    Log.d(LOG_TAG, "## onActivityResumed() : the locale has been updated to " + Locale.getDefault().toString()
                            + ", restore the expected value " + getApplicationLocale().toString());
                    updateApplicationSettings(getApplicationLocale(),
                            FontScale.INSTANCE.getFontScalePrefValue(),
                            ThemeUtils.INSTANCE.getApplicationTheme(activity));
                    restartActivity(activity);
                }

                PermissionsToolsKt.logPermissionStatuses(VectorApp.this);
            }

            @Override
            public void onActivityPaused(Activity activity) {
                Log.d(LOG_TAG, "onActivityPaused " + activity);
                mLocalesByActivity.put(activity.toString(), getActivityLocaleStatus(activity));
                setCurrentActivity(null);
                onAppPause();
            }

            @Override
            public void onActivityStopped(Activity activity) {
                Log.d(LOG_TAG, "onActivityStopped " + activity);
            }

            @Override
            public void onActivitySaveInstanceState(Activity activity, Bundle outState) {
                Log.d(LOG_TAG, "onActivitySaveInstanceState " + activity);
            }

            @Override
            public void onActivityDestroyed(Activity activity) {
                Log.d(LOG_TAG, "onActivityDestroyed " + activity);
                mCreatedActivities.remove(activity.toString());
                mLocalesByActivity.remove(activity.toString());

                if (mCreatedActivities.size() > 1) {
                    Log.d(LOG_TAG, "onActivityDestroyed : \n" + mCreatedActivities);
                }
            }
        });

        // create the markdown parser
        try {
            mMarkdownParser = new VectorMarkdownParser(this);
        } catch (Exception e) {
            // reported by GA
            Log.e(LOG_TAG, "cannot create the mMarkdownParser " + e.getMessage(), e);
        }

        // track external language updates
        // local update from the settings
        // or screen rotation !
        registerReceiver(mLanguageReceiver, new IntentFilter(Intent.ACTION_LOCALE_CHANGED));
        registerReceiver(mLanguageReceiver, new IntentFilter(Intent.ACTION_CONFIGURATION_CHANGED));

        PreferencesManager.fixMigrationIssues(this);
        initApplicationLocale();
        visitSessionVariables();
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (!TextUtils.equals(Locale.getDefault().toString(), getApplicationLocale().toString())) {
            Log.d(LOG_TAG, "## onConfigurationChanged() : the locale has been updated to " + Locale.getDefault().toString()
                    + ", restore the expected value " + getApplicationLocale().toString());
            updateApplicationSettings(getApplicationLocale(),
                    FontScale.INSTANCE.getFontScalePrefValue(),
                    ThemeUtils.INSTANCE.getApplicationTheme(this));
        }
    }

    /**
     * Parse a markdown text
     *
     * @param text     the text to parse
     * @param listener the result listener
     */
    public static void markdownToHtml(final String text, final VectorMarkdownParser.IVectorMarkdownParserListener listener) {
        if (null != getInstance().mMarkdownParser) {
            getInstance().mMarkdownParser.markdownToHtml(text, listener);
        } else {
            (new Handler(Looper.getMainLooper())).post(new Runnable() {
                @Override
                public void run() {
                    // GA issue
                    listener.onMarkdownParsed(text, null);
                }
            });
        }
    }

    /**
     * Suspend background threads.
     */
    private void suspendApp() {
        GcmRegistrationManager gcmRegistrationManager = Matrix.getInstance(VectorApp.this).getSharedGCMRegistrationManager();

        // suspend the events thread if the client uses GCM
        if (!gcmRegistrationManager.isBackgroundSyncAllowed() || (gcmRegistrationManager.useGCM() && gcmRegistrationManager.hasRegistrationToken())) {
            Log.d(LOG_TAG, "suspendApp ; pause the event stream");
            CommonActivityUtils.pauseEventStream(VectorApp.this);
        } else {
            Log.d(LOG_TAG, "suspendApp ; the event stream is not paused because GCM is disabled.");
        }

        // the sessions are not anymore seen as "online"
        List<MXSession> sessions = Matrix.getInstance(this).getSessions();

        for (MXSession session : sessions) {
            if (session.isAlive()) {
                session.setIsOnline(false);
                session.setSyncDelay(gcmRegistrationManager.isBackgroundSyncAllowed() ? gcmRegistrationManager.getBackgroundSyncDelay() : 0);
                session.setSyncTimeout(gcmRegistrationManager.getBackgroundSyncTimeOut());

                // remove older medias
                if ((System.currentTimeMillis() - mLastMediasCheck) < (24 * 60 * 60 * 1000)) {
                    mLastMediasCheck = System.currentTimeMillis();
                    session.removeMediasBefore(VectorApp.this, PreferencesManager.getMinMediasLastAccessTime(getApplicationContext()));
                }

                if (session.getDataHandler().areLeftRoomsSynced()) {
                    session.getDataHandler().releaseLeftRooms();
                }
            }
        }

        clearSyncingSessions();

        PIDsRetriever.getInstance().onAppBackgrounded();

        MyPresenceManager.advertiseAllUnavailable();

        mRageShake.stop();

        onAppPause();
    }

    /**
     * Test if application is put in background.
     * i.e wait 2s before assuming that the application is put in background.
     */
    private void startActivityTransitionTimer() {
        Log.d(LOG_TAG, "## startActivityTransitionTimer()");

        try {
            mActivityTransitionTimer = new Timer();
            mActivityTransitionTimerTask = new TimerTask() {
                @Override
                public void run() {
                    // reported by GA
                    try {
                        if (mActivityTransitionTimerTask != null) {
                            mActivityTransitionTimerTask.cancel();
                            mActivityTransitionTimerTask = null;
                        }

                        if (mActivityTransitionTimer != null) {
                            mActivityTransitionTimer.cancel();
                            mActivityTransitionTimer = null;
                        }
                    } catch (Exception e) {
                        Log.e(LOG_TAG, "## startActivityTransitionTimer() failed " + e.getMessage(), e);
                    }

                    if (null != mCurrentActivity) {
                        Log.e(LOG_TAG, "## startActivityTransitionTimer() : the timer expires but there is an active activity.");
                    } else {
                        mIsInBackground = true;
                        mIsCallingInBackground = (null != mCallsManager.getActiveCall());

                        // if there is a pending call
                        // the application is not suspended
                        if (!mIsCallingInBackground) {
                            Log.d(LOG_TAG, "Suspend the application because there was no resumed activity within "
                                    + (MAX_ACTIVITY_TRANSITION_TIME_MS / 1000) + " seconds");
                            CommonActivityUtils.displayMemoryInformation(null, " app suspended");
                            suspendApp();
                        } else {
                            Log.d(LOG_TAG, "App not suspended due to call in progress");
                        }
                    }
                }
            };

            mActivityTransitionTimer.schedule(mActivityTransitionTimerTask, MAX_ACTIVITY_TRANSITION_TIME_MS);
        } catch (Throwable throwable) {
            Log.e(LOG_TAG, "## startActivityTransitionTimer() : failed to start the timer " + throwable.getMessage());

            if (null != mActivityTransitionTimer) {
                mActivityTransitionTimer.cancel();
                mActivityTransitionTimer = null;
            }
        }
    }

    /**
     * Stop the background detection.
     */
    private void stopActivityTransitionTimer() {
        Log.d(LOG_TAG, "## stopActivityTransitionTimer()");

        if (mActivityTransitionTimerTask != null) {
            mActivityTransitionTimerTask.cancel();
            mActivityTransitionTimerTask = null;
        }

        if (mActivityTransitionTimer != null) {
            mActivityTransitionTimer.cancel();
            mActivityTransitionTimer = null;
        }

        if (isAppInBackground() && !mIsCallingInBackground) {
            // the event stream service has been killed
            if (EventStreamService.isStopped()) {
                CommonActivityUtils.startEventStreamService(VectorApp.this);
            } else {
                CommonActivityUtils.resumeEventStream(VectorApp.this);

                // try to perform a GCM registration if it failed
                // or if the GCM server generated a new push key
                GcmRegistrationManager gcmRegistrationManager = Matrix.getInstance(this).getSharedGCMRegistrationManager();

                if (null != gcmRegistrationManager) {
                    gcmRegistrationManager.checkRegistrations();
                }
            }

            // get the contact update at application launch
            ContactsManager.getInstance().clearSnapshot();
            ContactsManager.getInstance().refreshLocalContactsSnapshot();

            List<MXSession> sessions = Matrix.getInstance(this).getSessions();
            for (MXSession session : sessions) {
                session.getMyUser().refreshUserInfos(null);
                session.setIsOnline(true);
                session.setSyncDelay(0);
                session.setSyncTimeout(0);
                addSyncingSession(session);
            }

            mCallsManager.checkDeadCalls();
            Matrix.getInstance(this).getSharedGCMRegistrationManager().onAppResume();
        }

        MyPresenceManager.advertiseAllOnline();
        mRageShake.start();

        mIsCallingInBackground = false;
        mIsInBackground = false;
    }

    /**
     * Update the current active activity.
     * It manages the application background / foreground when it is required.
     *
     * @param activity the current activity, null if there is no more one.
     */
    private void setCurrentActivity(Activity activity) {
        Log.d(LOG_TAG, "## setCurrentActivity() : from " + mCurrentActivity + " to " + activity);

        if (VectorApp.isAppInBackground() && (null != activity)) {
            Matrix matrixInstance = Matrix.getInstance(activity.getApplicationContext());

            // sanity check
            if (null != matrixInstance) {
                matrixInstance.refreshPushRules();
            }

            Log.d(LOG_TAG, "The application is resumed");
            // display the memory usage when the application is put iun foreground..
            CommonActivityUtils.displayMemoryInformation(activity, " app resumed with " + activity);
        }

        // wait 2s to check that the application is put in background
        if (null != getInstance()) {
            if (null == activity) {
                getInstance().startActivityTransitionTimer();
            } else {
                getInstance().stopActivityTransitionTimer();
            }
        } else {
            Log.e(LOG_TAG, "The application is resumed but there is no active instance");
        }

        mCurrentActivity = activity;

        if (null != mCurrentActivity) {
            KeyRequestHandler.getSharedInstance().processNextRequest();
        }
    }

    /**
     * @return the analytics app instance
     */
    public Analytics getAnalytics() {
        return mAppAnalytics;
    }

    /**
     * @return the DecryptionFailureTracker instance
     */
    public DecryptionFailureTracker getDecryptionFailureTracker() {
        return mDecryptionFailureTracker;
    }

    /**
     * @return the current active activity
     */
    public static Activity getCurrentActivity() {
        return mCurrentActivity;
    }

    /**
     * Return true if the application is in background.
     */
    public static boolean isAppInBackground() {
        return (null == mCurrentActivity) && (null != getInstance()) && getInstance().mIsInBackground;
    }

    /**
     * Restart an activity to manage language update
     *
     * @param activity the activity to restart
     */
    private void restartActivity(Activity activity) {
        // avoid restarting activities when it is not required
        // some of them has no text
        if (!(activity instanceof VectorMediasPickerActivity)
                && !(activity instanceof VectorCallViewActivity)
                && !(activity instanceof JitsiCallActivity)
                && !(activity instanceof WidgetActivity)) {
            activity.startActivity(activity.getIntent());
            activity.finish();
        }
    }

    //==============================================================================================================
    // cert management : store the active activities.
    //==============================================================================================================

    private final EventEmitter<Activity> mOnActivityDestroyedListener = new EventEmitter<>();

    /**
     * @return the EventEmitter list.
     */
    public EventEmitter<Activity> getOnActivityDestroyedListener() {
        return mOnActivityDestroyedListener;
    }

    //==============================================================================================================
    // Media pickers : image backup
    //==============================================================================================================

    private static Bitmap mSavedPickerImagePreview = null;

    /**
     * The image taken from the medias picker is stored in a static variable because
     * saving it would take too much time.
     *
     * @return the saved image from medias picker
     */
    public static Bitmap getSavedPickerImagePreview() {
        return mSavedPickerImagePreview;
    }

    /**
     * Save the image taken in the medias picker
     *
     * @param aSavedCameraImagePreview the bitmap.
     */
    public static void setSavedCameraImagePreview(Bitmap aSavedCameraImagePreview) {
        if (aSavedCameraImagePreview != mSavedPickerImagePreview) {
            // force to release memory
            // reported by GA
            // it seems that the medias picker might be refreshed
            // while leaving the activity
            // recycle the bitmap trigger a rendering issue
            // Canvas: trying to use a recycled bitmap...

            /*if (null != mSavedPickerImagePreview) {
                mSavedPickerImagePreview.recycle();
                mSavedPickerImagePreview = null;
                System.gc();
            }*/


            mSavedPickerImagePreview = aSavedCameraImagePreview;
        }
    }

    //==============================================================================================================
    // Syncing mxSessions
    //==============================================================================================================

    /**
     * syncing sessions
     */
    private static final Set<MXSession> mSyncingSessions = new HashSet<>();

    /**
     * Add a session in the syncing sessions list
     *
     * @param session the session
     */
    public static void addSyncingSession(MXSession session) {
        synchronized (mSyncingSessions) {
            mSyncingSessions.add(session);
        }
    }

    /**
     * Remove a session in the syncing sessions list
     *
     * @param session the session
     */
    public static void removeSyncingSession(MXSession session) {
        if (null != session) {
            synchronized (mSyncingSessions) {
                mSyncingSessions.remove(session);
            }
        }
    }

    /**
     * Clear syncing sessions list
     */
    public static void clearSyncingSessions() {
        synchronized (mSyncingSessions) {
            mSyncingSessions.clear();
        }
    }

    /**
     * Tell if a session is syncing
     *
     * @param session the session
     * @return true if the session is syncing
     */
    public static boolean isSessionSyncing(MXSession session) {
        boolean isSyncing = false;

        if (null != session) {
            synchronized (mSyncingSessions) {
                isSyncing = mSyncingSessions.contains(session);
            }
        }

        return isSyncing;
    }

    /**
     * Tells if the application crashed
     *
     * @return true if the application crashed
     */
    public boolean didAppCrash() {
        final SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(VectorApp.getInstance());
        return preferences.getBoolean(PREFS_CRASH_KEY, false);
    }


    /**
     * Clear the crash status
     */
    public void clearAppCrashStatus() {
        PreferenceManager.getDefaultSharedPreferences(VectorApp.getInstance())
                .edit()
                .remove(PREFS_CRASH_KEY)
                .apply();
    }

    //==============================================================================================================
    // Locale management
    //==============================================================================================================

    // the supported application languages
    private static final Set<Locale> mApplicationLocales = new HashSet<>();

    private static final String APPLICATION_LOCALE_COUNTRY_KEY = "APPLICATION_LOCALE_COUNTRY_KEY";
    private static final String APPLICATION_LOCALE_VARIANT_KEY = "APPLICATION_LOCALE_VARIANT_KEY";
    private static final String APPLICATION_LOCALE_LANGUAGE_KEY = "APPLICATION_LOCALE_LANGUAGE_KEY";

    private static final Locale mApplicationDefaultLanguage = new Locale("en", "US");

    /**
     * Init the application locale from the saved one
     */
    private static void initApplicationLocale() {
        Context context = VectorApp.getInstance();
        Locale locale = getApplicationLocale();
        float fontScale = FontScale.INSTANCE.getFontScale();
        String theme = ThemeUtils.INSTANCE.getApplicationTheme(context);

        Locale.setDefault(locale);
        Configuration config = new Configuration(context.getResources().getConfiguration());
        config.locale = locale;
        config.fontScale = fontScale;
        context.getResources().updateConfiguration(config, context.getResources().getDisplayMetrics());

        // init the theme
        ThemeUtils.INSTANCE.setApplicationTheme(context, theme);

        // init the known locales in background
        AsyncTask<Void, Void, Void> task = new AsyncTask<Void, Void, Void>() {
            @Override
            protected Void doInBackground(Void... params) {
                getApplicationLocales(VectorApp.getInstance());
                return null;
            }
        };

        // should never crash
        task.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }

    /**
     * Provides the current application locale
     *
     * @return the application locale
     */
    public static Locale getApplicationLocale() {
        Context context = VectorApp.getInstance();
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(context);
        Locale locale;

        if (!preferences.contains(APPLICATION_LOCALE_LANGUAGE_KEY)) {
            locale = Locale.getDefault();

            // detect if the default language is used
            String defaultStringValue = getString(context, mApplicationDefaultLanguage, R.string.resources_country_code);
            if (TextUtils.equals(defaultStringValue, getString(context, locale, R.string.resources_country_code))) {
                locale = mApplicationDefaultLanguage;
            }

            saveApplicationLocale(locale);
        } else {
            locale = new Locale(preferences.getString(APPLICATION_LOCALE_LANGUAGE_KEY, ""),
                    preferences.getString(APPLICATION_LOCALE_COUNTRY_KEY, ""),
                    preferences.getString(APPLICATION_LOCALE_VARIANT_KEY, "")
            );
        }

        return locale;
    }

    /**
     * Provides the device locale
     *
     * @return the device locale
     */
    public static Locale getDeviceLocale() {
        Context context = VectorApp.getInstance();
        Locale locale = getApplicationLocale();

        try {
            PackageManager packageManager = context.getPackageManager();
            Resources resources = packageManager.getResourcesForApplication("android");
            locale = resources.getConfiguration().locale;
        } catch (Exception e) {
            Log.e(LOG_TAG, "## getDeviceLocale() failed " + e.getMessage(), e);
        }

        return locale;
    }

    /**
     * Save the new application locale.
     */
    private static void saveApplicationLocale(Locale locale) {
        Context context = VectorApp.getInstance();

        SharedPreferences.Editor editor = PreferenceManager.getDefaultSharedPreferences(context).edit();

        String language = locale.getLanguage();
        if (!TextUtils.isEmpty(language)) {
            editor.putString(APPLICATION_LOCALE_LANGUAGE_KEY, language);
        } else {
            editor.remove(APPLICATION_LOCALE_LANGUAGE_KEY);
        }

        String country = locale.getCountry();
        if (!TextUtils.isEmpty(country)) {
            editor.putString(APPLICATION_LOCALE_COUNTRY_KEY, country);
        } else {
            editor.remove(APPLICATION_LOCALE_COUNTRY_KEY);
        }

        String variant = locale.getVariant();
        if (!TextUtils.isEmpty(variant)) {
            editor.putString(APPLICATION_LOCALE_VARIANT_KEY, variant);
        } else {
            editor.remove(APPLICATION_LOCALE_VARIANT_KEY);
        }

        editor.apply();
    }

    /**
     * Update the application locale
     *
     * @param locale
     */
    public static void updateApplicationLocale(Locale locale) {
        updateApplicationSettings(locale, FontScale.INSTANCE.getFontScalePrefValue(), ThemeUtils.INSTANCE.getApplicationTheme(VectorApp.getInstance()));
    }

    /**
     * Update the application theme
     *
     * @param theme the new theme
     */
    public static void updateApplicationTheme(String theme) {
        ThemeUtils.INSTANCE.setApplicationTheme(VectorApp.getInstance(), theme);
        updateApplicationSettings(getApplicationLocale(),
                FontScale.INSTANCE.getFontScalePrefValue(),
                ThemeUtils.INSTANCE.getApplicationTheme(VectorApp.getInstance()));
    }

    /**
     * Update the application locale.
     *
     * @param locale the locale
     * @param theme  the new theme
     */
    @SuppressWarnings("deprecation")
    @SuppressLint("NewApi")
    private static void updateApplicationSettings(Locale locale, String textSize, String theme) {
        Context context = VectorApp.getInstance();

        saveApplicationLocale(locale);
        FontScale.INSTANCE.saveFontScale(textSize);
        Locale.setDefault(locale);

        Configuration config = new Configuration(context.getResources().getConfiguration());
        config.locale = locale;
        config.fontScale = FontScale.INSTANCE.getFontScale();
        context.getResources().updateConfiguration(config, context.getResources().getDisplayMetrics());

        ThemeUtils.INSTANCE.setApplicationTheme(context, theme);
        PhoneNumberUtils.onLocaleUpdate();
    }

    /**
     * Compute a localised context
     *
     * @param context the context
     * @return the localised context
     */
    @SuppressWarnings("deprecation")
    @SuppressLint("NewApi")
    public static Context getLocalisedContext(Context context) {
        try {
            Resources resources = context.getResources();
            Locale locale = getApplicationLocale();
            Configuration configuration = resources.getConfiguration();
            configuration.fontScale = FontScale.INSTANCE.getFontScale();

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                configuration.setLocale(locale);
                configuration.setLayoutDirection(locale);
                return context.createConfigurationContext(configuration);
            } else {
                configuration.locale = locale;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
                    configuration.setLayoutDirection(locale);
                }
                resources.updateConfiguration(configuration, resources.getDisplayMetrics());
                return context;
            }
        } catch (Exception e) {
            Log.e(LOG_TAG, "## getLocalisedContext() failed : " + e.getMessage(), e);
        }

        return context;
    }

    /**
     * Get String from a locale
     *
     * @param context    the context
     * @param locale     the locale
     * @param resourceId the string resource id
     * @return the localized string
     */
    private static String getString(Context context, Locale locale, int resourceId) {
        String result;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
            Configuration config = new Configuration(context.getResources().getConfiguration());
            config.setLocale(locale);
            try {
                result = context.createConfigurationContext(config).getText(resourceId).toString();
            } catch (Exception e) {
                Log.e(LOG_TAG, "## getString() failed : " + e.getMessage(), e);
                // use the default one
                result = context.getString(resourceId);
            }
        } else {
            Resources resources = context.getResources();
            Configuration conf = resources.getConfiguration();
            Locale savedLocale = conf.locale;
            conf.locale = locale;
            resources.updateConfiguration(conf, null);

            // retrieve resources from desired locale
            result = resources.getString(resourceId);

            // restore original locale
            conf.locale = savedLocale;
            resources.updateConfiguration(conf, null);
        }

        return result;
    }

    /**
     * Provides the supported application locales list
     *
     * @param context the context
     * @return the supported application locales list
     */
    public static List<Locale> getApplicationLocales(Context context) {
        if (mApplicationLocales.isEmpty()) {

            Set<Pair<String, String>> knownLocalesSet = new HashSet<>();

            try {
                final Locale[] availableLocales = Locale.getAvailableLocales();

                for (Locale locale : availableLocales) {
                    knownLocalesSet.add(new Pair<>(getString(context, locale, R.string.resources_language),
                            getString(context, locale, R.string.resources_country_code)));
                }
            } catch (Exception e) {
                Log.e(LOG_TAG, "## getApplicationLocales() : failed " + e.getMessage(), e);
                knownLocalesSet.add(new Pair<>(context.getString(R.string.resources_language), context.getString(R.string.resources_country_code)));
            }

            for (Pair<String, String> knownLocale : knownLocalesSet) {
                mApplicationLocales.add(new Locale(knownLocale.first, knownLocale.second));
            }
        }

        List<Locale> sortedLocalesList = new ArrayList<>(mApplicationLocales);

        // sort by human display names
        Collections.sort(sortedLocalesList, new Comparator<Locale>() {
            @Override
            public int compare(Locale lhs, Locale rhs) {
                return localeToLocalisedString(lhs).compareTo(localeToLocalisedString(rhs));
            }
        });

        return sortedLocalesList;
    }

    /**
     * Convert a locale to a string
     *
     * @param locale the locale to convert
     * @return the string
     */
    public static String localeToLocalisedString(Locale locale) {
        String res = locale.getDisplayLanguage(locale);

        if (!TextUtils.isEmpty(locale.getDisplayCountry(locale))) {
            res += " (" + locale.getDisplayCountry(locale) + ")";
        }

        return res;
    }

    //==============================================================================================================
    // Analytics management
    //==============================================================================================================

    /**
     * Send session custom variables
     */
    private void visitSessionVariables() {
        mAppAnalytics.visitVariable(1, "App Platform", "Android Platform");
        mAppAnalytics.visitVariable(2, "App Version", BuildConfig.VERSION_NAME);
        mAppAnalytics.visitVariable(4, "Chosen Language", getApplicationLocale().toString());

        final MXSession session = Matrix.getInstance(this).getDefaultSession();
        if (session != null) {
            mAppAnalytics.visitVariable(7, "Homeserver URL", session.getHomeServerConfig().getHomeserverUri().toString());
            mAppAnalytics.visitVariable(8, "Identity Server URL", session.getHomeServerConfig().getIdentityServerUri().toString());
        }
    }

    /**
     * A new activity has been resumed
     *
     * @param activity the new activity
     */
    private void onNewScreen(Activity activity) {
        final String screenPath = "/android/" + Matrix.getApplicationName()
                + "/" + getString(R.string.flavor_description)
                + "/" + BuildConfig.VERSION_NAME
                + "/" + activity.getClass().getName().replace(".", "/");
        mAppAnalytics.trackScreen(screenPath, null);
    }

    /**
     * The application is paused.
     */
    private void onAppPause() {
        mDecryptionFailureTracker.dispatch();
        mAppAnalytics.forceDispatch();
    }
}
