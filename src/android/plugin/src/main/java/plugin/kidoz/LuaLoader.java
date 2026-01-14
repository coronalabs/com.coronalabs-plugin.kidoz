//
// LuaLoader.java
// Kidoz Plugin
//
// Copyright (c) 2016 CoronaLabs inc. All rights reserved.

// @formatter:off

package plugin.kidoz;

// imports
import android.graphics.Point;
import android.util.Log;
import android.view.Display;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.RelativeLayout;

import com.naef.jnlua.LuaState;
import com.naef.jnlua.JavaFunction;
import com.naef.jnlua.LuaType;
import com.naef.jnlua.NamedJavaFunction;

import com.ansca.corona.CoronaEnvironment;
import com.ansca.corona.CoronaRuntime;
import com.ansca.corona.CoronaRuntimeListener;
import com.ansca.corona.CoronaActivity;
import com.ansca.corona.CoronaLua;
import com.ansca.corona.CoronaLuaEvent;
import com.ansca.corona.CoronaRuntimeTask;
import com.ansca.corona.CoronaRuntimeTaskDispatcher;
import com.ansca.corona.CoronaBeacon;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

// Kidoz SDK imports
import net.kidoz.sdk.Kidoz;
import net.kidoz.sdk.KidozError;
import net.kidoz.sdk.KidozInitializationListener;
import net.kidoz.ads.banner.KidozBannerView;
import net.kidoz.ads.banner.KidozBannerAdCallback;
import net.kidoz.ads.fullscreen.interstial.KidozInterstitialAd;
import net.kidoz.ads.fullscreen.interstial.KidozInterstitialAdCallback;
import net.kidoz.ads.fullscreen.rewarded.KidozRewardedAd;
import net.kidoz.ads.fullscreen.rewarded.KidozRewardedAdCallback;

/**
 * Implements the Lua interface for the Kidoz Plugin.
 * <p/>
 * Only one instance of this class will be created by Corona for the lifetime of the application.
 * This instance will be re-used for every new Corona activity that gets created.
 */
public class LuaLoader implements JavaFunction, CoronaRuntimeListener
{
    private static final String PLUGIN_NAME        = "plugin.kidoz";
    private static final String PLUGIN_VERSION     = "3.0";
    private static final String PLUGIN_SDK_VERSION = "10.1.6";

    private static final String EVENT_NAME     = "adsRequest";
    private static final String PROVIDER_NAME  = "kidoz";

    // ad types
    private static final String ADTYPE_PANELVIEW     = "panelView";
    private static final String ADTYPE_FLEXIVIEW     = "flexiView";
    private static final String ADTYPE_BANNER        = "banner";
    private static final String ADTYPE_INTERSTITIAL  = "interstitial";
    private static final String ADTYPE_REWARDEDVIDEO = "rewardedVideo";
    private static final String ADTYPE_VIDEOUNIT     = "videoUnit";
    private static final String ADTYPE_VIDEO         = "video";

    // positions
    private static final String POS_TOP    = "top";
    private static final String POS_BOTTOM = "bottom";
    private static final String POS_LEFT   = "left";
    private static final String POS_RIGHT  = "right";
    private static final String POS_CENTER = "center";
    private static final String POS_CUSTOM = "custom";
    private static final String POS_NONE   = "none";

    private static final String POS_TOP_LEFT    = "topLeft";
    private static final String POS_TOP_RIGHT    = "topRight";
    private static final String POS_BOTTOM_LEFT    = "bottomLeft";
    private static final String POS_BOTTOM_RIGHT    = "bottomRight";

    // set up valid positions
    private static final List<String> validBannerPositions = new ArrayList<>();
    private static final List<String> validAdTypes = new ArrayList<>();

    // event phases
    private static final String PHASE_INIT           = "init";
    private static final String PHASE_LOADED         = "loaded";
    private static final String PHASE_FAILED         = "failed";
    private static final String PHASE_DISPLAYED      = "displayed";
    private static final String PHASE_CLOSED         = "closed";
    private static final String PHASE_REWARD         = "reward";
    private static final String PHASE_PLAYBACK_BEGAN = "playbackBegan";
    private static final String PHASE_PLAYBACK_ENDED = "playbackEnded";

    // response
    private static final String RESPONSE_LOAD_FAILED = "loadFailed";
    private static final String RESPONSE_NO_OFFERS   = "noOffersAvailable";

