//
//  KidozPlugin.mm
//  Kidoz Plugin
//
//  Copyright (c) 2016 Corona Labs Inc. All rights reserved.
//

#import <Foundation/Foundation.h>
#import <UIKit/UIKit.h>

#import "CoronaRuntime.h"
#import "CoronaAssert.h"
#import "CoronaEvent.h"
#import "CoronaLua.h"
#import "CoronaLibrary.h"
#import "CoronaLuaIOS.h"

// Kidoz
#import "KidozPlugin.h"
#import "KIDOZSDK.h"

// some macros to make life easier, and code more readable
#define UTF8StringWithFormat(format, ...) [[NSString stringWithFormat:format, ##__VA_ARGS__] UTF8String]
#define UTF8IsEqual(utf8str1, utf8str2) (strcmp(utf8str1, utf8str2) == 0)
#define UTF8Concat(utf8str1, utf8str2) [[NSString stringWithFormat:@"%s%s", utf8str1, utf8str2] UTF8String]
#define MsgFormat(format, ...) [NSString stringWithFormat:format, ##__VA_ARGS__]

// ----------------------------------------------------------------------------
// Plugin Constants
// ----------------------------------------------------------------------------

#define PLUGIN_NAME     "plugin.kidoz"    // Class plugin name
#define PLUGIN_VERSION  "2.1"

static const char EVENT_NAME[]    = "adsRequest";
static const char PROVIDER_NAME[] = "kidoz";

// ad types
static const char ADTYPE_PANELVIEW[]     = "panelView";
static const char ADTYPE_FEEDVIEW[]      = "feedView";
static const char ADTYPE_FLEXIVIEW[]     = "flexiView";
static const char ADTYPE_BANNER[]        = "banner";
static const char ADTYPE_INTERSTITIAL[]  = "interstitial";
static const char ADTYPE_REWARDEDVIDEO[] = "rewardedVideo";

// event phases
static NSString * const PHASE_INIT           = @"init";
static NSString * const PHASE_INIT_FAILED    = @"initFailed";
static NSString * const PHASE_LOADED         = @"loaded";
static NSString * const PHASE_FAILED         = @"failed";
static NSString * const PHASE_DISPLAYED      = @"displayed";
static NSString * const PHASE_CLOSED         = @"closed";
static NSString * const PHASE_PLAYBACK_BEGAN = @"playbackBegan";
static NSString * const PHASE_PLAYBACK_ENDED = @"playbackEnded";
static NSString * const PHASE_REWARD         = @"reward";

// responses
static NSString * const RESPONSE_LOAD_FAILED = @"loadFailed";
static NSString * const RESPONSE_LOAD_NO_FILL = @"no fill";

// message constants
static NSString * const ERROR_MSG   = @"ERROR: ";
static NSString * const WARNING_MSG = @"WARNING: ";

// valid ad types (used during init)
static const NSArray *validAdTypes = @[
  @(ADTYPE_PANELVIEW),
  @(ADTYPE_FEEDVIEW),
  @(ADTYPE_FLEXIVIEW),
  @(ADTYPE_BANNER),
  @(ADTYPE_INTERSTITIAL),
  @(ADTYPE_REWARDEDVIDEO)
];

// unsupported on iOS (KIDOZ SDK not implemented yet)
static const NSArray *unsupportedAdTypes = @[
   @(ADTYPE_PANELVIEW),
   @(ADTYPE_FEEDVIEW),
   @(ADTYPE_FLEXIVIEW),
];

// key/value dictionary for ad objects (panel, feed etc)
static NSMutableDictionary *kidozObjects;

// class for holding ad instance info for kidozObjects
@interface KidozAdInfo: NSObject

@property (nonatomic, retain) NSString *adInstance;     // ad object
@property (nonatomic, assign) bool isLoaded;            // true when the ad object has loaded an ad
@property (nonatomic, assign) bool hasUIElement;        // true when ad object has a button or handle
@property (nonatomic, assign) bool isHiddenBySystem;    // true if force-hidden by 'ready' event
@property (nonatomic, assign) BANNER_POSITION bannerPositionToLoad;

- (instancetype)initWithAd:(NSString *)adInstance;

@end

// ----------------------------------------------------------------------------
// plugin class and delegate definitions
// ----------------------------------------------------------------------------

@interface KidozDelegate: NSObject

@property (nonatomic, assign) CoronaLuaRef coronaListener;          // Reference to the Lua listener
@property (nonatomic, assign) id<CoronaRuntime> coronaRuntime;      // Pointer to the Lua state

- (void)dispatchLuaEvent:(NSDictionary *)dict;

@end

@interface KidozStandardDelegate: KidozDelegate <KDZInitDelegate>
@end

@interface KidozInterstitialDelegate: KidozDelegate <KDZInterstitialDelegate>
@end

@interface KidozRewardedDelegate: KidozDelegate <KDZRewardedDelegate>
@end

@interface KidozBannerDelegate: KidozDelegate <KDZBannerDelegate>
@end
// ----------------------------------------------------------------------------

