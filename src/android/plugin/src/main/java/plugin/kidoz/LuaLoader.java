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

import com.kidoz.sdk.api.ui_views.new_kidoz_banner.BANNER_POSITION;
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
import com.kidoz.sdk.api.KidozSDK;
//import com.kidoz.sdk.api.FlexiView;
import com.kidoz.sdk.api.PanelView;
import com.kidoz.sdk.api.KidozInterstitial;
import com.kidoz.sdk.api.ui_views.interstitial.BaseInterstitial;
import com.kidoz.sdk.api.interfaces.FlexiViewListener;
import com.kidoz.sdk.api.interfaces.IOnPanelViewEventListener;
import com.kidoz.sdk.api.interfaces.KidozPlayerListener;
import com.kidoz.sdk.api.interfaces.SDKEventListener;
import com.kidoz.sdk.api.ui_views.new_kidoz_banner.KidozBannerView;
import com.kidoz.sdk.api.ui_views.kidoz_banner.KidozBannerListener;
import com.kidoz.sdk.api.ui_views.panel_view.HANDLE_POSITION;
import com.kidoz.sdk.api.ui_views.flexi_view.FLEXI_POSITION;
import com.kidoz.sdk.api.ui_views.panel_view.PANEL_TYPE;
//import com.kidoz.sdk.api.ui_views.video_unit.VideoUnit;
import com.kidoz.sdk.api.general.utils.ConstantDef;

/**
 * Implements the Lua interface for the Kidoz Plugin.
 * <p/>
 * Only one instance of this class will be created by Corona for the lifetime of the application.
 * This instance will be re-used for every new Corona activity that gets created.
 */
public class LuaLoader implements JavaFunction, CoronaRuntimeListener
{
  private static final String PLUGIN_NAME        = "plugin.kidoz";
  private static final String PLUGIN_VERSION     = "2.1";
  private static final String PLUGIN_SDK_VERSION = KidozSDK.getSDKVersion();

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
  private static final List<String> validPanelViewPositions = new ArrayList<>();      // these are used to
  private static final List<String> validTBPositions = new ArrayList<>();             // positions during load()
  private static final List<String> validLRPositions = new ArrayList<>();             //
  private static final List<String> validFlexiViewPositions = new ArrayList<>();      //
  private static final List<String> validBannerPositions = new ArrayList<>();         //
  private static final List<String> validAdTypes = new ArrayList<>();                 //

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
//  private static FlexiDelegate flexiDelegate;
  private static PanelDelegate panelDelegate;
  private static BannerDelegate bannerDelegate;
  private static PlayerDelegate playerDelegate;
  private static InterstitialDelegate interstitialDelegate;
  private static RewardedInterstitialDelegate rewardedInterstitialDelegate;
  private static RewardedVideoDelegate rewardedVideoDelegate;
//  private static VideoUnitDelegate videoUnitDelegate;

  private static int coronaListener = CoronaLua.REFNIL;
  private static CoronaRuntimeTaskDispatcher coronaRuntimeTaskDispatcher = null;

  private static String functionSignature = "";                               // used in error reporting functions
  private static final Map<String, Object> kidozObjects = new HashMap<>();    // keep track of loaded ad objects (panel, feed etc)

  // class to hold ad instance information
  private class KidozAdInfo
  {
    private Object adInstance;                       // ad object
    private boolean isLoaded;                        // true when ad is loaded and ready for display
    private boolean hasUIElement;                    // true when ad unit has a button or handle
    private boolean isHiddenBySystem;                // flag to tell if ad was automatically hidden

    KidozAdInfo(Object adInstance) {
      this.adInstance = adInstance;
      hasUIElement = false;
      isLoaded = false;
      isHiddenBySystem = false;
    }
  }

  // -------------------------------------------------------------------
  // Plugin lifecycle events
  // -------------------------------------------------------------------

  /**
   * <p/>
   * Note that a new LuaLoader instance will not be created for every CoronaActivity instance.
   * That is, only one instance of this class will be created for the lifetime of the
   * application process.
   * This gives a plugin the option to do operations in the background while the CoronaActivity
   * is destroyed.
   */
  @SuppressWarnings("unused")
  public LuaLoader()
  {
    // Set up this plugin to listen for Corona runtime events to be received by methods
    // onLoaded(), onStarted(), onSuspended(), onResumed(), and onExiting().
    CoronaEnvironment.addRuntimeListener(this);
  }

  /**
   * Called when this plugin is being loaded via the Lua require() function.
   * <p/>
   * Note that this method will be called every time a new CoronaActivity has been launched.
   * This means that you'll need to re-initialize this plugin here.
   * <p/>
   * Warning! This method is not called on the main UI thread.
   *
   * @param L Reference to the Lua state that the require() function was called from.
   * @return Returns the number of values that the require() function will return.
   * <p/>
   * Expected to return 1, the library that the require() function is loading.
   */
  @Override
  public int invoke(LuaState L)
  {
    // Register this plugin into Lua with the following functions.
    NamedJavaFunction[] luaFunctions = new NamedJavaFunction[] {
      new Init(),
      new Load(),
      new IsLoaded(),
      new Show(),
      new Hide()
    };
    String libName = L.toString(1);
    L.register(libName, luaFunctions);

    // Returning 1 indicates that the Lua require() function will return the above Lua
    return 1;
  }

  /**
   * Called after the Corona runtime has been created and just before executing the "main.lua"
   * file.
   * <p/>
   * Warning! This method is not called on the main thread.
   *
   * @param runtime Reference to the CoronaRuntime object that has just been loaded/initialized.
   *                Provides a LuaState object that allows the application to extend the Lua API.
   */
  @Override
  public void onLoaded(CoronaRuntime runtime)
  {
    // Note that this method will not be called the first time a Corona activity has been
    // launched.
    // This is because this listener cannot be added to the CoronaEnvironment until after
    // this plugin has been required-in by Lua, which occurs after the onLoaded() event.
    // However, this method will be called when a 2nd Corona activity has been created.

    if (coronaRuntimeTaskDispatcher == null) {
      coronaRuntimeTaskDispatcher = new CoronaRuntimeTaskDispatcher(runtime);

      // set validation arrays
      validAdTypes.add(ADTYPE_PANELVIEW);
//      validAdTypes.add(ADTYPE_FLEXIVIEW);
      validAdTypes.add(ADTYPE_BANNER);
      validAdTypes.add(ADTYPE_INTERSTITIAL);
      validAdTypes.add(ADTYPE_REWARDEDVIDEO);
//      validAdTypes.add(ADTYPE_VIDEOUNIT);

      validPanelViewPositions.add(POS_TOP);
      validPanelViewPositions.add(POS_BOTTOM);
      validPanelViewPositions.add(POS_LEFT);
      validPanelViewPositions.add(POS_RIGHT);

      validFlexiViewPositions.add(POS_TOP);
      validFlexiViewPositions.add(POS_BOTTOM);
      validFlexiViewPositions.add(POS_LEFT);
      validFlexiViewPositions.add(POS_RIGHT);
      validFlexiViewPositions.add(POS_CENTER);

      validLRPositions.add(POS_LEFT);
      validLRPositions.add(POS_CENTER);
      validLRPositions.add(POS_RIGHT);
      validLRPositions.add(POS_NONE);

      validTBPositions.add(POS_TOP);
      validTBPositions.add(POS_CENTER);
      validTBPositions.add(POS_BOTTOM);
      validTBPositions.add(POS_NONE);

      validBannerPositions.add(POS_TOP);
      validBannerPositions.add(POS_BOTTOM);
      validBannerPositions.add(POS_TOP_LEFT);
      validBannerPositions.add(POS_BOTTOM_LEFT);
      validBannerPositions.add(POS_TOP_RIGHT);
      validBannerPositions.add(POS_BOTTOM_RIGHT);

      // initialize the delegates
//      flexiDelegate = new FlexiDelegate();
      panelDelegate = new PanelDelegate();
      playerDelegate = new PlayerDelegate();
      bannerDelegate = new BannerDelegate();
      interstitialDelegate = new InterstitialDelegate();
      rewardedVideoDelegate = new RewardedVideoDelegate();
      rewardedInterstitialDelegate = new RewardedInterstitialDelegate();
//      videoUnitDelegate = new VideoUnitDelegate();
    }
  }