    // add missing keys
    private static final String EVENT_PHASE_KEY = "phase";
    private static final String EVENT_TYPE_KEY  = "type";

    // message constants
    private static final String CORONA_TAG  = "Corona";
    private static final String ERROR_MSG   = "ERROR: ";
    private static final String WARNING_MSG = "WARNING: ";

    // delegates
    private static BannerDelegate bannerDelegate;
    private static InterstitialDelegate interstitialDelegate;
    private static RewardedDelegate rewardedDelegate;

    private static int coronaListener = CoronaLua.REFNIL;
    private static CoronaRuntimeTaskDispatcher coronaRuntimeTaskDispatcher = null;

    private static String functionSignature = "";
    private static final Map<String, Object> kidozObjects = new HashMap<>();

    // Store actual ad instances
    private static KidozInterstitialAd currentInterstitialAd = null;
    private static KidozRewardedAd currentRewardedAd = null;
    private static KidozBannerView currentBannerView = null;

    // class to hold ad instance information
    private class KidozAdInfo
    {
        private Object adInstance;
        private boolean isLoaded;

        KidozAdInfo(Object adInstance) {
            this.adInstance = adInstance;
            isLoaded = false;
        }
    }

    // -------------------------------------------------------------------
    // Plugin lifecycle events
    // -------------------------------------------------------------------

    @SuppressWarnings("unused")
    public LuaLoader()
    {
        CoronaEnvironment.addRuntimeListener(this);
    }

    @Override
    public int invoke(LuaState L)
    {
        NamedJavaFunction[] luaFunctions = new NamedJavaFunction[] {
                new Init(),
                new Load(),
                new IsLoaded(),
                new Show(),
                new Hide()
        };
        String libName = L.toString(1);
        L.register(libName, luaFunctions);

        return 1;
    }

    @Override
    public void onLoaded(CoronaRuntime runtime)
    {
        if (coronaRuntimeTaskDispatcher == null) {
            coronaRuntimeTaskDispatcher = new CoronaRuntimeTaskDispatcher(runtime);

            // set validation arrays
            validAdTypes.add(ADTYPE_BANNER);
            validAdTypes.add(ADTYPE_INTERSTITIAL);
            validAdTypes.add(ADTYPE_REWARDEDVIDEO);

            validBannerPositions.add(POS_TOP);
            validBannerPositions.add(POS_BOTTOM);
            validBannerPositions.add(POS_TOP_LEFT);
            validBannerPositions.add(POS_BOTTOM_LEFT);
            validBannerPositions.add(POS_TOP_RIGHT);
            validBannerPositions.add(POS_BOTTOM_RIGHT);

            // initialize the delegates
            bannerDelegate = new BannerDelegate();
            interstitialDelegate = new InterstitialDelegate();
            rewardedDelegate = new RewardedDelegate();
        }
    }

    @Override
    public void onStarted(CoronaRuntime runtime)
    {
    }

    @Override
    public void onSuspended(CoronaRuntime runtime)
    {
    }

    @Override
    public void onResumed(CoronaRuntime runtime)
    {
    }

    @Override
    public void onExiting(CoronaRuntime runtime)
    {
        // release all objects
        kidozObjects.clear();
        validBannerPositions.clear();
        validAdTypes.clear();

        currentInterstitialAd = null;
        currentRewardedAd = null;
        currentBannerView = null;

        bannerDelegate = null;
        interstitialDelegate = null;
        rewardedDelegate = null;

        CoronaLua.deleteRef(runtime.getLuaState(), coronaListener);
        coronaListener = CoronaLua.REFNIL;
        coronaRuntimeTaskDispatcher = null;
    }

    // -------------------------------------------------------------------
    // helper functions
    // -------------------------------------------------------------------

    private void logMsg(String msgType, String errorMsg)
    {
        String functionID = functionSignature;
        if (!functionID.isEmpty()) {
            functionID += ", ";
        }

        Log.i(CORONA_TAG, msgType + functionID + errorMsg);
    }

    private boolean isSDKInitialized()
    {
        if (coronaListener == CoronaLua.REFNIL) {
            logMsg(ERROR_MSG, "kidoz.init() must be called before calling other API functions.");
            return false;
        }

        return true;
    }

