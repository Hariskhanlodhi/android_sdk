//
//  ActivityHandler.java
//  Adjust
//
//  Created by Christian Wellenbrock on 2013-06-25.
//  Copyright (c) 2013 adjust GmbH. All rights reserved.
//  See the file MIT-LICENSE for copying permission.
//

package com.adjust.sdk;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.preference.PreferenceManager;

import org.json.JSONObject;

import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static com.adjust.sdk.Constants.ATTRIBUTION_FILENAME;
import static com.adjust.sdk.Constants.LOGTAG;
import static com.adjust.sdk.Constants.SESSION_STATE_FILENAME;

public class ActivityHandler extends HandlerThread {

    private static long TIMER_INTERVAL;
    private static long SESSION_INTERVAL;
    private static long SUBSESSION_INTERVAL;
    private static final String TIME_TRAVEL = "Time travel!";
    private static final String ADJUST_PREFIX = "adjust_";
    private static final String ACTIVITY_STATE_NAME = "activity state";
    private static final String ATTRIBUTION_NAME = "attribution";

    private        SessionHandler           sessionHandler;
    private        IPackageHandler          packageHandler;
    private        ActivityState            activityState;
    private        Logger                   logger;
    private static ScheduledExecutorService timer;
    private        boolean                  enabled;

    private DeviceInfo deviceInfo;
    private AdjustConfig adjustConfig;
    private Attribution attribution;
    private AttributionHandler attributionHandler;

    public ActivityHandler(AdjustConfig adjustConfig) {
        super(LOGTAG, MIN_PRIORITY);
        setDaemon(true);
        start();

        sessionHandler = new SessionHandler(getLooper(), this);
        enabled = true;

        Message message = Message.obtain();
        message.arg1 = SessionHandler.INIT;
        message.obj = adjustConfig;
        sessionHandler.sendMessage(message);
    }

    public void trackSubsessionStart() {
        Message message = Message.obtain();
        message.arg1 = SessionHandler.START;
        sessionHandler.sendMessage(message);
    }

    public void trackSubsessionEnd() {
        Message message = Message.obtain();
        message.arg1 = SessionHandler.END;
        sessionHandler.sendMessage(message);
    }

    public void trackEvent(Event event) {
        Message message = Message.obtain();
        message.arg1 = SessionHandler.EVENT;
        message.obj = event;
        sessionHandler.sendMessage(message);
    }

    public void finishedTrackingActivity(JSONObject jsonResponse) {
        if (jsonResponse == null) {
            return;
        }

        String deepLink = jsonResponse.optString("deeplink", null);
        launchDeepLinkMain(deepLink);
        attributionHandler.checkAttribution(jsonResponse);
    }

    public void setEnabled(Boolean enabled) {
        this.enabled = enabled;
        if (activityState != null) {
            activityState.enabled = enabled;
        }
        if (enabled) {
            this.trackSubsessionStart();
        } else {
            this.trackSubsessionEnd();
        }
    }

    public Boolean isEnabled() {
        if (activityState != null) {
            return activityState.enabled;
        } else {
            return this.enabled;
        }
    }

    public void readOpenUrl(Uri url) {
        Message message = Message.obtain();
        message.arg1 = SessionHandler.DEEP_LINK;
        message.obj = url;
        sessionHandler.sendMessage(message);
    }

    public void updateAttribution(Attribution attribution) {
        if (attribution == null) return;

        if (attribution.equals(this.attribution)) {
            return;
        }

        this.attribution = attribution;
        Util.writeObject(attribution, adjustConfig.context, ATTRIBUTION_FILENAME, ATTRIBUTION_NAME);
    }

    public void launchAttributionDelegate() {
        if (adjustConfig.onFinishedListener == null) {
            return;
        }
        Handler handler = new Handler(adjustConfig.context.getMainLooper());
        Runnable runnable = new Runnable() {
            @Override
            public void run() {
                adjustConfig.onFinishedListener.onFinishedTracking(attribution);
            }
        };
        handler.post(runnable);
    }

    public void setReferrer (String referrer) {
        PackageBuilder builder = new PackageBuilder(adjustConfig, deviceInfo, activityState);
        builder.referrer = referrer;
        ActivityPackage clickPackage = builder.buildClickPackage();
        packageHandler.sendClickPackage(clickPackage);
    }

    public void setOfflineMode(boolean enabled) {
        if (enabled) {
            endInternal();
        } else {
            packageHandler.resumeSending();
            startTimer();
        }
    }

    private static final class SessionHandler extends Handler {
        private static final int INIT        = 72630;
        private static final int START       = 72640;
        private static final int END         = 72650;
        private static final int EVENT       = 72660;
        private static final int DEEP_LINK   = 72680;


        private final WeakReference<ActivityHandler> sessionHandlerReference;

        protected SessionHandler(Looper looper, ActivityHandler sessionHandler) {
            super(looper);
            this.sessionHandlerReference = new WeakReference<ActivityHandler>(sessionHandler);
        }