class KidozPlugin
{
  public:
    typedef KidozPlugin Self;
    static const char kName[];
    
  public:
    static int Open(lua_State *L);
    static int Finalizer(lua_State *L);
    static Self *ToLibrary(lua_State *L);
    
  protected:
    KidozPlugin();
    bool Initialize(void *platformContext);
    
  public:
    static int init(lua_State *L);
    static int load(lua_State *L);
    static int isLoaded(lua_State *L);
    static int show(lua_State *L);
    static int hide(lua_State *L);
	static int isShowing(lua_State *L);
	static int version(lua_State *L);

  private: // internal helper functions
    static void logMsg(lua_State *L, NSString *msgType,  NSString *errorMsg);
    static bool isSDKInitialized(lua_State *L);
    
  private:
    NSString *functionSignature;                            // used in logxxxMsg to identify function
    UIViewController *coronaViewController;                 // application's view controller
    UIWindow *coronaWindow;                                 // application's UIWindow
    KidozStandardDelegate *kidozStandardDelegate;           // Kidoz's delegate
    KidozInterstitialDelegate *kidozInterstitialDelegate;   // Kidoz's delegate for rewarded videos
    KidozRewardedDelegate *kidozRewardedDelegate;           // Kidoz's delegate for rewarded videos
	KidozBannerDelegate *kidozBannerDelegate;
};

const char KidozPlugin::kName[] = PLUGIN_NAME;

// ----------------------------------------------------------------------------
// helper functions
// ----------------------------------------------------------------------------

// log message to console
void
KidozPlugin::logMsg(lua_State *L, NSString* msgType, NSString* errorMsg)
{
  Self *context = ToLibrary(L);
  
  if (context) {
    Self& library = *context;
    
    NSString *functionID = [library.functionSignature copy];
    if (functionID.length > 0) {
      functionID = [functionID stringByAppendingString:@", "];
    }
    
    CoronaLuaLogPrefix(L, [msgType UTF8String], UTF8StringWithFormat(@"%@%@", functionID, errorMsg));
  }
}

// check if SDK calls can be made
bool
KidozPlugin::isSDKInitialized(lua_State *L)
{
  Self *context = ToLibrary(L);
  
  if (context) {
    Self& library = *context;
    
    if (library.kidozStandardDelegate.coronaListener == NULL) {
      logMsg(L, ERROR_MSG, @"kidoz.init() must be called before calling other API functions");
      return false;
    }
    
    return true;
  }
  
  return false;
}

// ----------------------------------------------------------------------------
// plugin implementation
// ----------------------------------------------------------------------------

int
KidozPlugin::Open(lua_State *L)
{
  // Register __gc callback
  const char kMetatableName[] = __FILE__; // Globally unique string to prevent collision
  CoronaLuaInitializeGCMetatable(L, kMetatableName, Finalizer);
  
  void *platformContext = CoronaLuaGetContext(L);
  
  // Set library as upvalue for each library function
  Self *library = new Self;
  
  if (library->Initialize(platformContext)) {
    // Functions in library
    static const luaL_Reg kFunctions[] = {
      {"init", init},
      {"load", load},
      {"isLoaded", isLoaded},
      {"show", show},
      {"isShowing", isShowing},
      {"hide", hide},
      {"version", version},
      {NULL, NULL}
    };
    
    // Register functions as closures, giving each access to the
    // 'library' instance via ToLibrary()
    {
      CoronaLuaPushUserdata(L, library, kMetatableName);
      luaL_openlib(L, kName, kFunctions, 1); // leave "library" on top of stack
    }
  }
  
  return 1;
}

int
KidozPlugin::Finalizer(lua_State *L)
{
    Self *library = (Self *)CoronaLuaToUserdata(L, 1);
  
    // Free the Lua listener
    CoronaLuaDeleteRef(L, library->kidozStandardDelegate.coronaListener);
    library->kidozStandardDelegate.coronaListener = NULL;
    library->kidozInterstitialDelegate.coronaListener = NULL;
    library->kidozRewardedDelegate.coronaListener = NULL;
	library->kidozBannerDelegate.coronaListener = NULL;

    // release all ad objects
    [kidozObjects removeAllObjects];
    kidozObjects = nil;
  
    library->kidozStandardDelegate = nil;
    library->kidozInterstitialDelegate = nil;
    library->kidozRewardedDelegate = nil;
	library->kidozBannerDelegate = nil;

    delete library;
		
    return 0;
}

KidozPlugin*
KidozPlugin::ToLibrary(lua_State *L)
{
  // library is pushed as part of the closure
  Self *library = (Self *)CoronaLuaToUserdata(L, lua_upvalueindex(1));
  return library;
}

KidozPlugin::KidozPlugin()
: coronaViewController(nil)
{
}