  /**
   * Called just after the Corona runtime has executed the "main.lua" file.
   * <p/>
   * Warning! This method is not called on the main thread.
   *
   * @param runtime Reference to the CoronaRuntime object that has just been started.
   */
  @Override
  public void onStarted(CoronaRuntime runtime)
  {
  }

  /**
   * Called just after the Corona runtime has been suspended which pauses all rendering, audio,
   * timers,
   * and other Corona related operations. This can happen when another Android activity (ie:
   * window) has
   * been displayed, when the screen has been powered off, or when the screen lock is shown.
   * <p/>
   * Warning! This method is not called on the main thread.
   *
   * @param runtime Reference to the CoronaRuntime object that has just been suspended.
   */
  @Override
  public void onSuspended(CoronaRuntime runtime)
  {
  }

  /**
   * Called just after the Corona runtime has been resumed after a suspend.
   * <p/>
   * Warning! This method is not called on the main thread.
   *
   * @param runtime Reference to the CoronaRuntime object that has just been resumed.
   */
  @Override
  public void onResumed(CoronaRuntime runtime)
  {
  }

  /**
   * Called just before the Corona runtime terminates.
   * <p/>
   * This happens when the Corona activity is being destroyed which happens when the user
   * presses the Back button
   * on the activity, when the native.requestExit() method is called in Lua, or when the
   * activity's finish()
   * method is called. This does not mean that the application is exiting.
   * <p/>
   * Warning! This method is not called on the main thread.
   *
   * @param runtime Reference to the CoronaRuntime object that is being terminated.
   */
  @Override
  public void onExiting(CoronaRuntime runtime)
  {
    // release event listeners
    if (kidozObjects.containsKey(ADTYPE_PANELVIEW)) {
      KidozAdInfo adInfo = (KidozAdInfo)kidozObjects.get(ADTYPE_PANELVIEW);
      PanelView savedPanel = (PanelView)adInfo.adInstance;
      // remove listeners
      savedPanel.setKidozPlayerListener(null);
      savedPanel.setOnPanelViewEventListener(null);
      // reset instance
      adInfo.adInstance = null;
    }

    // release event listeners
    if (kidozObjects.containsKey(ADTYPE_BANNER)) {
      KidozAdInfo adInfo = (KidozAdInfo)kidozObjects.get(ADTYPE_BANNER);
      KidozBannerView savedBanner = (KidozBannerView)adInfo.adInstance;
      // remove listeners
      savedBanner.setKidozBannerListener(null);
      // remove ad object
      adInfo.adInstance = null;
    }

    // release event listeners
//    if (kidozObjects.containsKey(ADTYPE_FLEXIVIEW)) {
//      KidozAdInfo adInfo = (KidozAdInfo)kidozObjects.get(ADTYPE_FLEXIVIEW);
//      FlexiView savedFlexi = (FlexiView)adInfo.adInstance;
//      // remove listeners and views
//      savedFlexi.setOnFlexiViewEventListener(null);
//      savedFlexi.setKidozPlayerListener(null);
//      // remove ad object
//      adInfo.adInstance = null;
//    }

    // release event listeners
    if (kidozObjects.containsKey(ADTYPE_INTERSTITIAL)) {
      KidozAdInfo adInfo = (KidozAdInfo)kidozObjects.get(ADTYPE_INTERSTITIAL);
      // remove listener
      ((KidozInterstitial)adInfo.adInstance).setOnInterstitialEventListener(null);
      // remove ad object
      adInfo.adInstance = null;
    }

    // release event listeners
    if (kidozObjects.containsKey(ADTYPE_REWARDEDVIDEO)) {
      KidozAdInfo adInfo = (KidozAdInfo)kidozObjects.get(ADTYPE_REWARDEDVIDEO);
      // remove listener
      ((KidozInterstitial)adInfo.adInstance).setOnInterstitialEventListener(null);
      ((KidozInterstitial)adInfo.adInstance).setOnInterstitialRewardedEventListener(null);
      // remove ad object
      adInfo.adInstance = null;
    }

    // release event listeners
//    if (kidozObjects.containsKey(ADTYPE_VIDEOUNIT)) {
//      KidozAdInfo adInfo = (KidozAdInfo)kidozObjects.get(ADTYPE_VIDEOUNIT);
//      // remove listener
//      ((VideoUnit)adInfo.adInstance).setVideoUnitListener(null);
//      // remove ad object
//      adInfo.adInstance = null;
//    }

    // release all objects
    kidozObjects.clear();
    validPanelViewPositions.clear();
    validTBPositions.clear();
    validLRPositions.clear();
    validFlexiViewPositions.clear();
    validBannerPositions.clear();
    validAdTypes.clear();

//    flexiDelegate = null;
    panelDelegate = null;
    bannerDelegate = null;
    playerDelegate = null;
    interstitialDelegate = null;
    rewardedVideoDelegate = null;
    rewardedInterstitialDelegate = null;

    CoronaLua.deleteRef(runtime.getLuaState(), coronaListener);
    coronaListener = CoronaLua.REFNIL;
    coronaRuntimeTaskDispatcher = null;
  }

  // -------------------------------------------------------------------
  // helper functions
  // -------------------------------------------------------------------

  // log message to console
  private void logMsg(String msgType, String errorMsg)
  {
    String functionID = functionSignature;
    if (!functionID.isEmpty()) {
      functionID += ", ";
    }

    Log.i(CORONA_TAG, msgType + functionID + errorMsg);
  }

  // return true if SDK is properly initialized
  private boolean isSDKInitialized()
  {
    if (coronaListener == CoronaLua.REFNIL) {
      logMsg(ERROR_MSG, "kidoz.init() must be called before calling other API functions.");
      return false;
    }

    return true;
  }