        @Override
        public void handleMessage(Message message) {
            super.handleMessage(message);

            ActivityHandler sessionHandler = sessionHandlerReference.get();
            if (sessionHandler == null) {
                return;
            }

            switch (message.arg1) {
                case INIT:
                    AdjustConfig adjustConfig = (AdjustConfig) message.obj;
                    sessionHandler.initInternal(adjustConfig);
                    break;
                case START:
                    sessionHandler.startInternal();
                    break;
                case END:
                    sessionHandler.endInternal();
                    break;
                case EVENT:
                    Event event = (Event) message.obj;
                    sessionHandler.trackEventInternal(event);
                    break;
                case DEEP_LINK:
                    Uri url = (Uri) message.obj;
                    sessionHandler.readOpenUrlInternal(url);
                    break;
            }
        }
    }

    private void initInternal(AdjustConfig adjustConfig) {
        TIMER_INTERVAL = AdjustFactory.getTimerInterval();
        SESSION_INTERVAL = AdjustFactory.getSessionInterval();
        SUBSESSION_INTERVAL = AdjustFactory.getSubsessionInterval();

        this.adjustConfig = adjustConfig;
        deviceInfo = new DeviceInfo();
        logger = AdjustFactory.getLogger();

        if (adjustConfig.environment == AdjustConfig.PRODUCTION_ENVIRONMENT) {
            logger.setLogLevel(Logger.LogLevel.ASSERT);
        } else {
            logger.setLogLevel(adjustConfig.logLevel);
        }

        if (adjustConfig.sdkPrefix == null) {
            deviceInfo.clientSdk = Constants.CLIENT_SDK;
        } else {
            deviceInfo.clientSdk = String.format("%s@%s", adjustConfig.sdkPrefix, Constants.CLIENT_SDK);
        }

        deviceInfo.pluginKeys = Util.getPluginKeys(adjustConfig.context);

        // TODO check if AdjustDefaultTracker and AdjustDropOfflineActivities are still needed

        if (adjustConfig.eventBufferingEnabled) {
            logger.info("Event buffering is enabled");
        }

        deviceInfo.androidId= Util.getAndroidId(adjustConfig.context);
        deviceInfo.fbAttributionId = Util.getAttributionId(adjustConfig.context);
        deviceInfo.userAgent = Util.getUserAgent(adjustConfig.context);

        String playAdId = Util.getPlayAdId(adjustConfig.context);
        if (playAdId == null) {
            logger.info("Unable to get Google Play Services Advertising ID at start time");
        }

        if  (!Util.isGooglePlayServicesAvailable(adjustConfig.context)) {
            String macAddress = Util.getMacAddress(adjustConfig.context);
            deviceInfo.macSha1 = Util.getMacSha1(macAddress);
            deviceInfo.macShortMd5 = Util.getMacShortMd5(macAddress);
        }

        if (adjustConfig.defaultTracker != null) {
            logger.info("Default tracker: '%s'", adjustConfig.defaultTracker);
        }

        packageHandler = AdjustFactory.getPackageHandler(this, adjustConfig.context);
        attributionHandler = new AttributionHandler(this, adjustConfig.attributionMaxTimeMilliseconds);
        activityState = Util.readObject(adjustConfig.context, SESSION_STATE_FILENAME, ACTIVITY_STATE_NAME);

        startInternal();
    }

    private void startInternal() {
        if (activityState != null
            && !activityState.enabled) {
            return;
        }

        packageHandler.resumeSending();
        startTimer();

        long now = System.currentTimeMillis();

        // very first session
        if (null == activityState) {
            activityState = new ActivityState();
            activityState.sessionCount = 1; // this is the first session
            activityState.createdAt = now;  // starting now

            transferSessionPackage();
            activityState.resetSessionAttributes(now);
            activityState.enabled = this.enabled;
            Util.writeObject(activityState, adjustConfig.context, SESSION_STATE_FILENAME, ACTIVITY_STATE_NAME);
            if (adjustConfig.referrer != null) {
                setReferrer(adjustConfig.referrer);
            }
            return;
        }

        long lastInterval = now - activityState.lastActivity;

        if (lastInterval < 0) {
            logger.error(TIME_TRAVEL);
            activityState.lastActivity = now;
            Util.writeObject(activityState, adjustConfig.context, SESSION_STATE_FILENAME, ACTIVITY_STATE_NAME);
            return;
        }

        // new session
        if (lastInterval > SESSION_INTERVAL) {
            activityState.sessionCount++;
            activityState.createdAt = now;
            activityState.lastInterval = lastInterval;

            transferSessionPackage();
            activityState.resetSessionAttributes(now);
            Util.writeObject(activityState, adjustConfig.context, SESSION_STATE_FILENAME, ACTIVITY_STATE_NAME);
            return;
        }

        // new subsession
        if (lastInterval > SUBSESSION_INTERVAL) {
            activityState.subsessionCount++;
            logger.info("Started subsession %d of session %d",
                    activityState.subsessionCount,
                    activityState.sessionCount);
        }
        activityState.sessionLength += lastInterval;
        activityState.lastActivity = now;
        Util.writeObject(activityState, adjustConfig.context, SESSION_STATE_FILENAME, ACTIVITY_STATE_NAME);
    }