bool
KidozPlugin::Initialize(void *platformContext)
{
  bool shouldInit = (! coronaViewController);
  
  if (shouldInit) {
    id<CoronaRuntime> runtime = (__bridge id<CoronaRuntime>)platformContext;
    coronaViewController = runtime.appViewController;
    coronaWindow = runtime.appWindow;
    functionSignature = @"";
    
    kidozStandardDelegate = [KidozStandardDelegate new];
    kidozInterstitialDelegate = [KidozInterstitialDelegate new];
    kidozRewardedDelegate = [KidozRewardedDelegate new];
    kidozBannerDelegate = [KidozBannerDelegate new];
    kidozStandardDelegate.coronaRuntime = runtime;
    kidozInterstitialDelegate.coronaRuntime = runtime;
    kidozRewardedDelegate.coronaRuntime = runtime;
	kidozBannerDelegate.coronaRuntime = runtime;

    kidozObjects = [NSMutableDictionary new];
  }
  
  return shouldInit;
}

// [Lua] init(listener, options)
int
KidozPlugin::init( lua_State *L )
{
  Self *context = ToLibrary(L);
  
  if (! context) { // abort if no valid context
    return 0;
  }
  
  Self& library = *context;
  
  const char *publisherID = NULL;
  const char *securityToken = NULL;
  
  library.functionSignature = @"kidoz.init(listener, options)";
  
  // prevent init from being called twice
  if (library.kidozStandardDelegate.coronaListener != NULL) {
    logMsg(L, WARNING_MSG, @"init() cannot be called twice.");
    return 0;
  }
  
  // get number of arguments
  int nargs = lua_gettop(L);
  if (nargs != 2) {
    logMsg(L, ERROR_MSG, MsgFormat(@"Expected 2 arguments, got %d", nargs));
    return 0;
  }
  
  // Get listener key (required)
  if (CoronaLuaIsListener(L, 1, PROVIDER_NAME)) {
    library.kidozStandardDelegate.coronaListener = CoronaLuaNewRef(L, 1);
    library.kidozInterstitialDelegate.coronaListener = library.kidozStandardDelegate.coronaListener;
    library.kidozRewardedDelegate.coronaListener = library.kidozStandardDelegate.coronaListener;
	library.kidozBannerDelegate.coronaListener = library.kidozStandardDelegate.coronaListener;
  }
  else {
    logMsg(L, ERROR_MSG, MsgFormat(@"listener expected, got: %s", luaL_typename(L, 1)));
    return 0;
  }
  
  // check for options table (required)
  if (lua_type(L, 2) == LUA_TTABLE) {
    // traverse and verify all options
    for (lua_pushnil(L); lua_next(L, 2) != 0; lua_pop(L, 1)) {
      const char *key = lua_tostring(L, -2);
      
      if (UTF8IsEqual(key, "publisherID")) {
        if (lua_type(L, -1) == LUA_TSTRING) {
          publisherID = lua_tostring(L, -1);
        }
        else {
          logMsg(L, ERROR_MSG, MsgFormat(@"options.publisherID, expected string got: %s", luaL_typename(L, -1)));
          return 0;
        }
      }
      else if (UTF8IsEqual(key, "securityToken")) {
        if (lua_type(L, -1) == LUA_TSTRING) {
          securityToken = lua_tostring(L, -1);
        }
        else {
          logMsg(L, ERROR_MSG, MsgFormat(@"options.securityToken, expected string got: %s", luaL_typename(L, -1)));
          return 0;
        }
      }
      else {
        logMsg(L, ERROR_MSG, MsgFormat(@"Invalid option '%s'", key));
        return 0;
      }
    }
  }
  else { // no options table
    logMsg(L, ERROR_MSG, MsgFormat(@"options table expected, got %s", luaL_typename(L, 2)));
    return 0;
  }
  
  // validate
  if (publisherID == NULL) {
    logMsg(L, ERROR_MSG, MsgFormat(@"options.publisherID required"));
    return 0;
  }
  
  if (securityToken == NULL) {
    logMsg(L, ERROR_MSG, MsgFormat(@"options.securityToken required"));
    return 0;
  }
  
  // initialize the Kidoz SDK
    [[KidozSDK instance] initializeWithPublisherID:@(publisherID) securityToken:@(securityToken) withDelegate:library.kidozStandardDelegate];
  
  // log plugin version to console
  NSLog(@"%s: %s", PLUGIN_NAME, PLUGIN_VERSION);
  
  return 0;
}