    private void dispatchLuaEvent(final Map<String, Object> event)
    {
        if (coronaRuntimeTaskDispatcher != null) {
            coronaRuntimeTaskDispatcher.send(new CoronaRuntimeTask() {
                public void executeUsing(CoronaRuntime runtime) {
                    try {
                        LuaState L = runtime.getLuaState();
                        CoronaLua.newEvent(L, EVENT_NAME);
                        boolean hasErrorKey = false;

                        for (String key: event.keySet()) {
                            CoronaLua.pushValue(L, event.get(key));
                            L.setField(-2, key);

                            if (! hasErrorKey) {
                                hasErrorKey = key.equals(CoronaLuaEvent.ISERROR_KEY);
                            }
                        }

                        if (! hasErrorKey) {
                            L.pushBoolean(false);
                            L.setField(-2, CoronaLuaEvent.ISERROR_KEY);
                        }

                        L.pushString(PROVIDER_NAME);
                        L.setField(-2, CoronaLuaEvent.PROVIDER_KEY);

                        CoronaLua.dispatchEvent(L, coronaListener, 0);
                    }
                    catch (Exception ex) {
                        ex.printStackTrace();
                    }
                }
            });
        }
    }

    private class BeaconListener implements JavaFunction
    {
        @Override
        public int invoke(LuaState L)
        {
            return 0;
        }
    }

    private void sendToBeacon(final String eventType, final String placementID)
    {
        final CoronaActivity coronaActivity = CoronaEnvironment.getCoronaActivity();

        if (coronaActivity != null) {
            Runnable runnableActivity = new Runnable() {
                public void run() {
                    CoronaBeacon.sendDeviceDataToBeacon(coronaRuntimeTaskDispatcher, PLUGIN_NAME, PLUGIN_VERSION, eventType, placementID, new BeaconListener());
                }
            };

            coronaActivity.runOnUiThread(runnableActivity);
        }
    }

    // -------------------------------------------------------------------
    // Plugin implementation
    // -------------------------------------------------------------------

    @SuppressWarnings("unused")
    private class Init implements NamedJavaFunction
    {
        @Override
        public String getName()
        {
            return "init";
        }