    private void endInternal() {
        packageHandler.pauseSending();
        stopTimer();
        updateActivityState(System.currentTimeMillis());
        Util.writeObject(activityState, adjustConfig.context, SESSION_STATE_FILENAME, ACTIVITY_STATE_NAME);
    }

    private void trackEventInternal(Event event) {
        if (!activityState.enabled) {
            return;
        }

        long now = System.currentTimeMillis();
        activityState.createdAt = now;
        activityState.eventCount++;
        updateActivityState(now);

        PackageBuilder eventBuilder = new PackageBuilder(adjustConfig, deviceInfo, activityState);
        eventBuilder.event = event;
        ActivityPackage eventPackage = eventBuilder.buildEventPackage();
        packageHandler.addPackage(eventPackage);

        if (adjustConfig.eventBufferingEnabled) {
            logger.info("Buffered event %s", eventPackage.getSuffix());
        } else {
            packageHandler.sendFirstPackage();
        }

        Util.writeObject(activityState, adjustConfig.context, SESSION_STATE_FILENAME, ACTIVITY_STATE_NAME);
    }

    private void readOpenUrlInternal(Uri url) {
        if (url == null) {
            return;
        }

        String queryString = url.getQuery();
        if (queryString == null) {
            return;
        }

        Map<String, String> adjustDeepLinks = new HashMap<String, String>();

        String[] queryPairs = queryString.split("&");
        for (String pair : queryPairs) {
            String[] pairComponents = pair.split("=");
            if (pairComponents.length != 2) continue;

            String key = pairComponents[0];
            if (!key.startsWith(ADJUST_PREFIX)) continue;

            String value = pairComponents[1];
            if (value.length() == 0) continue;

            String keyWOutPrefix = key.substring(ADJUST_PREFIX.length());
            if (keyWOutPrefix.length() == 0) continue;

            adjustDeepLinks.put(keyWOutPrefix, value);
        }

        attributionHandler.getAttribution();

        if (adjustDeepLinks.size() == 0) {
            return;
        }

        PackageBuilder builder = new PackageBuilder(adjustConfig, deviceInfo, activityState);
        builder.deepLinkParameters = adjustDeepLinks;
        ActivityPackage clickPackage = builder.buildClickPackage();
        packageHandler.sendClickPackage(clickPackage);
    }

    private void launchDeepLinkMain(String deepLink) {
        if (deepLink == null) return;

        Uri location = Uri.parse(deepLink);
        Intent mapIntent = new Intent(Intent.ACTION_VIEW, location);
        mapIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

        // Verify it resolves
        PackageManager packageManager = adjustConfig.context.getPackageManager();
        List<ResolveInfo> activities = packageManager.queryIntentActivities(mapIntent, 0);
        boolean isIntentSafe = activities.size() > 0;

        // Start an activity if it's safe
        if (!isIntentSafe) {
            logger.error("Unable to open deep link (%s)", deepLink);
            return;
        }

        logger.info("Open deep link (%s)", deepLink);
        adjustConfig.context.startActivity(mapIntent);
    }

    private void updateActivityState(long now) {
        long lastInterval = now - activityState.lastActivity;
        if (lastInterval < 0) {
            logger.error(TIME_TRAVEL);
            activityState.lastActivity = now;
            return;
        }

        // ignore late updates
        if (lastInterval > SESSION_INTERVAL) {
            return;
        }

        activityState.sessionLength += lastInterval;
        activityState.timeSpent += lastInterval;
        activityState.lastActivity = now;
    }

    public static Boolean deleteActivityState(Context context) {
        return context.deleteFile(SESSION_STATE_FILENAME);
    }

    private void transferSessionPackage() {
        PackageBuilder builder = new PackageBuilder(adjustConfig, deviceInfo, activityState);
        ActivityPackage sessionPackage = builder.buildSessionPackage();
        packageHandler.addPackage(sessionPackage);
        packageHandler.sendFirstPackage();
    }

    private void startTimer() {
        if (timer != null) {
            stopTimer();
        }
        timer = Executors.newSingleThreadScheduledExecutor();
        timer.scheduleWithFixedDelay(new Runnable() {
            @Override
            public void run() {
                timerFired();
            }
        }, 1000, TIMER_INTERVAL, TimeUnit.MILLISECONDS);
    }

    private void stopTimer() {
        if (timer != null) {
            timer.shutdown();
        }
    }

    private void timerFired() {
        if (null != activityState
            && !activityState.enabled) {
            return;
        }

        packageHandler.sendFirstPackage();

        updateActivityState(System.currentTimeMillis());
        Util.writeObject(activityState, adjustConfig.context, SESSION_STATE_FILENAME, ACTIVITY_STATE_NAME);
    }

    // TODO validate if this is equal with an offline mode
    /*
    private void setDropOfflineActivities(boolean drop) {
        dropOfflineActivities = drop;
        if (dropOfflineActivities) {
            logger.info("Offline activities will get dropped");
        }
    }
    */
}