// [Lua] load(adType, options)
int
KidozPlugin::load(lua_State *L)
{
  Self *context = ToLibrary(L);
  
  if (! context) { // abort if no valid context
    return 0;
  }
  
  Self& library = *context;
  
  library.functionSignature = @"kidoz.load(adType, options)";
  
  // don't continue if SDK isn't initialized
  if (! isSDKInitialized(L)) {
    return 0;
  }
  
  const char *adType = NULL;
  
  // check for ad type (required)
  if (lua_type(L, 1) == LUA_TSTRING) {
    adType = lua_tostring(L, 1);
  }
  else {
    logMsg(L, ERROR_MSG, MsgFormat(@"adType, expected string, got: %s", luaL_typename(L, 1)));
    return 0;
  }
  
  // check number of arguments
  int nargs = lua_gettop(L);
  int minArgs = 2;
  int maxArgs = 2;

  if ((UTF8IsEqual(adType, ADTYPE_INTERSTITIAL)) || (UTF8IsEqual(adType, ADTYPE_REWARDEDVIDEO))) {
    minArgs = 1;
    maxArgs = 1;
  }
  else if (UTF8IsEqual(adType, ADTYPE_FEEDVIEW) || UTF8IsEqual(adType, ADTYPE_BANNER)) {
    minArgs = 1;
    maxArgs = 2;
  }

  if ((nargs < minArgs) || (nargs > maxArgs)) {
    if (minArgs == maxArgs) {
      logMsg(L, ERROR_MSG, MsgFormat(@"Expected %d argument(s), got %d", maxArgs, nargs));
    }
    else {
      logMsg(L, ERROR_MSG, MsgFormat(@"Expected %d to %d arguments, got %d", minArgs, maxArgs, nargs));
    }
    return 0;
  }
  
  // check for options table
  if (! lua_isnoneornil(L, 2)) {
    if (lua_type(L, 2) != LUA_TTABLE) {
      logMsg(L, ERROR_MSG, MsgFormat(@"Options table expected, got: %s", luaL_typename(L, 2)));
      return 0;
    }
  }
  
  // check to see if valid type
  if (! [validAdTypes containsObject:@(adType)]) {
    logMsg(L, ERROR_MSG, MsgFormat(@"Invalid adType: '%s'", adType));
    return 0;
  }
  
  // check for unsupported ad types
  if ([unsupportedAdTypes containsObject:@(adType)]) {
    logMsg(L, WARNING_MSG, MsgFormat(@"The KIDOZ SDK does not support '%s' ads on iOS. (coming in a future version)", adType));
    return 0;
  }

  	BANNER_POSITION bannerPosition = BOTTOM_CENTER;
	const struct {
		BANNER_POSITION value;
		const char *name;
	} validBannerPositions[] = {
		  {TOP_LEFT, "topLeft"}, {TOP_CENTER, "top"}, {TOP_RIGHT, "topRight"}
		, {BOTTOM_LEFT, "bottomLeft"}, {BOTTOM_CENTER, "bottom"},{BOTTOM_RIGHT, "bottomRight"}
		, {NONE, "none"}
		, {NONE, NULL} // last entry should have name NULL
	};
	if(UTF8IsEqual(adType, ADTYPE_BANNER) && lua_istable(L, 2)) {
		lua_getfield(L, 2, "adPosition");
		const char* adPosition = lua_tostring(L, -1);
		for (int i=0; adPosition && validBannerPositions[i].name; i++) {
			if(UTF8IsEqual(validBannerPositions[i].name, adPosition)) {
				bannerPosition = validBannerPositions[i].value;
				break;
			}
		}
		lua_pop(L, 1);
	}
	
	
  // intersitital ------------------------------------------------------------------------------------------
  if (UTF8IsEqual(adType, ADTYPE_INTERSTITIAL)) {
    if (kidozObjects[@(adType)] == nil) {
        // create and load a new interstitial ad object and set its delegate
        [[KidozSDK instance] initializeInterstitialWithDelegate:library.kidozInterstitialDelegate];
        // add object to the dictionary for easy access
        KidozAdInfo *adInfo = [[KidozAdInfo alloc] initWithAd:@(ADTYPE_INTERSTITIAL)];
        kidozObjects[@(ADTYPE_INTERSTITIAL)] = adInfo;
    }
      // load an interstitial
      else if([[KidozSDK instance]isInterstitialInitialized]){
          [[KidozSDK instance]loadInterstitial];
      }
  }
  // rewarded video -----------------------------------------------------------------------------------------
  else if (UTF8IsEqual(adType, ADTYPE_REWARDEDVIDEO)) {
      if (kidozObjects[@(adType)] == nil) {
          // create and load a new interstitial ad object and set its delegate
          [[KidozSDK instance] initializeRewardedWithDelegate:library.kidozRewardedDelegate];
          // add object to the dictionary for easy access
          KidozAdInfo *adInfo = [[KidozAdInfo alloc] initWithAd:@(ADTYPE_REWARDEDVIDEO)];
          kidozObjects[@(ADTYPE_REWARDEDVIDEO)] = adInfo;
      }

      // load an interstitial
      else if([[KidozSDK instance]isRewardedInitialized]){
          [[KidozSDK instance]loadRewarded];
      }
    
  }  else if (UTF8IsEqual(adType, ADTYPE_BANNER)) {
	  KidozAdInfo *adInfo = kidozObjects[@(adType)];
	  if (adInfo == nil) {
		  // create and load a new interstitial ad object and set its delegate
		  [[KidozSDK instance] initializeBannerWithDelegate:library.kidozBannerDelegate withViewController:library.coronaViewController];
		  // add object to the dictionary for easy access
		  adInfo = [[KidozAdInfo alloc] initWithAd:@(ADTYPE_BANNER)];
		  kidozObjects[@(ADTYPE_BANNER)] = adInfo;
	  } else if([[KidozSDK instance] isBannerInitialized]) {
		  [[KidozSDK instance] setBannerPosition:bannerPosition];
		  [[KidozSDK instance] loadBanner];
	  }
	  adInfo.bannerPositionToLoad = bannerPosition;
  }
  
  return 0;
}