  // dispatch a Lua event to our callback (dynamic handling of properties through map)
  private void dispatchLuaEvent(final Map<String, Object> event)
  {
    if (coronaRuntimeTaskDispatcher != null) {
      coronaRuntimeTaskDispatcher.send(new CoronaRuntimeTask() {
        public void executeUsing(CoronaRuntime runtime) {
          try {
            LuaState L = runtime.getLuaState();
            CoronaLua.newEvent(L, EVENT_NAME);
            boolean hasErrorKey = false;

            // add event parameters from map
            for (String key: event.keySet()) {
              CoronaLua.pushValue(L, event.get(key));           // push value
              L.setField(-2, key);                              // push key

              if (! hasErrorKey) {
                hasErrorKey = key.equals(CoronaLuaEvent.ISERROR_KEY);
              }
            }

            // add error key if not in map
            if (! hasErrorKey) {
              L.pushBoolean(false);
              L.setField(-2, CoronaLuaEvent.ISERROR_KEY);
            }

            // add provider
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

  // Corona beacon listener
  private class BeaconListener implements JavaFunction
  {
    // This method is executed when the Lua function is called
    @Override
    public int invoke(LuaState L)
    {
      // NOP (Debugging purposes only)
      // Listener called but the function body should be empty for public release
      return 0;
    }
  }

  // Corona beacon wrapper
  private void
  sendToBeacon(final String eventType, final String placementID)
  {
    final CoronaActivity coronaActivity = CoronaEnvironment.getCoronaActivity();

    // ignore if invalid activity
    if (coronaActivity != null) {
      // Create a new runnable object to invoke our activity
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

  // [Lua] init(listener, options)
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

      // function to log in case of error
      functionSignature = "kidoz.init(listener, options)";

      // prevent init from being called twice
      if (coronaListener != CoronaLua.REFNIL) {
        return 0;
      }

      // check number of args
      int nargs = luaState.getTop();
      if (nargs != 2) {
        logMsg(ERROR_MSG, "Expected 2 arguments, got: " + nargs);
        return 0;
      }

      // Get the listener (required)
      if (CoronaLua.isListener(luaState, 1, PROVIDER_NAME)) {
        coronaListener = CoronaLua.newRef(luaState, 1);
      }
      else {
        logMsg(ERROR_MSG, "listener expected, got: " + luaState.typeName(1));
        return 0;
      }

      // check for options table (required)
      if (luaState.type(2) == LuaType.TABLE) {
        // traverse and verify all options
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
      else { // no options table
        logMsg(ERROR_MSG, "options table expected, got " + luaState.typeName(2));
        return 0;
      }

      // validate
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

      // Run the activity on the uiThread
      if (coronaActivity != null) {
        coronaActivity.runOnUiThread(new Runnable() {
          @Override
          public void run() {
            KidozSDK.setSDKListener(new KidozSDKDelegate());

            // tell Kidoz SDK we're using Corona before init
            ConstantDef.SDK_EXTENSION_TYPE = ConstantDef.EXTENSION_TYPE_CORONA;
            KidozSDK.initialize(coronaActivity, fPublisherID, fSecurityToken);

            // log plugin version to the console
            Log.i(CORONA_TAG, PLUGIN_NAME + ": " + PLUGIN_VERSION + " (SDK: " + PLUGIN_SDK_VERSION + ")");
          }
        });
      }

      return 0;
    }
  }

  // [Lua] load(adType, options)
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

      // function to log in case of error
      functionSignature = "kidoz.load(adType, options)";

      if (!isSDKInitialized()) {
        return 0;
      }

      // check for ad type (required)
      if (luaState.type(1) == LuaType.STRING) {
        adType = luaState.toString(1);
      }
      else {
        logMsg(ERROR_MSG, "adType, expected string, got: " + luaState.typeName(1));
        return 0;
      }

      // check number of arguments
      int nargs = luaState.getTop();
      int minArgs = 2;
      int maxArgs = 2;

      if (adType.equals(ADTYPE_INTERSTITIAL) || adType.equals(ADTYPE_REWARDEDVIDEO) || adType.equals(ADTYPE_VIDEOUNIT)) {
        minArgs = 1;
        maxArgs = 1;
      }
      else if (adType.equals(ADTYPE_BANNER)) {
        minArgs = 1;
        maxArgs = 2;
      }

      if ((nargs < minArgs) || (nargs > maxArgs)) {
        if (minArgs == maxArgs) {
          logMsg(ERROR_MSG, "Expected " + maxArgs + " argument(s), got " + nargs);
        }
        else {
          logMsg(ERROR_MSG, "Expected " + minArgs + " to " + maxArgs + " arguments, got " + nargs);
        }
        return 0;
      }

      // check for options table
      if (! luaState.isNoneOrNil(2)) {
        if (luaState.type(2) != LuaType.TABLE) {
          logMsg(ERROR_MSG, "Options table expected, got: " + luaState.typeName(2));
          return 0;
        }
      }

      // check to see if valid type
      if (! validAdTypes.contains(adType)) {
        logMsg(ERROR_MSG, "Invalid adType: '" + adType + "'");
        return 0;
      }

      // set up environment for custom positioning
      double contentWidth;
      double contentHeight;
      double actualContentWidth;
      double actualContentHeight;
      double offsetX;
      double offsetY;

      luaState.getGlobal("display");                                    // push 'display'

      // get corona display content dimensions
      luaState.getField(-1, "contentWidth");                            // get result
      contentWidth = luaState.toNumber(-1);                             // save width
      luaState.pop(1);                                                  // pop result
      luaState.getField(-1, "contentHeight");                           // get result
      contentHeight = luaState.toNumber(-1);                            // save height
      luaState.pop(1);                                                  // pop result

      // get corona actual content dimensions
      luaState.getField(-1, "actualContentWidth");                      // get result
      actualContentWidth = luaState.toNumber(-1);                       // save width
      luaState.pop(1);                                                  // pop result
      luaState.getField(-1, "actualContentHeight");                     // get result
      actualContentHeight = luaState.toNumber(-1);                      // save height
      luaState.pop(1);                                                  // pop result

      luaState.pop(1);                                                  // pop 'display'

      // get the activity
      final CoronaActivity coronaActivity = CoronaEnvironment.getCoronaActivity();
      Runnable runnableActivity = null;
      if (coronaActivity == null) { // bail if no valid activity
        return 0;
      }

      // get display size in pixels
      Display display = coronaActivity.getWindowManager().getDefaultDisplay();
      Point size = new Point();
      display.getSize(size);
      int displayPixelWidth = size.x;
      int displayPixelHeight = size.y;

      double coronaScale = displayPixelWidth / actualContentWidth;
      offsetX = (actualContentWidth - contentWidth) / 2.0 * coronaScale;
      offsetY = (actualContentHeight - contentHeight) / 2.0 * coronaScale;
      final double customPosX;        // calculated custom position
      final double customPosY;        // calculated custom position

      String adPosition = null;

      // declare final variables for inner class
      final String fAdType = adType;

      // ------------------------------------------------------------------------------------------------
      if (adType.equals(ADTYPE_PANELVIEW)) {
        String panelHandlePosition = POS_CENTER;

        // traverse and verify all options
        for (luaState.pushNil(); luaState.next(2); luaState.pop(1)) {
          String key = luaState.toString(-2);

          switch (key) {
            case "adPosition":
              if (luaState.type(-1) == LuaType.STRING) {
                adPosition = luaState.toString(-1);
              } else {
                logMsg(ERROR_MSG, "options.adPosition (string) expected, got: " + luaState.typeName(-1));
                return 0;
              }
              break;
            case "handlePosition":
              if (luaState.type(-1) == LuaType.STRING) {
                panelHandlePosition = luaState.toString(-1);
              } else {
                logMsg(ERROR_MSG, "options.handlePosition (string) expected, got: " + luaState.typeName(-1));
                return 0;
              }
              break;
            default:
              logMsg(ERROR_MSG, "Invalid option '" + key + "' for adType '" + adType + "'");
              return 0;
          }
        }

        // validate ad position
        if (adPosition == null) {
          logMsg(ERROR_MSG, "options.adPosition required");
          return 0;
        }

        if (! validPanelViewPositions.contains(adPosition)) {
          logMsg(ERROR_MSG, "Invalid adPosition: '" + adPosition + "' for adType '" + adType + "'");
          return 0;
        }

        // validate handle position
        boolean validHandlePos = false;

        if (panelHandlePosition.equals(POS_TOP) || panelHandlePosition.equals(POS_BOTTOM)) {
          validHandlePos = validTBPositions.contains(panelHandlePosition);
        }
        else { // left/right
          validHandlePos = validLRPositions.contains(panelHandlePosition);
        }

        if (! validHandlePos) {
          logMsg(ERROR_MSG, "Invalid handlePosition: '" + panelHandlePosition + "' for panel position '" + adPosition + "'");
          return 0;
        }

        final PANEL_TYPE panelPos;
        final HANDLE_POSITION handlePos;

        // set ad position
        switch (adPosition) {
          case POS_TOP:
            panelPos = PANEL_TYPE.TOP;
            break;
          case POS_LEFT:
            panelPos = null;
            logMsg(ERROR_MSG, "Panel position LEFT was deprecated!");
            break;
          case POS_RIGHT:
            panelPos = null;
            logMsg(ERROR_MSG, "Panel position RIGHT was deprecated!");
            break;
          case POS_BOTTOM:
          default:
            panelPos = PANEL_TYPE.BOTTOM;
            break;
        }

        // set handle position
        switch (panelHandlePosition) {
          case POS_LEFT:
          case POS_TOP:
            handlePos = HANDLE_POSITION.START;
            break;
          case POS_RIGHT:
          case POS_BOTTOM:
            handlePos = HANDLE_POSITION.END;
            break;
          case POS_NONE:
            handlePos = HANDLE_POSITION.NONE;
            break;
          default:
            handlePos = HANDLE_POSITION.CENTER;
            break;
        }

        // Run the activity on the uiThread
        runnableActivity = new Runnable() {
          public void run() {
            // remove old ad object
            if (kidozObjects.containsKey(fAdType)) {
              KidozAdInfo adInfo = (KidozAdInfo)kidozObjects.get(fAdType);
              PanelView savedPanel = (PanelView)adInfo.adInstance;
              // remove listeners and views
              savedPanel.setKidozPlayerListener(null);
              savedPanel.setOnPanelViewEventListener(null);
              savedPanel.removeAllViews();
              // reset instance
              adInfo.adInstance = null;
              kidozObjects.remove(fAdType);
            }

            // create panel object and set its delegate
            PanelView panel = new PanelView(coronaActivity);
            panel.setPanelConfiguration(panelPos, handlePos);
            panel.setOnPanelViewEventListener(panelDelegate);
            panel.setKidozPlayerListener(playerDelegate);

            // configure layout params and add to corona's content view
            RelativeLayout.LayoutParams params = new RelativeLayout.LayoutParams(
              RelativeLayout.LayoutParams.MATCH_PARENT,
              RelativeLayout.LayoutParams.MATCH_PARENT
            );
            try {
              coronaActivity.getOverlayView().addView(panel, params);
            }
            catch (Exception ex) {
              logMsg(ERROR_MSG, "Cannot get Corona overlayView");
              ex.printStackTrace();
            }

            // must set alpha to 0 to hide panel initially. panels in the Kidoz SDK
            // will behave weirdly if trying to use setVisibility() before it's 'ready'
            panel.setAlpha(0f);

            // add object to the dictionary for easy access
            KidozAdInfo adInfo = new KidozAdInfo(panel);
            adInfo.hasUIElement = (handlePos != HANDLE_POSITION.NONE);
            adInfo.isHiddenBySystem = true;
            kidozObjects.put(ADTYPE_PANELVIEW, adInfo);

            // send data to beacon
            sendToBeacon(CoronaBeacon.REQUEST, ADTYPE_PANELVIEW);
          }
        };
      }
      // ------------------------------------------------------------------------------------------------
      else if (adType.equals(ADTYPE_BANNER)) {
        // traverse and validate all options
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

        // validate ad position
        if (adPosition == null) {
          adPosition = "bottom";
        }

        if (! validBannerPositions.contains(adPosition)) {
          logMsg(ERROR_MSG, "Invalid adPosition: '" + adPosition + "' for adType '" + adType + "'");
          return 0;
        }

        BANNER_POSITION bannerGravity;

        switch (adPosition) {
          case POS_TOP:
            bannerGravity = BANNER_POSITION.TOP_CENTER;
            break;
          case POS_TOP_LEFT:
            bannerGravity = BANNER_POSITION.TOP_LEFT;
            break;
          case POS_TOP_RIGHT:
            bannerGravity = BANNER_POSITION.TOP_RIGHT;
            break;
          case POS_BOTTOM:
            bannerGravity = BANNER_POSITION.BOTTOM_CENTER;
            break;
          case POS_BOTTOM_LEFT:
            bannerGravity = BANNER_POSITION.BOTTOM_LEFT;
            break;
          case POS_BOTTOM_RIGHT:
            bannerGravity = BANNER_POSITION.BOTTOM_RIGHT;
            break;
          default:
            bannerGravity = BANNER_POSITION.BOTTOM_CENTER;
            break;
        }

        final String fAdPosition = adPosition;
        final BANNER_POSITION fBannerGravity = bannerGravity;

        // Run the activity on the uiThread
        runnableActivity = new Runnable() {
          public void run() {
            KidozBannerView banner;
            if (kidozObjects.containsKey(fAdType)) {
              // just reuse existing banner ad instance
              KidozAdInfo adInfo = (KidozAdInfo)kidozObjects.get(fAdType);
              banner = (KidozBannerView)adInfo.adInstance;
              banner.setBannerPosition(fBannerGravity);
            } else {
              // create banner object and set its delegate
              banner = KidozSDK.getKidozBanner(coronaActivity);
              banner.setKidozBannerListener(bannerDelegate);
              banner.setBannerPosition(fBannerGravity);
              // add object to the dictionary for easy access
              kidozObjects.put(ADTYPE_BANNER, new KidozAdInfo(banner));
            }

            banner.load();
            // send beacon data to our server
            sendToBeacon(CoronaBeacon.REQUEST, ADTYPE_BANNER);
          }
        };
      }
      // ------------------------------------------------------------------------------------------------
//      else if (adType.equals(ADTYPE_FLEXIVIEW)) {
//        String adAlign = POS_CENTER;
//        boolean draggable = true;
//        boolean closable = true;
//
//        // traverse and validate all options
//        for (luaState.pushNil(); luaState.next(2); luaState.pop(1)) {
//          String key = luaState.toString(-2);
//
//          if (key.equals("adPosition")) {
//            if (luaState.type(-1) == LuaType.STRING) {
//              adPosition = luaState.toString(-1);
//            }
//            else {
//              logMsg(ERROR_MSG, "options.adPosition (string) expected, got: " + luaState.typeName(-1));
//              return 0;
//            }
//          }
//          else if (key.equals("align")) {
//            if (luaState.type(-1) == LuaType.STRING) {
//              adAlign = luaState.toString(-1);
//            }
//            else {
//              logMsg(ERROR_MSG, "options.align (string) expected, got: " + luaState.typeName(-1));
//              return 0;
//            }
//          }
//          else if (key.equals("draggable")) {
//            if (luaState.type(-1) == LuaType.BOOLEAN) {
//              draggable = luaState.toBoolean(-1);
//            }
//            else {
//              logMsg(ERROR_MSG, "options.draggable (boolean) expected, got: " + luaState.typeName(-1));
//              return 0;
//            }
//          }
//          else if (key.equals("closable")) {
//            if (luaState.type(-1) == LuaType.BOOLEAN) {
//              closable = luaState.toBoolean(-1);
//            }
//            else {
//              logMsg(ERROR_MSG, "options.closable (boolean) expected, got: " + luaState.typeName(-1));
//              return 0;
//            }
//          }
//          else {
//            logMsg(ERROR_MSG, "Invalid option '" + key + "' for adType '" + adType + "'");
//            return 0;
//          }
//        }
//
//        // validate ad position
//        if (adPosition == null) {
//          logMsg(ERROR_MSG, "options.adPosition required");
//          return 0;
//        }
//
//        if (! validFlexiViewPositions.contains(adPosition)) {
//          logMsg(ERROR_MSG, "Invalid adPosition: '" + adPosition + "' for adType '" + adType + "'");
//          return 0;
//        }
//
//        // validate align
//        boolean validAlignPos = false;
//
//        if (adPosition.equals(POS_TOP) || adPosition.equals(POS_BOTTOM)) {
//          validAlignPos = validTBPositions.contains(adAlign);
//        }
//        else if (adPosition.equals(POS_LEFT) || adPosition.equals(POS_RIGHT)) {
//          validAlignPos = validLRPositions.contains(adAlign);
//        }
//        else { // center position
//          validAlignPos = adAlign.equals(POS_CENTER);
//        }
//
//        if (!validAlignPos) {
//          logMsg(ERROR_MSG, "adType '" + adType + "' Invalid align: '" + adAlign + "' for adPosition '" + adPosition + "'");
//          return 0;
//        }
//
//        final FLEXI_POSITION flexiPosition;
//
//        // set ad position
//        if (adPosition.equals(POS_TOP)) {
//          if (adAlign.equals(POS_LEFT)) {
//            flexiPosition = FLEXI_POSITION.TOP_START;
//          }
//          else if (adAlign.equals(POS_CENTER)) {
//            flexiPosition = FLEXI_POSITION.TOP_CENTER;
//          }
//          else if (adAlign.equals(POS_RIGHT)) {
//            flexiPosition = FLEXI_POSITION.TOP_END;
//          }
//          else {
//            flexiPosition = FLEXI_POSITION.TOP_CENTER;
//          }
//        }
//        else if (adPosition.equals(POS_BOTTOM)) {
//          if (adAlign.equals(POS_LEFT)) {
//            flexiPosition = FLEXI_POSITION.BOTTOM_START;
//          }
//          else if (adAlign.equals(POS_CENTER)) {
//            flexiPosition = FLEXI_POSITION.BOTTOM_CENTER;
//          }
//          else if (adAlign.equals(POS_RIGHT)) {
//            flexiPosition = FLEXI_POSITION.BOTTOM_END;
//          }
//          else {
//            flexiPosition = FLEXI_POSITION.BOTTOM_CENTER;
//          }
//        }
//        else if (adPosition.equals(POS_LEFT)) {
//          if (adAlign.equals(POS_TOP)) {
//            flexiPosition = FLEXI_POSITION.TOP_START;
//          }
//          else if (adAlign.equals(POS_CENTER)) {
//            flexiPosition = FLEXI_POSITION.MIDDLE_START;
//          }
//          else if (adAlign.equals(POS_BOTTOM)) {
//            flexiPosition = FLEXI_POSITION.BOTTOM_START;
//          }
//          else {
//            flexiPosition = FLEXI_POSITION.MIDDLE_START;
//          }
//        }
//        else if (adPosition.equals(POS_RIGHT)) {
//          if (adAlign.equals(POS_TOP)) {
//            flexiPosition = FLEXI_POSITION.TOP_END;
//          }
//          else if (adAlign.equals(POS_CENTER)) {
//            flexiPosition = FLEXI_POSITION.MIDDLE_END;
//          }
//          else if (adAlign.equals(POS_BOTTOM)) {
//            flexiPosition = FLEXI_POSITION.BOTTOM_END;
//          }
//          else {
//            flexiPosition = FLEXI_POSITION.MIDDLE_END;
//          }
//        }
//        else {
//          flexiPosition = FLEXI_POSITION.MIDDLE_CENTER;
//        }
//
//        final boolean fClosable = closable;
//        final boolean fDraggable = draggable;
//
//        // Run the activity on the uiThread
//        runnableActivity = new Runnable() {
//          public void run() {
//            // remove old ad object
//            if (kidozObjects.containsKey(fAdType)) {
//              KidozAdInfo adInfo = (KidozAdInfo)kidozObjects.get(fAdType);
//              FlexiView savedFlexi = (FlexiView)adInfo.adInstance;
//              // remove listeners and views
//              savedFlexi.setOnFlexiViewEventListener(null);
//              savedFlexi.setKidozPlayerListener(null);
//              savedFlexi.removeAllViews();
//              // remove ad object
//              adInfo.adInstance = null;
//              kidozObjects.remove(fAdType);
//            }
//
//            // create new flexi view object and and set its delegate
//            FlexiView flexi = new FlexiView(coronaActivity);
//            flexi.setFlexiViewInitialPosition(flexiPosition);
//            flexi.setAutoShow(false); // must call kidoz.show() to display flexiView
////            flexi.setClosable(fClosable);
////            flexi.setDraggable(fDraggable);
//            flexi.setOnFlexiViewEventListener(flexiDelegate);
//            flexi.setKidozPlayerListener(playerDelegate);
//
//            // define layout params
//            RelativeLayout.LayoutParams params = new RelativeLayout.LayoutParams(
//              RelativeLayout.LayoutParams.MATCH_PARENT,
//              RelativeLayout.LayoutParams.MATCH_PARENT
//            );
//            try {
//              coronaActivity.getOverlayView().addView(flexi, params);
//            }
//            catch (Exception ex) {
//              logMsg(ERROR_MSG, "Cannot get Corona overlayView");
//              ex.printStackTrace();
//            }
//
//            // add object to the dictionary for easy access
//            kidozObjects.put(ADTYPE_FLEXIVIEW, new KidozAdInfo(flexi));
//
//            // send beacon data to our server
//            sendToBeacon(CoronaBeacon.REQUEST, ADTYPE_FLEXIVIEW);
//          }
//        };
//      }
      // ------------------------------------------------------------------------------------------------
      else if (adType.equals(ADTYPE_INTERSTITIAL)) {
        runnableActivity = new Runnable() {
          public void run() {
            KidozInterstitial interstitial;
            if (kidozObjects.containsKey(fAdType)) {
              // just reuse existing interstitial ad instance
              KidozAdInfo adInfo = (KidozAdInfo)kidozObjects.get(fAdType);
              interstitial = (KidozInterstitial)adInfo.adInstance;
            } else {
              // create and load a new interstitial ad object and set its delegates
              interstitial = new KidozInterstitial(coronaActivity, KidozInterstitial.AD_TYPE.INTERSTITIAL);
              interstitial.setOnInterstitialEventListener(interstitialDelegate);
              // add object to the dictionary for easy access
              kidozObjects.put(ADTYPE_INTERSTITIAL, new KidozAdInfo(interstitial));
            }

            interstitial.loadAd();
            // send beacon data to our server
            sendToBeacon(CoronaBeacon.REQUEST, ADTYPE_INTERSTITIAL);
          }
        };
      }
      // ------------------------------------------------------------------------------------------------
      else if (adType.equals(ADTYPE_REWARDEDVIDEO)) {
        runnableActivity = new Runnable() {
          public void run() {
            KidozInterstitial rewardedVideo;
            if (kidozObjects.containsKey(fAdType)) {
              // just reuse existing rewarded ad instance
              KidozAdInfo adInfo = (KidozAdInfo)kidozObjects.get(fAdType);
              rewardedVideo = (KidozInterstitial)adInfo.adInstance;
            } else {
              // create and load a new rewarded ad object and set its delegates
              rewardedVideo = new KidozInterstitial(coronaActivity, KidozInterstitial.AD_TYPE.REWARDED_VIDEO);
              rewardedVideo.setOnInterstitialEventListener(rewardedInterstitialDelegate);
              rewardedVideo.setOnInterstitialRewardedEventListener(rewardedVideoDelegate);
              // add object to the dictionary for easy access
              kidozObjects.put(ADTYPE_REWARDEDVIDEO, new KidozAdInfo(rewardedVideo));
            }

            rewardedVideo.loadAd();
            // send beacon data to our server
            sendToBeacon(CoronaBeacon.REQUEST, ADTYPE_REWARDEDVIDEO);
          }
        };
      }
      // ------------------------------------------------------------------------------------------------
//      else if (adType.equals(ADTYPE_VIDEOUNIT)) {
//        runnableActivity = new Runnable() {
//          public void run() {
//            // remove old ad object
//            if (kidozObjects.containsKey(fAdType)) {
//              KidozAdInfo adInfo = (KidozAdInfo)kidozObjects.get(fAdType);
//              // remove listeners
//              ((VideoUnit)adInfo.adInstance).setVideoUnitListener(null);
//              // remove ad object
//              adInfo.adInstance = null;
//              kidozObjects.remove(fAdType);
//            }
//
//            // create and load a new video unit object and set its delegates
//            VideoUnit videoUnit = new VideoUnit(coronaActivity);
//            videoUnit.setVideoUnitListener(videoUnitDelegate);
//
//            // add object to the dictionary for easy access
//            kidozObjects.put(ADTYPE_VIDEOUNIT, new KidozAdInfo(videoUnit));
//
//            // send beacon data to our server
//            sendToBeacon(CoronaBeacon.REQUEST, ADTYPE_VIDEOUNIT);
//          }
//        };
//      }

      coronaActivity.runOnUiThread(runnableActivity);

      return 0;
    }
  }

  // [Lua] isLoaded(adType)
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

      // don't continue if SDK isn't initialized
      if (! isSDKInitialized()) {
        return 0;
      }

      // check number of args
      int nargs = luaState.getTop();
      if (nargs != 1) {
        logMsg(ERROR_MSG, "Expected 1 argument, got: " + nargs);
        return 0;
      }

      // check for ad type (required)
      if (luaState.type(1) == LuaType.STRING) {
        adType = luaState.toString(1);

        // check to see if valid type
        if (! validAdTypes.contains(adType)) {
          logMsg(ERROR_MSG, "Invalid adType: '" + adType + "'");
          return 0;
        }
      }
      else {
        logMsg(ERROR_MSG, "adType, expected string, got: " + luaState.typeName(1));
        return 0;
      }

      // check if ad is ready for display
      KidozAdInfo adObject = (KidozAdInfo)kidozObjects.get(adType);
      boolean isReady = (adObject != null) && adObject.isLoaded;
      luaState.pushBoolean(isReady);

      return 1;
    }
  }

  // [Lua] show(adType [, options])
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
      boolean forceOpen = false;

      functionSignature = "kidoz.show(adType [, options])";

      // don't continue if SDK isn't initialized
      if (! isSDKInitialized()) {
        return 0;
      }

      // check number of args
      int nargs = luaState.getTop();
      if ((nargs < 1) || (nargs > 2)) {
        logMsg(ERROR_MSG, "Expected 1-2 argument(s), got: " + nargs);
        return 0;
      }

      // check for ad type (required)
      if (luaState.type(1) == LuaType.STRING) {
        adType = luaState.toString(1);
      }
      else {
        logMsg(ERROR_MSG, "adType, expected string, got: " + luaState.typeName(1));
        return 0;
      }

      // check for options table (optional)
      if (! luaState.isNoneOrNil(2)) {
        if (luaState.type(2) == LuaType.TABLE) {
          // traverse and verify all options
          for (luaState.pushNil(); luaState.next(2); luaState.pop(1)) {
            String key = luaState.toString(-2);

            if (key.equals("open")) {
              if (luaState.type(-1) == LuaType.BOOLEAN) {
                forceOpen = luaState.toBoolean(-1);
              }
              else {
                logMsg(ERROR_MSG, "options.open (boolean) expected, got: " + luaState.typeName(-1));
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
          logMsg(ERROR_MSG, "options table expected, got: " + luaState.typeName(2));
          return 0;
        }
      }

      // check to see if valid type
      if (! validAdTypes.contains(adType)) {
        logMsg(ERROR_MSG, "Invalid adType: '" + adType + "'");
        return 0;
      }

      final CoronaActivity coronaActivity = CoronaEnvironment.getCoronaActivity();
      final boolean fForceOpen = forceOpen;

      // Run the activity on the uiThread
      if (coronaActivity != null) {
        Runnable runnableActivity = new Runnable() {
          public void run() {
            // check if ad type has been loaded
            KidozAdInfo adObject = (KidozAdInfo)kidozObjects.get(adType);

            if ((adObject == null)) {// || (! adObject.isLoaded)) {
              logMsg(WARNING_MSG, "adType '" + adType + "' not loaded");
            }
            else {
              if (adType.equals(ADTYPE_PANELVIEW)) {
                PanelView panel = (PanelView)adObject.adInstance;

                // unset isHiddenBySystem
                if (adObject.isHiddenBySystem) {
                  adObject.isHiddenBySystem = false;
                  panel.setVisibility(View.VISIBLE);
                  panel.setAlpha(1f);
                  panel.bringToFront();
                }

                // perform action. panel has button => show, else open
                if (adObject.hasUIElement && ! fForceOpen) {
                  panel.setVisibility(View.VISIBLE);
                  panel.bringToFront();
                }
                else {
                  panel.expandPanelView();
                }
              }
//              else if (adType.equals(ADTYPE_FLEXIVIEW)) {
//                ((FlexiView)adObject.adInstance).showFlexiView();
//              }
              else if (adType.equals(ADTYPE_BANNER)) {
                ((KidozBannerView)adObject.adInstance).show();
              }
              else if (adType.equals(ADTYPE_INTERSTITIAL)) {
                ((KidozInterstitial)adObject.adInstance).show();
              }
              else if (adType.equals(ADTYPE_REWARDEDVIDEO)) {
                ((KidozInterstitial)adObject.adInstance).show();
              }
//              else if (adType.equals(ADTYPE_VIDEOUNIT)) {
//                ((VideoUnit)adObject.adInstance).show();
//              }
            }
          }
        };

        coronaActivity.runOnUiThread(runnableActivity);
      }

      return 0;
    }
  }

  // [Lua] hide(adType [, options])
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
      boolean forceClose = false;

      functionSignature = "kidoz.hide(adType [, options])";

      // don't continue if SDK isn't initialized
      if (! isSDKInitialized()) {
        return 0;
      }

      // check number of args
      int nargs = luaState.getTop();
      if ((nargs < 1) || (nargs > 2)) {
        logMsg(ERROR_MSG, "Expected 1-2 argument(s), got: " + nargs);
        return 0;
      }

      // check for ad type (required)
      if (luaState.type(1) == LuaType.STRING) {
        adType = luaState.toString(1);
      }
      else {
        logMsg(ERROR_MSG, "adType, expected string, got: " + luaState.typeName(1));
        return 0;
      }

      // check for options table (optional)
      if (! luaState.isNoneOrNil(2)) {
        if (luaState.type(2) == LuaType.TABLE) {
          // traverse and verify all options
          for (luaState.pushNil(); luaState.next(2); luaState.pop(1)) {
            String key = luaState.toString(-2);

            if (key.equals("close")) {
              if (luaState.type(-1) == LuaType.BOOLEAN) {
                forceClose = luaState.toBoolean(-1);
              }
              else {
                logMsg(ERROR_MSG, "options.close (boolean) expected, got: " + luaState.typeName(-1));
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
          logMsg(ERROR_MSG, "options table expected, got: " + luaState.typeName(2));
          return 0;
        }
      }

      // check to see if valid type
      if (! validAdTypes.contains(adType)) {
        logMsg(ERROR_MSG, "Invalid adType: '" + adType + "'");
        return 0;
      }

      if (adType.equals(ADTYPE_INTERSTITIAL)) {
        logMsg(ERROR_MSG, "Interstitials cannot be hidden");
        return 0;
      }

      final CoronaActivity coronaActivity = CoronaEnvironment.getCoronaActivity();
      final boolean fForceClose = forceClose;

      // Run the activity on the uiThread
      if (coronaActivity != null) {
        Runnable runnableActivity = new Runnable() {
          public void run() {
            // check if ad type has been loaded
            KidozAdInfo adObject = (KidozAdInfo)kidozObjects.get(adType);

            if ((adObject == null) || (! adObject.isLoaded)) {
              logMsg(WARNING_MSG, "adType '" + adType + "' not loaded");
            }
            else {
              if (adType.equals(ADTYPE_PANELVIEW)) {
                PanelView panel = (PanelView)adObject.adInstance;

                // perform action. panel has button => hide, else close
                if (adObject.hasUIElement && ! fForceClose) {
                  panel.setVisibility(View.INVISIBLE);
                }
                else {
                  panel.collapsePanelView();
                }
              }
//              else if (adType.equals(ADTYPE_FLEXIVIEW)) {
//                ((FlexiView)adObject.adInstance).hideFlexiView();
//              }
              else if (adType.equals(ADTYPE_BANNER)) {
                ((KidozBannerView)adObject.adInstance).hide();
                adObject.isLoaded = false;
              }
            }
          }
        };

        coronaActivity.runOnUiThread(runnableActivity);
      }

      return 0;
    }
  }

  // -------------------------------------------------------------------
  // Delegates
  // -------------------------------------------------------------------

  private class KidozSDKDelegate implements SDKEventListener
  {
    @Override
    public void onInitError(String error) {
      // create init event
      Map<String, Object> coronaEvent = new HashMap<>();
      coronaEvent.put(EVENT_PHASE_KEY, PHASE_FAILED);
      coronaEvent.put(CoronaLuaEvent.ISERROR_KEY, true);
      coronaEvent.put(CoronaLuaEvent.RESPONSE_KEY, error);
      dispatchLuaEvent(coronaEvent);
    }

    @Override
    public void onInitSuccess() {
      // create init event
      Map<String, Object> coronaEvent = new HashMap<>();
      coronaEvent.put(EVENT_PHASE_KEY, PHASE_INIT);
      dispatchLuaEvent(coronaEvent);
    }
  }

  // -------------------------------------------------------------------

  private class PanelDelegate implements IOnPanelViewEventListener
  {
    // Panel View --------------------------
    @Override
    public void onPanelReady()
    {
      if (! kidozObjects.isEmpty()) {
        // get saved ad object
        final KidozAdInfo adObject = (KidozAdInfo)kidozObjects.get(ADTYPE_PANELVIEW);
        final PanelView panel = (PanelView)adObject.adInstance;
        final CoronaActivity coronaActivity = CoronaEnvironment.getCoronaActivity();

        if (coronaActivity != null) {
          coronaActivity.runOnUiThread(new Runnable() {
            @Override
            public void run() {
              panel.setVisibility(View.INVISIBLE);   // now it's safe to make panel invisible
              panel.setAlpha(1.0f);                  // reset alpha to 1 (see comment in kidoz.load)
              adObject.isLoaded = true;
            }
          });
        }

        // delay sending the 'loaded' phase since an animation is in progress (wait 1 second)
        panel.postDelayed(new Runnable() {
          @Override
          public void run() {
            // send lua event
            Map<String, Object> coronaEvent = new HashMap<>();
            coronaEvent.put(EVENT_PHASE_KEY, PHASE_LOADED);
            coronaEvent.put(EVENT_TYPE_KEY, ADTYPE_PANELVIEW);
            dispatchLuaEvent(coronaEvent);
          }
        }, 1000);
      }
    }

    @Override
    public void onPanelViewCollapsed()
    {
      // create event
      Map<String, Object> coronaEvent = new HashMap<>();
      coronaEvent.put(EVENT_PHASE_KEY, PHASE_CLOSED);
      coronaEvent.put(EVENT_TYPE_KEY, ADTYPE_PANELVIEW);
      dispatchLuaEvent(coronaEvent);
    }

    @Override
    public void onPanelViewExpanded()
    {
      // create event
      Map<String, Object> coronaEvent = new HashMap<>();
      coronaEvent.put(EVENT_PHASE_KEY, PHASE_DISPLAYED);
      coronaEvent.put(EVENT_TYPE_KEY, ADTYPE_PANELVIEW);
      dispatchLuaEvent(coronaEvent);

      sendToBeacon(CoronaBeacon.IMPRESSION, ADTYPE_PANELVIEW);
    }
  }

//  private class FlexiDelegate extends FlexiViewListener
//  {
//    @Override
//    public void onViewVisible()
//    {
//      // create event
//      Map<String, Object> coronaEvent = new HashMap<>();
//      coronaEvent.put(EVENT_PHASE_KEY, PHASE_DISPLAYED);
//      coronaEvent.put(EVENT_TYPE_KEY, ADTYPE_FLEXIVIEW);
//      dispatchLuaEvent(coronaEvent);
//
//      sendToBeacon(CoronaBeacon.IMPRESSION, ADTYPE_FLEXIVIEW);
//    }
//
//    @Override
//    public void onViewHidden()
//    {
//      // create event
//      Map<String, Object> coronaEvent = new HashMap<>();
//      coronaEvent.put(EVENT_PHASE_KEY, PHASE_CLOSED);
//      coronaEvent.put(EVENT_TYPE_KEY, ADTYPE_FLEXIVIEW);
//      dispatchLuaEvent(coronaEvent);
//    }
//
//    @Override
//    public void onViewReady()
//    {
//      if (! kidozObjects.isEmpty()) {
//        // create event
//        Map<String, Object> coronaEvent = new HashMap<>();
//        coronaEvent.put(EVENT_PHASE_KEY, PHASE_LOADED);
//        coronaEvent.put(EVENT_TYPE_KEY, ADTYPE_FLEXIVIEW);
//        dispatchLuaEvent(coronaEvent);
//
//        ((KidozAdInfo)kidozObjects.get(ADTYPE_FLEXIVIEW)).isLoaded = true;
//      }
//    }
//  }

  private class BannerDelegate implements KidozBannerListener
  {
    @Override
    public void onBannerError(String errorMsg)
    {
      if (! kidozObjects.isEmpty()) {
        // create event
        Map<String, Object> coronaEvent = new HashMap<>();
        coronaEvent.put(EVENT_PHASE_KEY, PHASE_FAILED);
        coronaEvent.put(EVENT_TYPE_KEY, ADTYPE_BANNER);
        coronaEvent.put(CoronaLuaEvent.ISERROR_KEY, true);
        coronaEvent.put(CoronaLuaEvent.RESPONSE_KEY, RESPONSE_LOAD_FAILED + ": " + errorMsg);
        dispatchLuaEvent(coronaEvent);

        ((KidozAdInfo)kidozObjects.get(ADTYPE_BANNER)).isLoaded = false;
      }
    }

    @Override
    public void onBannerClose()
    {
      // create event
      Map<String, Object> coronaEvent = new HashMap<>();
      coronaEvent.put(EVENT_PHASE_KEY, PHASE_CLOSED);
      coronaEvent.put(EVENT_TYPE_KEY, ADTYPE_BANNER);
      dispatchLuaEvent(coronaEvent);
    }

    @Override
    public void onBannerReady()
    {
      if (! kidozObjects.isEmpty()) {
        // create event
        Map<String, Object> coronaEvent = new HashMap<>();
        coronaEvent.put(EVENT_PHASE_KEY, PHASE_LOADED);
        coronaEvent.put(EVENT_TYPE_KEY, ADTYPE_BANNER);
        dispatchLuaEvent(coronaEvent);

        ((KidozAdInfo)kidozObjects.get(ADTYPE_BANNER)).isLoaded = true;
      }
    }

    @Override
    public void onBannerViewAdded()
    {
      // create event
      Map<String, Object> coronaEvent = new HashMap<>();
      coronaEvent.put(EVENT_PHASE_KEY, PHASE_DISPLAYED);
      coronaEvent.put(EVENT_TYPE_KEY, ADTYPE_BANNER);
      dispatchLuaEvent(coronaEvent);

      sendToBeacon(CoronaBeacon.IMPRESSION, ADTYPE_BANNER);
    }

    @Override
    public void onBannerNoOffers()
    {
      // create event
      Map<String, Object> coronaEvent = new HashMap<>();
      coronaEvent.put(EVENT_PHASE_KEY, PHASE_FAILED);
      coronaEvent.put(EVENT_TYPE_KEY, ADTYPE_BANNER);
      coronaEvent.put(CoronaLuaEvent.ISERROR_KEY, true);
      coronaEvent.put(CoronaLuaEvent.RESPONSE_KEY, RESPONSE_LOAD_FAILED + ": " + RESPONSE_NO_OFFERS);
      dispatchLuaEvent(coronaEvent);
    }
  }

  private class PlayerDelegate extends KidozPlayerListener
  {
    @Override
    public void onPlayerClose()
    {
      // create event
      Map<String, Object> coronaEvent = new HashMap<>();
      coronaEvent.put(EVENT_PHASE_KEY, PHASE_PLAYBACK_ENDED);
      coronaEvent.put(EVENT_TYPE_KEY, ADTYPE_VIDEO);
      dispatchLuaEvent(coronaEvent);
    }

    @Override
    public void onPlayerOpen()
    {
      // create event
      Map<String, Object> coronaEvent = new HashMap<>();
      coronaEvent.put(EVENT_PHASE_KEY, PHASE_PLAYBACK_BEGAN);
      coronaEvent.put(EVENT_TYPE_KEY, ADTYPE_VIDEO);
      dispatchLuaEvent(coronaEvent);

      sendToBeacon(CoronaBeacon.IMPRESSION, ADTYPE_VIDEO);
    }
  }

  private class InterstitialDelegate implements BaseInterstitial.IOnInterstitialEventListener
  {
    @Override
    public void onNoOffers() {
      if (! kidozObjects.isEmpty()) {
        // create event
        Map<String, Object> coronaEvent = new HashMap<>();
        coronaEvent.put(EVENT_PHASE_KEY, PHASE_FAILED);
        coronaEvent.put(EVENT_TYPE_KEY, ADTYPE_INTERSTITIAL);
        coronaEvent.put(CoronaLuaEvent.ISERROR_KEY, true);
        coronaEvent.put(CoronaLuaEvent.RESPONSE_KEY, RESPONSE_NO_OFFERS);
        dispatchLuaEvent(coronaEvent);

        ((KidozAdInfo)kidozObjects.get(ADTYPE_INTERSTITIAL)).isLoaded = false;
      }
    }

    @Override
    public void onReady()
    {
      if (! kidozObjects.isEmpty()) {
        // interstitial is ready. set loaded flag
        ((KidozAdInfo)kidozObjects.get(ADTYPE_INTERSTITIAL)).isLoaded = true;

        // create event
        Map<String, Object> coronaEvent = new HashMap<>();
        coronaEvent.put(EVENT_PHASE_KEY, PHASE_LOADED);
        coronaEvent.put(EVENT_TYPE_KEY, ADTYPE_INTERSTITIAL);
        dispatchLuaEvent(coronaEvent);
      }
    }

    @Override
    public void onOpened()
    {
      if (! kidozObjects.isEmpty()) {
        // interstitial has been used. reset loaded flag
        ((KidozAdInfo)kidozObjects.get(ADTYPE_INTERSTITIAL)).isLoaded = false;

        // create event
        Map<String, Object> coronaEvent = new HashMap<>();
        coronaEvent.put(EVENT_PHASE_KEY, PHASE_DISPLAYED);
        coronaEvent.put(EVENT_TYPE_KEY, ADTYPE_INTERSTITIAL);
        dispatchLuaEvent(coronaEvent);

        sendToBeacon(CoronaBeacon.IMPRESSION, ADTYPE_INTERSTITIAL);
      }
    }

    @Override
    public void onClosed()
    {
      // create event
      Map<String, Object> coronaEvent = new HashMap<>();
      coronaEvent.put(EVENT_PHASE_KEY, PHASE_CLOSED);
      coronaEvent.put(EVENT_TYPE_KEY, ADTYPE_INTERSTITIAL);
      dispatchLuaEvent(coronaEvent);
    }

    @Override
    public void onLoadFailed() {
      if (! kidozObjects.isEmpty()) {
        // create event
        Map<String, Object> coronaEvent = new HashMap<>();
        coronaEvent.put(EVENT_PHASE_KEY, PHASE_FAILED);
        coronaEvent.put(EVENT_TYPE_KEY, ADTYPE_INTERSTITIAL);
        coronaEvent.put(CoronaLuaEvent.ISERROR_KEY, true);
        coronaEvent.put(CoronaLuaEvent.RESPONSE_KEY, RESPONSE_LOAD_FAILED);
        dispatchLuaEvent(coronaEvent);

        ((KidozAdInfo)kidozObjects.get(ADTYPE_INTERSTITIAL)).isLoaded = false;
      }
    }
  }

  private class RewardedVideoDelegate implements BaseInterstitial.IOnInterstitialRewardedEventListener
  {
    @Override
    public void onRewardedStarted() {
      // create event
      Map<String, Object> coronaEvent = new HashMap<>();
      coronaEvent.put(EVENT_PHASE_KEY, PHASE_PLAYBACK_BEGAN);
      coronaEvent.put(EVENT_TYPE_KEY, ADTYPE_REWARDEDVIDEO);
      dispatchLuaEvent(coronaEvent);
    }

    @Override
    public void onRewardReceived() {
      // create event
      Map<String, Object> coronaEvent = new HashMap<>();
      coronaEvent.put(EVENT_PHASE_KEY, PHASE_REWARD);
      coronaEvent.put(EVENT_TYPE_KEY, ADTYPE_REWARDEDVIDEO);
      dispatchLuaEvent(coronaEvent);
    }
  }
  private class RewardedInterstitialDelegate implements BaseInterstitial.IOnInterstitialEventListener {
    @Override
    public void onNoOffers() {
      if (! kidozObjects.isEmpty()) {
        // create event
        Map<String, Object> coronaEvent = new HashMap<>();
        coronaEvent.put(EVENT_PHASE_KEY, PHASE_FAILED);
        coronaEvent.put(EVENT_TYPE_KEY, ADTYPE_REWARDEDVIDEO);
        coronaEvent.put(CoronaLuaEvent.ISERROR_KEY, true);
        coronaEvent.put(CoronaLuaEvent.RESPONSE_KEY, RESPONSE_NO_OFFERS);
        dispatchLuaEvent(coronaEvent);

        ((KidozAdInfo)kidozObjects.get(ADTYPE_REWARDEDVIDEO)).isLoaded = false;
      }
    }

    @Override
    public void onReady()
    {
      if (! kidozObjects.isEmpty()) {
        // interstitial is ready. set loaded flag
        ((KidozAdInfo)kidozObjects.get(ADTYPE_REWARDEDVIDEO)).isLoaded = true;

        // create event
        Map<String, Object> coronaEvent = new HashMap<>();
        coronaEvent.put(EVENT_PHASE_KEY, PHASE_LOADED);
        coronaEvent.put(EVENT_TYPE_KEY, ADTYPE_REWARDEDVIDEO);
        dispatchLuaEvent(coronaEvent);
      }
    }

    @Override
    public void onOpened()
    {
      if (! kidozObjects.isEmpty()) {
        // interstitial has been used. reset loaded flag
        ((KidozAdInfo)kidozObjects.get(ADTYPE_REWARDEDVIDEO)).isLoaded = false;

        // create event
        Map<String, Object> coronaEvent = new HashMap<>();
        coronaEvent.put(EVENT_PHASE_KEY, PHASE_DISPLAYED);
        coronaEvent.put(EVENT_TYPE_KEY, ADTYPE_REWARDEDVIDEO);
        dispatchLuaEvent(coronaEvent);

        sendToBeacon(CoronaBeacon.IMPRESSION, ADTYPE_REWARDEDVIDEO);
      }
    }

    @Override
    public void onClosed()
    {
      // create event
      Map<String, Object> coronaEvent = new HashMap<>();
      coronaEvent.put(EVENT_PHASE_KEY, PHASE_CLOSED);
      coronaEvent.put(EVENT_TYPE_KEY, ADTYPE_REWARDEDVIDEO);
      dispatchLuaEvent(coronaEvent);
    }

    @Override
    public void onLoadFailed() {
      if (! kidozObjects.isEmpty()) {
        // create event
        Map<String, Object> coronaEvent = new HashMap<>();
        coronaEvent.put(EVENT_PHASE_KEY, PHASE_FAILED);
        coronaEvent.put(EVENT_TYPE_KEY, ADTYPE_REWARDEDVIDEO);
        coronaEvent.put(CoronaLuaEvent.ISERROR_KEY, true);
        coronaEvent.put(CoronaLuaEvent.RESPONSE_KEY, RESPONSE_LOAD_FAILED);
        dispatchLuaEvent(coronaEvent);

        ((KidozAdInfo)kidozObjects.get(ADTYPE_REWARDEDVIDEO)).isLoaded = false;
      }
    }
  }

//  private class VideoUnitDelegate implements VideoUnit.VideoUnitListener {
//    @Override
//    public void onReady() {
//      if (! kidozObjects.isEmpty()) {
//        // interstitial is ready. set loaded flag
//        for (Map.Entry<String,Object> entry : kidozObjects.entrySet()) {
//          String key = entry.getKey();
//          Object value = entry.getValue();
//          logMsg(ERROR_MSG, "~~~"+key);
//        }
//        ((KidozAdInfo)kidozObjects.get(ADTYPE_VIDEOUNIT)).isLoaded = true;
//
//        // create event
//        Map<String, Object> coronaEvent = new HashMap<>();
//        coronaEvent.put(EVENT_PHASE_KEY, PHASE_LOADED);
//        coronaEvent.put(EVENT_TYPE_KEY, ADTYPE_VIDEOUNIT);
//        dispatchLuaEvent(coronaEvent);
//      }
//    }
//
//    @Override
//    public void onOpen() {
//      if (! kidozObjects.isEmpty()) {
//        // interstitial has been used. reset loaded flag
//        for (Map.Entry<String,Object> entry : kidozObjects.entrySet()) {
//          String key = entry.getKey();
//          Object value = entry.getValue();
//          logMsg(ERROR_MSG, "~~~"+key);
//        }
//        ((KidozAdInfo)kidozObjects.get(ADTYPE_VIDEOUNIT)).isLoaded = false;
//
//        // create event
//        Map<String, Object> coronaEvent = new HashMap<>();
//        coronaEvent.put(EVENT_PHASE_KEY, PHASE_DISPLAYED);
//        coronaEvent.put(EVENT_TYPE_KEY, ADTYPE_VIDEOUNIT);
//        dispatchLuaEvent(coronaEvent);
//
//        sendToBeacon(CoronaBeacon.IMPRESSION, ADTYPE_VIDEOUNIT);
//      }
//    }
//
//    @Override
//    public void onClose() {
//      // create event
//      Map<String, Object> coronaEvent = new HashMap<>();
//      coronaEvent.put(EVENT_PHASE_KEY, PHASE_CLOSED);
//      coronaEvent.put(EVENT_TYPE_KEY, ADTYPE_VIDEOUNIT);
//      dispatchLuaEvent(coronaEvent);
//    }
//  }
}