        @Override
        public int invoke(LuaState luaState)
        {
            String publisherID = null;
            String securityToken = null;

            functionSignature = "kidoz.init(listener, options)";

            if (coronaListener != CoronaLua.REFNIL) {
                return 0;
            }

            int nargs = luaState.getTop();
            if (nargs != 2) {
                logMsg(ERROR_MSG, "Expected 2 arguments, got: " + nargs);
                return 0;
            }

            if (CoronaLua.isListener(luaState, 1, PROVIDER_NAME)) {
                coronaListener = CoronaLua.newRef(luaState, 1);
            }
            else {
                logMsg(ERROR_MSG, "listener expected, got: " + luaState.typeName(1));
                return 0;
            }

            if (luaState.type(2) == LuaType.TABLE) {
                for (luaState.pushNil(); luaState.next(2); luaState.pop(1)) {
                    String key = luaState.toString(-2);

                    if (key.equals("publisherID")) {
                        if (luaState.type(-1) == LuaType.STRING) {
                            publisherID = luaState.toString(-1);
                        }
                        else {
                            logMsg(ERROR_MSG, "options.publisherID, expected string got: " + luaState.typeName(-1));
                            return 0;
                        }
                    }
                    else if (key.equals("securityToken")) {
                        if (luaState.type(-1) == LuaType.STRING) {
                            securityToken = luaState.toString(-1);
                        }
                        else {
                            logMsg(ERROR_MSG, "options.securityToken, expected string got: " + luaState.typeName(-1));
                            return 0;
                        }
                    }
                    else {
                        logMsg(ERROR_MSG, "Invalid option '" + key + "'");
                        return 0;
                    }
                }
            }
            else {
                logMsg(ERROR_MSG, "options table expected, got " + luaState.typeName(2));
                return 0;
            }

            if (publisherID == null) {
                logMsg(ERROR_MSG, "options.publisherID required");
                return 0;
            }

            if (securityToken == null) {
                logMsg(ERROR_MSG, "options.securityToken required");
                return 0;
            }

            final CoronaActivity coronaActivity = CoronaEnvironment.getCoronaActivity();
            final String fPublisherID = publisherID;
            final String fSecurityToken = securityToken;

            if (coronaActivity != null) {
                coronaActivity.runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        Kidoz.initialize(coronaActivity, fPublisherID, fSecurityToken, new KidozInitializationListener() {
                            @Override
                            public void onInitSuccess() {
                                Map<String, Object> coronaEvent = new HashMap<>();
                                coronaEvent.put(EVENT_PHASE_KEY, PHASE_INIT);
                                dispatchLuaEvent(coronaEvent);
                            }

                            @Override
                            public void onInitError(KidozError error) {
                                Map<String, Object> coronaEvent = new HashMap<>();
                                coronaEvent.put(EVENT_PHASE_KEY, PHASE_FAILED);
                                coronaEvent.put(CoronaLuaEvent.ISERROR_KEY, true);
                                coronaEvent.put(CoronaLuaEvent.RESPONSE_KEY, error.getMessage());
                                dispatchLuaEvent(coronaEvent);
                            }
                        });

                        Log.i(CORONA_TAG, PLUGIN_NAME + ": " + PLUGIN_VERSION + " (SDK: " + PLUGIN_SDK_VERSION + ")");
                    }
                });
            }

            return 0;
        }
    }

    @SuppressWarnings("unused")
    private class Load implements NamedJavaFunction
    {
        @Override
        public String getName() {
            return "load";
        }

        @Override
        public int invoke(LuaState luaState)
        {
            String adType = null;
            String adPosition = null;

            functionSignature = "kidoz.load(adType, options)";

            if (!isSDKInitialized()) {
                return 0;
            }

            if (luaState.type(1) == LuaType.STRING) {
                adType = luaState.toString(1);
            }
            else {
                logMsg(ERROR_MSG, "adType, expected string, got: " + luaState.typeName(1));
                return 0;
            }

            int nargs = luaState.getTop();
            int minArgs = 1;
            int maxArgs = 2;

            if ((nargs < minArgs) || (nargs > maxArgs)) {
                if (minArgs == maxArgs) {
                    logMsg(ERROR_MSG, "Expected " + maxArgs + " argument(s), got " + nargs);
                }
                else {
                    logMsg(ERROR_MSG, "Expected " + minArgs + " to " + maxArgs + " arguments, got " + nargs);
                }
                return 0;
            }

            if (! luaState.isNoneOrNil(2)) {
                if (luaState.type(2) != LuaType.TABLE) {
                    logMsg(ERROR_MSG, "Options table expected, got: " + luaState.typeName(2));
                    return 0;
                }
            }

            if (! validAdTypes.contains(adType)) {
                logMsg(ERROR_MSG, "Invalid adType: '" + adType + "'");
                return 0;
            }

            final CoronaActivity coronaActivity = CoronaEnvironment.getCoronaActivity();
            if (coronaActivity == null) {
                return 0;
            }

            final String fAdType = adType;

            // Banner
            if (adType.equals(ADTYPE_BANNER)) {
                if (! luaState.isNoneOrNil(2)) {
                    for (luaState.pushNil(); luaState.next(2); luaState.pop(1)) {
                        String key = luaState.toString(-2);

                        if (key.equals("adPosition")) {
                            if (luaState.type(-1) == LuaType.STRING) {
                                adPosition = luaState.toString(-1);
                            }
                            else {
                                logMsg(ERROR_MSG, "options.adPosition (string) expected, got: " + luaState.typeName(-1));
                                return 0;
                            }
                        }
                        else {
                            logMsg(ERROR_MSG, "Invalid option '" + key + "' for adType '" + adType + "'");
                            return 0;
                        }
                    }
                }

                if (adPosition == null) {
                    adPosition = POS_BOTTOM;
                }

                if (! validBannerPositions.contains(adPosition)) {
                    logMsg(ERROR_MSG, "Invalid adPosition: '" + adPosition + "' for adType '" + adType + "'");
                    return 0;
                }

                final KidozBannerView.Position bannerPosition;

                switch (adPosition) {
                    case POS_TOP:
                        bannerPosition = KidozBannerView.Position.TOP_CENTER;
                        break;
                    case POS_TOP_LEFT:
                        bannerPosition = KidozBannerView.Position.TOP_LEFT;
                        break;
                    case POS_TOP_RIGHT:
                        bannerPosition = KidozBannerView.Position.TOP_RIGHT;
                        break;
                    case POS_BOTTOM:
                        bannerPosition = KidozBannerView.Position.BOTTOM_CENTER;
                        break;
                    case POS_BOTTOM_LEFT:
                        bannerPosition = KidozBannerView.Position.BOTTOM_LEFT;
                        break;
                    case POS_BOTTOM_RIGHT:
                        bannerPosition = KidozBannerView.Position.BOTTOM_RIGHT;
                        break;
                    default:
                        bannerPosition = KidozBannerView.Position.BOTTOM_CENTER;
                        break;
                }

                coronaActivity.runOnUiThread(new Runnable() {
                    public void run() {
                        if (currentBannerView == null) {
                            currentBannerView = new KidozBannerView(coronaActivity);
                            currentBannerView.setBannerCallback(bannerDelegate);
                            currentBannerView.setAutoShow(false);
                            kidozObjects.put(ADTYPE_BANNER, new KidozAdInfo(currentBannerView));
                        }

                        currentBannerView.setBannerPosition(bannerPosition);
                        currentBannerView.load();

                        sendToBeacon(CoronaBeacon.REQUEST, ADTYPE_BANNER);
                    }
                });
            }
            // Interstitial
            else if (adType.equals(ADTYPE_INTERSTITIAL)) {
                coronaActivity.runOnUiThread(new Runnable() {
                    public void run() {
                        KidozInterstitialAd.load(coronaActivity, interstitialDelegate);

                        if (!kidozObjects.containsKey(ADTYPE_INTERSTITIAL)) {
                            kidozObjects.put(ADTYPE_INTERSTITIAL, new KidozAdInfo(null));
                        }

                        sendToBeacon(CoronaBeacon.REQUEST, ADTYPE_INTERSTITIAL);
                    }
                });
            }
            // Rewarded Video
            else if (adType.equals(ADTYPE_REWARDEDVIDEO)) {
                coronaActivity.runOnUiThread(new Runnable() {
                    public void run() {
                        KidozRewardedAd.load(coronaActivity, rewardedDelegate);

                        if (!kidozObjects.containsKey(ADTYPE_REWARDEDVIDEO)) {
                            kidozObjects.put(ADTYPE_REWARDEDVIDEO, new KidozAdInfo(null));
                        }

                        sendToBeacon(CoronaBeacon.REQUEST, ADTYPE_REWARDEDVIDEO);
                    }
                });
            }

            return 0;
        }
    }

    @SuppressWarnings("unused")
    private class IsLoaded implements NamedJavaFunction
    {
        @Override
        public String getName() {
            return "isLoaded";
        }

        @Override
        public int invoke(LuaState luaState)
        {
            final String adType;
            functionSignature = "kidoz.isLoaded(adType)";

            if (! isSDKInitialized()) {
                return 0;
            }

            int nargs = luaState.getTop();
            if (nargs != 1) {
                logMsg(ERROR_MSG, "Expected 1 argument, got: " + nargs);
                return 0;
            }

            if (luaState.type(1) == LuaType.STRING) {
                adType = luaState.toString(1);

                if (! validAdTypes.contains(adType)) {
                    logMsg(ERROR_MSG, "Invalid adType: '" + adType + "'");
                    return 0;
                }
            }
            else {
                logMsg(ERROR_MSG, "adType, expected string, got: " + luaState.typeName(1));
                return 0;
            }

            KidozAdInfo adObject = (KidozAdInfo)kidozObjects.get(adType);
            boolean isReady = (adObject != null) && adObject.isLoaded;
            luaState.pushBoolean(isReady);

            return 1;
        }
    }

    @SuppressWarnings("unused")
    private class Show implements NamedJavaFunction
    {
        @Override
        public String getName()
        {
            return "show";
        }

        @Override
        public int invoke(LuaState luaState)
        {
            final String adType;

            functionSignature = "kidoz.show(adType)";

            if (! isSDKInitialized()) {
                return 0;
            }

            int nargs = luaState.getTop();
            if (nargs != 1) {
                logMsg(ERROR_MSG, "Expected 1 argument, got: " + nargs);
                return 0;
            }

            if (luaState.type(1) == LuaType.STRING) {
                adType = luaState.toString(1);
            }
            else {
                logMsg(ERROR_MSG, "adType, expected string, got: " + luaState.typeName(1));
                return 0;
            }

            if (! validAdTypes.contains(adType)) {
                logMsg(ERROR_MSG, "Invalid adType: '" + adType + "'");
                return 0;
            }

            final CoronaActivity coronaActivity = CoronaEnvironment.getCoronaActivity();

            if (coronaActivity != null) {
                coronaActivity.runOnUiThread(new Runnable() {
                    public void run() {
                        KidozAdInfo adObject = (KidozAdInfo)kidozObjects.get(adType);

                        if ((adObject == null) || (! adObject.isLoaded)) {
                            logMsg(WARNING_MSG, "adType '" + adType + "' not loaded");
                        }
                        else {
                            if (adType.equals(ADTYPE_BANNER)) {
                                if (currentBannerView != null) {
                                    currentBannerView.show();
                                }
                            }
                            else if (adType.equals(ADTYPE_INTERSTITIAL)) {
                                if (currentInterstitialAd != null) {
                                    currentInterstitialAd.show();
                                }
                            }
                            else if (adType.equals(ADTYPE_REWARDEDVIDEO)) {
                                if (currentRewardedAd != null) {
                                    currentRewardedAd.show();
                                }
                            }
                        }
                    }
                });
            }

            return 0;
        }
    }

    @SuppressWarnings("unused")
    private class Hide implements NamedJavaFunction
    {
        @Override
        public String getName()
        {
            return "hide";
        }

        @Override
        public int invoke(LuaState luaState)
        {
            final String adType;

            functionSignature = "kidoz.hide(adType)";

            if (! isSDKInitialized()) {
                return 0;
            }

            int nargs = luaState.getTop();
            if (nargs != 1) {
                logMsg(ERROR_MSG, "Expected 1 argument, got: " + nargs);
                return 0;
            }

            if (luaState.type(1) == LuaType.STRING) {
                adType = luaState.toString(1);
            }
            else {
                logMsg(ERROR_MSG, "adType, expected string, got: " + luaState.typeName(1));
                return 0;
            }

            if (! validAdTypes.contains(adType)) {
                logMsg(ERROR_MSG, "Invalid adType: '" + adType + "'");
                return 0;
            }

            if (adType.equals(ADTYPE_INTERSTITIAL) || adType.equals(ADTYPE_REWARDEDVIDEO)) {
                logMsg(ERROR_MSG, "adType '" + adType + "' cannot be hidden");
                return 0;
            }

            final CoronaActivity coronaActivity = CoronaEnvironment.getCoronaActivity();

            if (coronaActivity != null) {
                coronaActivity.runOnUiThread(new Runnable() {
                    public void run() {
                        if (adType.equals(ADTYPE_BANNER)) {
                            if (currentBannerView != null) {
                                currentBannerView.close();
                            }
                        }
                    }
                });
            }

            return 0;
        }
    }

    // -------------------------------------------------------------------
    // Delegates
    // -------------------------------------------------------------------

    private class BannerDelegate implements KidozBannerAdCallback
    {
        @Override
        public void onAdLoaded()
        {
            if (! kidozObjects.isEmpty()) {
                KidozAdInfo adObject = (KidozAdInfo)kidozObjects.get(ADTYPE_BANNER);
                if (adObject != null) {
                    adObject.isLoaded = true;
                }

                Map<String, Object> coronaEvent = new HashMap<>();
                coronaEvent.put(EVENT_PHASE_KEY, PHASE_LOADED);
                coronaEvent.put(EVENT_TYPE_KEY, ADTYPE_BANNER);
                dispatchLuaEvent(coronaEvent);
            }
        }

        @Override
        public void onAdFailedToLoad(KidozError error)
        {
            if (! kidozObjects.isEmpty()) {
                KidozAdInfo adObject = (KidozAdInfo)kidozObjects.get(ADTYPE_BANNER);
                if (adObject != null) {
                    adObject.isLoaded = false;
                }

                Map<String, Object> coronaEvent = new HashMap<>();
                coronaEvent.put(EVENT_PHASE_KEY, PHASE_FAILED);
                coronaEvent.put(EVENT_TYPE_KEY, ADTYPE_BANNER);
                coronaEvent.put(CoronaLuaEvent.ISERROR_KEY, true);
                coronaEvent.put(CoronaLuaEvent.RESPONSE_KEY, error.getMessage());
                dispatchLuaEvent(coronaEvent);
            }
        }

        @Override
        public void onAdShown()
        {
            Map<String, Object> coronaEvent = new HashMap<>();
            coronaEvent.put(EVENT_PHASE_KEY, PHASE_DISPLAYED);
            coronaEvent.put(EVENT_TYPE_KEY, ADTYPE_BANNER);
            dispatchLuaEvent(coronaEvent);

            sendToBeacon(CoronaBeacon.IMPRESSION, ADTYPE_BANNER);
        }

        @Override
        public void onAdFailedToShow(KidozError error)
        {
            Map<String, Object> coronaEvent = new HashMap<>();
            coronaEvent.put(EVENT_PHASE_KEY, PHASE_FAILED);
            coronaEvent.put(EVENT_TYPE_KEY, ADTYPE_BANNER);
            coronaEvent.put(CoronaLuaEvent.ISERROR_KEY, true);
            coronaEvent.put(CoronaLuaEvent.RESPONSE_KEY, error.getMessage());
            dispatchLuaEvent(coronaEvent);
        }

        @Override
        public void onAdImpression()
        {
            // Optional: can dispatch additional event if needed
        }

        @Override
        public void onAdClosed()
        {
            Map<String, Object> coronaEvent = new HashMap<>();
            coronaEvent.put(EVENT_PHASE_KEY, PHASE_CLOSED);
            coronaEvent.put(EVENT_TYPE_KEY, ADTYPE_BANNER);
            dispatchLuaEvent(coronaEvent);
        }
    }

    private class InterstitialDelegate implements KidozInterstitialAdCallback
    {
        @Override
        public void onAdLoaded(KidozInterstitialAd ad)
        {
            currentInterstitialAd = ad;

            if (! kidozObjects.isEmpty()) {
                KidozAdInfo adObject = (KidozAdInfo)kidozObjects.get(ADTYPE_INTERSTITIAL);
                if (adObject != null) {
                    adObject.isLoaded = true;
                    adObject.adInstance = ad;
                }

                Map<String, Object> coronaEvent = new HashMap<>();
                coronaEvent.put(EVENT_PHASE_KEY, PHASE_LOADED);
                coronaEvent.put(EVENT_TYPE_KEY, ADTYPE_INTERSTITIAL);
                dispatchLuaEvent(coronaEvent);
            }
        }

        @Override
        public void onAdFailedToLoad(KidozError error)
        {
            if (! kidozObjects.isEmpty()) {
                KidozAdInfo adObject = (KidozAdInfo)kidozObjects.get(ADTYPE_INTERSTITIAL);
                if (adObject != null) {
                    adObject.isLoaded = false;
                }

                Map<String, Object> coronaEvent = new HashMap<>();
                coronaEvent.put(EVENT_PHASE_KEY, PHASE_FAILED);
                coronaEvent.put(EVENT_TYPE_KEY, ADTYPE_INTERSTITIAL);
                coronaEvent.put(CoronaLuaEvent.ISERROR_KEY, true);
                coronaEvent.put(CoronaLuaEvent.RESPONSE_KEY, error.getMessage());
                dispatchLuaEvent(coronaEvent);
            }
        }

        @Override
        public void onAdShown(KidozInterstitialAd ad)
        {
            if (! kidozObjects.isEmpty()) {
                KidozAdInfo adObject = (KidozAdInfo)kidozObjects.get(ADTYPE_INTERSTITIAL);
                if (adObject != null) {
                    adObject.isLoaded = false;
                }

                Map<String, Object> coronaEvent = new HashMap<>();
                coronaEvent.put(EVENT_PHASE_KEY, PHASE_DISPLAYED);
                coronaEvent.put(EVENT_TYPE_KEY, ADTYPE_INTERSTITIAL);
                dispatchLuaEvent(coronaEvent);

                sendToBeacon(CoronaBeacon.IMPRESSION, ADTYPE_INTERSTITIAL);
            }
        }

        @Override
        public void onAdFailedToShow(KidozInterstitialAd ad, KidozError error)
        {
            Map<String, Object> coronaEvent = new HashMap<>();
            coronaEvent.put(EVENT_PHASE_KEY, PHASE_FAILED);
            coronaEvent.put(EVENT_TYPE_KEY, ADTYPE_INTERSTITIAL);
            coronaEvent.put(CoronaLuaEvent.ISERROR_KEY, true);
            coronaEvent.put(CoronaLuaEvent.RESPONSE_KEY, error.getMessage());
            dispatchLuaEvent(coronaEvent);
        }

        @Override
        public void onAdImpression(KidozInterstitialAd ad)
        {
            // Optional: can dispatch additional event if needed
        }

        @Override
        public void onAdClosed(KidozInterstitialAd ad)
        {
            currentInterstitialAd = null;

            Map<String, Object> coronaEvent = new HashMap<>();
            coronaEvent.put(EVENT_PHASE_KEY, PHASE_CLOSED);
            coronaEvent.put(EVENT_TYPE_KEY, ADTYPE_INTERSTITIAL);
            dispatchLuaEvent(coronaEvent);
        }
    }

    private class RewardedDelegate implements KidozRewardedAdCallback
    {
        @Override
        public void onAdLoaded(KidozRewardedAd ad)
        {
            currentRewardedAd = ad;

            if (! kidozObjects.isEmpty()) {
                KidozAdInfo adObject = (KidozAdInfo)kidozObjects.get(ADTYPE_REWARDEDVIDEO);
                if (adObject != null) {
                    adObject.isLoaded = true;
                    adObject.adInstance = ad;
                }

                Map<String, Object> coronaEvent = new HashMap<>();
                coronaEvent.put(EVENT_PHASE_KEY, PHASE_LOADED);
                coronaEvent.put(EVENT_TYPE_KEY, ADTYPE_REWARDEDVIDEO);
                dispatchLuaEvent(coronaEvent);
            }
        }

        @Override
        public void onAdFailedToLoad(KidozError error)
        {
            if (! kidozObjects.isEmpty()) {
                KidozAdInfo adObject = (KidozAdInfo)kidozObjects.get(ADTYPE_REWARDEDVIDEO);
                if (adObject != null) {
                    adObject.isLoaded = false;
                }

                Map<String, Object> coronaEvent = new HashMap<>();
                coronaEvent.put(EVENT_PHASE_KEY, PHASE_FAILED);
                coronaEvent.put(EVENT_TYPE_KEY, ADTYPE_REWARDEDVIDEO);
                coronaEvent.put(CoronaLuaEvent.ISERROR_KEY, true);
                coronaEvent.put(CoronaLuaEvent.RESPONSE_KEY, error.getMessage());
                dispatchLuaEvent(coronaEvent);
            }
        }

        @Override
        public void onAdShown(KidozRewardedAd ad)
        {
            if (! kidozObjects.isEmpty()) {
                KidozAdInfo adObject = (KidozAdInfo)kidozObjects.get(ADTYPE_REWARDEDVIDEO);
                if (adObject != null) {
                    adObject.isLoaded = false;
                }

                Map<String, Object> coronaEvent = new HashMap<>();
                coronaEvent.put(EVENT_PHASE_KEY, PHASE_DISPLAYED);
                coronaEvent.put(EVENT_TYPE_KEY, ADTYPE_REWARDEDVIDEO);
                dispatchLuaEvent(coronaEvent);

                sendToBeacon(CoronaBeacon.IMPRESSION, ADTYPE_REWARDEDVIDEO);
            }
        }

        @Override
        public void onAdFailedToShow(KidozRewardedAd ad, KidozError error)
        {
            Map<String, Object> coronaEvent = new HashMap<>();
            coronaEvent.put(EVENT_PHASE_KEY, PHASE_FAILED);
            coronaEvent.put(EVENT_TYPE_KEY, ADTYPE_REWARDEDVIDEO);
            coronaEvent.put(CoronaLuaEvent.ISERROR_KEY, true);
            coronaEvent.put(CoronaLuaEvent.RESPONSE_KEY, error.getMessage());
            dispatchLuaEvent(coronaEvent);
        }

        @Override
        public void onAdImpression(KidozRewardedAd ad)
        {
            // Optional: can dispatch additional event if needed
        }

        @Override
        public void onRewardReceived(KidozRewardedAd ad)
        {
            Map<String, Object> coronaEvent = new HashMap<>();
            coronaEvent.put(EVENT_PHASE_KEY, PHASE_REWARD);
            coronaEvent.put(EVENT_TYPE_KEY, ADTYPE_REWARDEDVIDEO);
            dispatchLuaEvent(coronaEvent);
        }

        @Override
        public void onAdClosed(KidozRewardedAd ad)
        {
            currentRewardedAd = null;

            Map<String, Object> coronaEvent = new HashMap<>();
            coronaEvent.put(EVENT_PHASE_KEY, PHASE_CLOSED);
            coronaEvent.put(EVENT_TYPE_KEY, ADTYPE_REWARDEDVIDEO);
            dispatchLuaEvent(coronaEvent);
        }
    }
}