// [Lua] isLoaded(adType)
int
KidozPlugin::isLoaded(lua_State *L)
{
  using namespace Corona;
  
  Self *context = ToLibrary(L);
  
  if (! context) { // abort if no valid context
    return 0;
  }
  
  Self& library = *context;
  
  const char *adType = NULL;
  
  library.functionSignature = @"kidoz.isLoaded(adType)";
  
  // get number of arguments
  int nargs = lua_gettop(L);
  if (nargs != 1) {
    logMsg(L, ERROR_MSG, MsgFormat(@"Expected 1 argument, got %d", nargs));
    return 0;
  }
  
  // get ad type (required)
  if (lua_type(L, 1) == LUA_TSTRING) {
    adType = lua_tostring(L, 1);
  }
  else {
    logMsg(L, ERROR_MSG, MsgFormat(@"adType, expected string got: %s", luaL_typename(L, 1)));
    return 0;
  }
  
  // check to see if valid type
  if (! [validAdTypes containsObject:@(adType)]) {
    logMsg(L, ERROR_MSG, MsgFormat(@"Invalid adType: '%s'", adType));
    return 0;
  }
  
  // check if the ad is loaded and ready for display
  KidozAdInfo *adObject = kidozObjects[@(adType)];
  bool isLoaded = (adObject == nil) ? false : [adObject isLoaded];
  lua_pushboolean(L, isLoaded);
  
  return 1;
}

int
KidozPlugin::isShowing(lua_State *L)
{
	lua_pushboolean(L, [[KidozSDK instance] isBannerShowing]);
	return 1;
}

int
KidozPlugin::version(lua_State *L)
{
	lua_pushstring(L, [[[KidozSDK instance] getSdkVersion] UTF8String]);
	return 1;
}

// [Lua] hide(adType [, options])
int
KidozPlugin::hide(lua_State *L)
{
  const char *adType = NULL;
  bool forceClose = false;
  
  Self *context = ToLibrary(L);
  
  if (! context) { // abort if no valid context
    return 0;
  }
  
  Self& library = *context;
  
  library.functionSignature = @"kidoz.hide(adType, options)";
  
  // don't continue if SDK isn't initialized
  if (! isSDKInitialized(L)) {
    return 0;
  }
  
  // get number of arguments
  int nargs = lua_gettop(L);
  if ((nargs < 1) || (nargs > 2)) {
    logMsg(L, ERROR_MSG, MsgFormat(@"Expected 1-2 argument(s), got %d", nargs));
    return 0;
  }
  
  // check for ad type (required)
  if (lua_type(L, 1) == LUA_TSTRING) {
    adType = lua_tostring(L, 1);
  }
  else {
    logMsg(L, ERROR_MSG, MsgFormat(@"adType, expected string, got: %s", luaL_typename(L, 1)));
    return 0;
  }
  
  // check for options table (optional)
  if (! lua_isnoneornil(L, 2)) {
    if (lua_type(L, 2) == LUA_TTABLE) {
      // traverse and verify all options
      for (lua_pushnil(L); lua_next(L, 2) != 0; lua_pop(L, 1)) {
        const char *key = lua_tostring(L, -2);
        
        if (UTF8IsEqual(key, "close")) {
          if (lua_type(L, -1) == LUA_TBOOLEAN) {
            forceClose = lua_toboolean(L, -1);
          }
          else {
            logMsg(L, ERROR_MSG, MsgFormat(@"options.close (boolean) expected, got: %s", luaL_typename(L, -1)));
            return 0;
          }
        }
        else {
          logMsg(L, ERROR_MSG, MsgFormat(@"Invalid option '%s'", key));
          return 0;
        }
      }
    }
    else {
      logMsg(L, ERROR_MSG, MsgFormat(@"options table expected, got: %s", luaL_typename(L, 2)));
      return 0;
    }
  }
  
  // check to see if valid type
  if (! [validAdTypes containsObject:@(adType)]) {
    logMsg(L, ERROR_MSG, MsgFormat(@"Invalid adType: '%s'", adType));
    return 0;
  }
  
  // check for unsupported ad types
  if ([unsupportedAdTypes containsObject:@(adType)]) {
    logMsg(L, WARNING_MSG, MsgFormat(@"The KIDOZ SDK does not support '%s' ads on iOS (coming in a future version).", adType));
    return 0;
  }
  
  // get saved ad object
  KidozAdInfo *adObject = kidozObjects[@(adType)];
  
  // check if ad object has been loaded with kidoz.load()
  if ((adObject == nil) || (! [adObject isLoaded])) {
    logMsg(L, WARNING_MSG, MsgFormat(@"adType '%s' not loaded", adType));
  }
  else {
    logMsg(L, WARNING_MSG, MsgFormat(@"adType '%s' cannot be hidden", adType));
  }
	
  if (UTF8IsEqual(adType, ADTYPE_BANNER)) {
	  [[KidozSDK instance]hideBanner];
  }

  
  return 0;
}

// [Lua] show(adType)
int
KidozPlugin::show(lua_State *L)
{
  const char *adType = NULL;

  Self *context = ToLibrary(L);
  
  if (! context) { // abort if no valid context
    return 0;
  }
  
  Self& library = *context;
  
  library.functionSignature = @"kidoz.show(adType)";
  
  // don't continue if SDK isn't initialized
  if (! isSDKInitialized(L)) {
    return 0;
  }
  
  // get number of arguments
  int nargs = lua_gettop(L);
  if (nargs != 1) {
    logMsg(L, ERROR_MSG, MsgFormat(@"Expected 1 argument, got %d", nargs));
    return 0;
  }
  
  // check for ad type (required)
  if (lua_type(L, 1) == LUA_TSTRING) {
    adType = lua_tostring(L, 1);
  }
  else {
    logMsg(L, ERROR_MSG, MsgFormat(@"adType, expected string, got: %s", luaL_typename(L, 1)));
    return 0;
  }
  
  // check to see if valid type
  if (! [validAdTypes containsObject:@(adType)]) {
    logMsg(L, ERROR_MSG, MsgFormat(@"Invalid adType: '%s'", adType));
    return 0;
  }
  
  // check for unsupported ad types
  if ([unsupportedAdTypes containsObject:@(adType)]) {
    logMsg(L, WARNING_MSG, MsgFormat(@"The KIDOZ SDK does not support '%s' ads on iOS (coming in a future version).", adType));
    return 0;
  }
  
  // get the saved ad object
  KidozAdInfo *adObject = kidozObjects[@(adType)];
  
  // check if ad object has been loaded with kidoz.load()
  if ((adObject == nil) || (! [adObject isLoaded])) {
    logMsg(L, WARNING_MSG, MsgFormat(@"adType '%s' not loaded", adType));
  }
  else {
    // actions for interstitals
    if (UTF8IsEqual(adType, ADTYPE_INTERSTITIAL)) {
        if(([[KidozSDK instance]isInterstitialReady])){
            [[KidozSDK instance]showInterstitial];
        }
    }
    // actions for rewarded video
    else if (UTF8IsEqual(adType, ADTYPE_REWARDEDVIDEO)) {
        if(([[KidozSDK instance]isRewardedReady])){
            [[KidozSDK instance]showRewarded];
        }
    }
	else if (UTF8IsEqual(adType, ADTYPE_BANNER)) {
		if(([[KidozSDK instance]isBannerReady])){
			[[KidozSDK instance]showBanner];
		}
	}
  }
  
  return 0;
}

// ----------------------------------------------------------------------------
// delegate implementation
// ----------------------------------------------------------------------------

// Kidoz Delegate implementation
@implementation KidozDelegate

- (instancetype)init
{
  if (self = [super init]) {
    self.coronaListener = NULL;
    self.coronaRuntime = NULL;
  }
  
  return self;
}

// dispatch a new Lua event
- (void)dispatchLuaEvent:(NSDictionary *)dict
{
  [[NSOperationQueue mainQueue] addOperationWithBlock:^{
    lua_State *L = self.coronaRuntime.L;
    CoronaLuaRef coronaListener = self.coronaListener;
    bool hasErrorKey = false;
    
    // create new event
    CoronaLuaNewEvent(L, EVENT_NAME);
    
    for (NSString *key in dict) {
      CoronaLuaPushValue(L, [dict valueForKey:key]);
      lua_setfield(L, -2, key.UTF8String);
      
      if (! hasErrorKey) {
        hasErrorKey = [key isEqualToString:@(CoronaEventIsErrorKey())];
      }
    }
    
    // add error key if not in dict
    if (! hasErrorKey) {
      lua_pushboolean(L, false);
      lua_setfield(L, -2, CoronaEventIsErrorKey());
    }
    
    // add provider
    lua_pushstring(L, PROVIDER_NAME );
    lua_setfield(L, -2, CoronaEventProviderKey());
    
    CoronaLuaDispatchEvent(L, coronaListener, 0);
  }];
}

@end

@implementation KidozStandardDelegate

-(void)onInitSuccess{
    // create Corona event
    NSDictionary *coronaEvent = @{
                                  @(CoronaEventPhaseKey()) : PHASE_INIT
                                  };
    [self dispatchLuaEvent:coronaEvent];
}

-(void)onInitError:(NSString *)error{
    // create Corona event
    NSDictionary *coronaEvent = @{
                                  @(CoronaEventPhaseKey()) : PHASE_INIT_FAILED,
                                  @(CoronaEventIsErrorKey()) : @(true),
								  @(CoronaEventResponseKey()) : error
                                  };
    [self dispatchLuaEvent:coronaEvent];
}

@end

@implementation KidozInterstitialDelegate

-(void)interstitialDidInitialize{
    if([[KidozSDK instance]isRewardedInitialized]){
        [[KidozSDK instance]loadRewarded];
    }
}

-(void)interstitialDidClose{
    // create Corona event
    NSDictionary *coronaEvent = @{
                                  @(CoronaEventPhaseKey()) : PHASE_CLOSED,
                                  @(CoronaEventTypeKey()) : @(ADTYPE_INTERSTITIAL)
                                  };
    [self dispatchLuaEvent:coronaEvent];
}

-(void)interstitialDidOpen{
    // ad has been used. reset loaded flag
    KidozAdInfo *adObject = kidozObjects[@(ADTYPE_INTERSTITIAL)];
    adObject.isLoaded = false;

    // create Corona event
    NSDictionary *coronaEvent = @{
                                  @(CoronaEventPhaseKey()) : PHASE_DISPLAYED,
                                  @(CoronaEventTypeKey()) : @(ADTYPE_INTERSTITIAL)
                                  };
    [self dispatchLuaEvent:coronaEvent];
}

-(void)interstitialIsReady{
    // ad is ready. set loaded flag
    KidozAdInfo *adObject = kidozObjects[@(ADTYPE_INTERSTITIAL)];
    adObject.isLoaded = true;

    // create Corona event
    NSDictionary *coronaEvent = @{
                                  @(CoronaEventPhaseKey()) : PHASE_LOADED,
                                  @(CoronaEventTypeKey()) : @(ADTYPE_INTERSTITIAL)
                                  };
    [self dispatchLuaEvent:coronaEvent];
}

-(void)interstitialReturnedWithNoOffers{
	KidozAdInfo *adObject = kidozObjects[@(ADTYPE_INTERSTITIAL)];
	adObject.isLoaded = false;
	
	// create Corona event
	NSDictionary *coronaEvent = @{
								  @(CoronaEventPhaseKey()) : PHASE_FAILED,
								  @(CoronaEventTypeKey()) : @(ADTYPE_INTERSTITIAL),
								  @(CoronaEventIsErrorKey()) : @(true),
								  @(CoronaEventResponseKey()) : RESPONSE_LOAD_NO_FILL,
								  };
	[self dispatchLuaEvent:coronaEvent];
}

-(void)interstitialDidPause{

}

-(void)interstitialDidResume{

}

-(void)interstitialDidReciveError:(NSString*)errorMessage{

}

-(void)interstitialLoadFailed {
    // reset loaded flag
    KidozAdInfo *adObject = kidozObjects[@(ADTYPE_INTERSTITIAL)];
    adObject.isLoaded = false;

    // create Corona event
    NSDictionary *coronaEvent = @{
                                  @(CoronaEventPhaseKey()) : PHASE_FAILED,
                                  @(CoronaEventTypeKey()) : @(ADTYPE_INTERSTITIAL),
                                  @(CoronaEventIsErrorKey()) : @(true),
                                  @(CoronaEventResponseKey()) : RESPONSE_LOAD_FAILED
                                  };
    [self dispatchLuaEvent:coronaEvent];
}

- (void)interstitialLeftApplication {
}

@end

@implementation KidozRewardedDelegate

-(void)rewardedDidInitialize{
    if([[KidozSDK instance]isRewardedInitialized]){
        [[KidozSDK instance]loadRewarded];
    }
}

-(void)rewardedDidClose{
    // create Corona event
    NSDictionary *coronaEvent = @{
                                  @(CoronaEventPhaseKey()) : PHASE_CLOSED,
                                  @(CoronaEventTypeKey()) : @(ADTYPE_REWARDEDVIDEO)
                                  };
    [self dispatchLuaEvent:coronaEvent];
}

-(void)rewardedDidOpen{
    // ad has been used. reset loaded flag
    KidozAdInfo *adObject = kidozObjects[@(ADTYPE_REWARDEDVIDEO)];
    adObject.isLoaded = false;

    // create Corona event
    NSDictionary *coronaEvent = @{
                                  @(CoronaEventPhaseKey()) : PHASE_DISPLAYED,
                                  @(CoronaEventTypeKey()) : @(ADTYPE_REWARDEDVIDEO)
                                  };
    [self dispatchLuaEvent:coronaEvent];
}
-(void)rewardedIsReady{
    // ad is ready. set loaded flag
    KidozAdInfo *adObject = kidozObjects[@(ADTYPE_REWARDEDVIDEO)];
    adObject.isLoaded = true;

    // create Corona event
    NSDictionary *coronaEvent = @{
                                  @(CoronaEventPhaseKey()) : PHASE_LOADED,
                                  @(CoronaEventTypeKey()) : @(ADTYPE_REWARDEDVIDEO)
                                  };
    [self dispatchLuaEvent:coronaEvent];

}
-(void)rewardedReturnedWithNoOffers{
	KidozAdInfo *adObject = kidozObjects[@(ADTYPE_REWARDEDVIDEO)];
	adObject.isLoaded = false;
	
	// create Corona event
	NSDictionary *coronaEvent = @{
								  @(CoronaEventPhaseKey()) : PHASE_FAILED,
								  @(CoronaEventTypeKey()) : @(ADTYPE_REWARDEDVIDEO),
								  @(CoronaEventIsErrorKey()) : @(true),
								  @(CoronaEventResponseKey()) : RESPONSE_LOAD_NO_FILL,
								  };
	[self dispatchLuaEvent:coronaEvent];
}

-(void)rewardedDidPause{
}

-(void)rewardedDidResume{
}

-(void)rewardedDidReciveError:(NSString*)errorMessage{

}

-(void)rewardReceived{
    // create Corona event
    NSDictionary *coronaEvent = @{
                                  @(CoronaEventPhaseKey()) : PHASE_REWARD,
                                  @(CoronaEventTypeKey()) : @(ADTYPE_REWARDEDVIDEO)
                                  };
    [self dispatchLuaEvent:coronaEvent];
}

-(void)rewardedStarted{
}

- (void)rewardedLoadFailed {
    // reset loaded flag
    KidozAdInfo *adObject = kidozObjects[@(ADTYPE_REWARDEDVIDEO)];
    adObject.isLoaded = false;

    // create Corona event
    NSDictionary *coronaEvent = @{
                                  @(CoronaEventPhaseKey()) : PHASE_FAILED,
                                  @(CoronaEventTypeKey()) : @(ADTYPE_REWARDEDVIDEO),
                                  @(CoronaEventIsErrorKey()) : @(true),
                                  @(CoronaEventResponseKey()) : RESPONSE_LOAD_FAILED
                                  };
    [self dispatchLuaEvent:coronaEvent];
}

- (void)rewardedLeftApplication {
}

@end

@implementation KidozBannerDelegate

- (void)bannerDidClose {
	NSDictionary *coronaEvent = @{
								  @(CoronaEventPhaseKey()) : PHASE_CLOSED,
								  @(CoronaEventTypeKey()) : @(ADTYPE_BANNER)
								  };
	[self dispatchLuaEvent:coronaEvent];
}

- (void)bannerDidInitialize {
	KidozAdInfo *adObject = kidozObjects[@(ADTYPE_BANNER)];
	[[KidozSDK instance] setBannerPosition:adObject.bannerPositionToLoad];
	[[KidozSDK instance] loadBanner];
}

- (void)bannerDidOpen {
	KidozAdInfo *adObject = kidozObjects[@(ADTYPE_BANNER)];
	adObject.isLoaded = false;
	
	// create Corona event
	NSDictionary *coronaEvent = @{
								  @(CoronaEventPhaseKey()) : PHASE_DISPLAYED,
								  @(CoronaEventTypeKey()) : @(ADTYPE_BANNER)
								  };
	[self dispatchLuaEvent:coronaEvent];

}

- (void)bannerDidReciveError:(NSString *)errorMessage {

}

- (void)bannerIsReady {
	KidozAdInfo *adObject = kidozObjects[@(ADTYPE_BANNER)];
	adObject.isLoaded = true;
	
	// create Corona event
	NSDictionary *coronaEvent = @{
								  @(CoronaEventPhaseKey()) : PHASE_LOADED,
								  @(CoronaEventTypeKey()) : @(ADTYPE_BANNER)
								  };
	[self dispatchLuaEvent:coronaEvent];

}

- (void)bannerLeftApplication {

}

- (void)bannerLoadFailed {
	KidozAdInfo *adObject = kidozObjects[@(ADTYPE_BANNER)];
	adObject.isLoaded = false;
	
	// create Corona event
	NSDictionary *coronaEvent = @{
								  @(CoronaEventPhaseKey()) : PHASE_FAILED,
								  @(CoronaEventTypeKey()) : @(ADTYPE_BANNER),
								  @(CoronaEventIsErrorKey()) : @(true),
								  @(CoronaEventResponseKey()) : RESPONSE_LOAD_FAILED
								  };
	[self dispatchLuaEvent:coronaEvent];

}

- (void)bannerReturnedWithNoOffers {
	KidozAdInfo *adObject = kidozObjects[@(ADTYPE_BANNER)];
	adObject.isLoaded = false;
	
	// create Corona event
	NSDictionary *coronaEvent = @{
								  @(CoronaEventPhaseKey()) : PHASE_FAILED,
								  @(CoronaEventTypeKey()) : @(ADTYPE_BANNER),
								  @(CoronaEventIsErrorKey()) : @(true),
								  @(CoronaEventResponseKey()) : RESPONSE_LOAD_NO_FILL,
								  };
	[self dispatchLuaEvent:coronaEvent];

}

@end

// ----------------------------------------------------------------------------

@implementation KidozAdInfo

- (instancetype)init
{
  return [self initWithAd:nil];
}

- (instancetype)initWithAd:(NSString *)adInstance
{
  if (self = [super init]) {
    self.adInstance = adInstance;
    self.isLoaded = false;
    self.hasUIElement = false;
    self.isHiddenBySystem = false;
  }
  
  return self;
}

@end

// ----------------------------------------------------------------------------

CORONA_EXPORT int luaopen_plugin_kidoz( lua_State *L )
{
  return KidozPlugin::Open( L );
}
