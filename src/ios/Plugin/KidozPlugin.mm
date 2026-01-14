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

// Import the KidozSDK framework - this will automatically include Swift generated interfaces
#import <KidozSDK/KidozSDK-Swift.h>

// If @import doesn't work, use traditional import
#ifndef __KidozSDK_h
#import <KidozSDK/KidozSDK.h>
#endif

// Forward class declarations for better compile-time resolution
@class Kidoz;
@class KidozInterstitialAd;
@class KidozRewardedAd;
@class KidozBannerView;
@class KidozError;

// Forward protocol declarations
@protocol KidozInitDelegate;
@protocol KidozInterstitialDelegate;
@protocol KidozRewardedDelegate;
@protocol KidozBannerDelegate;

// some macros to make life easier, and code more readable
#define UTF8StringWithFormat(format, ...) [[NSString stringWithFormat:format, ##__VA_ARGS__] UTF8String]
#define UTF8IsEqual(utf8str1, utf8str2) (strcmp(utf8str1, utf8str2) == 0)
#define UTF8Concat(utf8str1, utf8str2) [[NSString stringWithFormat:@"%s%s", utf8str1, utf8str2] UTF8String]
#define MsgFormat(format, ...) [NSString stringWithFormat:format, ##__VA_ARGS__]

// ----------------------------------------------------------------------------
// Plugin Constants
// ----------------------------------------------------------------------------

#define PLUGIN_NAME     "plugin.kidoz"    // Class plugin name
#define PLUGIN_VERSION  "3.0"

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

// Store actual ad instances
static KidozInterstitialAd *currentInterstitialAd = nil;
static KidozRewardedAd *currentRewardedAd = nil;
static KidozBannerView *currentBannerView = nil;

// class for holding ad instance info for kidozObjects
@interface KidozAdInfo: NSObject

@property (nonatomic, retain) NSString *adInstance;     // ad object
@property (nonatomic, assign) bool isLoaded;            // true when the ad object has loaded an ad

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

@interface KidozStandardDelegate: KidozDelegate <KidozInitDelegate>
@end

@interface KidozInterstitialDelegate: KidozDelegate <KidozInterstitialDelegate>
@end

@interface KidozRewardedDelegate: KidozDelegate <KidozRewardedDelegate>
@end

@interface KidozBannerDelegate: KidozDelegate <KidozBannerDelegate>
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
    KidozInterstitialDelegate *kidozInterstitialDelegate;   // Kidoz's delegate for interstitials
    KidozRewardedDelegate *kidozRewardedDelegate;           // Kidoz's delegate for rewarded videos
    KidozBannerDelegate *kidozBannerDelegate;               // Kidoz's delegate for banners
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
    
    currentInterstitialAd = nil;
    currentRewardedAd = nil;
    currentBannerView = nil;
  
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
  
  // initialize the Kidoz SDK using new API
  [Kidoz.instance initializeWithPublisherID:@(publisherID) securityToken:@(securityToken) delegate:library.kidozStandardDelegate];
  
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

  // intersitital ------------------------------------------------------------------------------------------
  if (UTF8IsEqual(adType, ADTYPE_INTERSTITIAL)) {
    if (![Kidoz.instance isSDKInitialized]) {
      logMsg(L, ERROR_MSG, @"SDK not initialized");
      return 0;
    }
    
    // Load using new static method
    [KidozInterstitialAd loadWithDelegate:library.kidozInterstitialDelegate];
    
    // Create/update ad info
    KidozAdInfo *adInfo = kidozObjects[@(ADTYPE_INTERSTITIAL)];
    if (adInfo == nil) {
      adInfo = [[KidozAdInfo alloc] initWithAd:@(ADTYPE_INTERSTITIAL)];
      kidozObjects[@(ADTYPE_INTERSTITIAL)] = adInfo;
    }
  }
  // rewarded video -----------------------------------------------------------------------------------------
  else if (UTF8IsEqual(adType, ADTYPE_REWARDEDVIDEO)) {
    if (![Kidoz.instance isSDKInitialized]) {
      logMsg(L, ERROR_MSG, @"SDK not initialized");
      return 0;
    }
    
    // Load using new static method
    [KidozRewardedAd loadWithDelegate:library.kidozRewardedDelegate];
    
    // Create/update ad info
    KidozAdInfo *adInfo = kidozObjects[@(ADTYPE_REWARDEDVIDEO)];
    if (adInfo == nil) {
      adInfo = [[KidozAdInfo alloc] initWithAd:@(ADTYPE_REWARDEDVIDEO)];
      kidozObjects[@(ADTYPE_REWARDEDVIDEO)] = adInfo;
    }
  }
  // banner -----------------------------------------------------------------------------------------
  else if (UTF8IsEqual(adType, ADTYPE_BANNER)) {
    if (![Kidoz.instance isSDKInitialized]) {
      logMsg(L, ERROR_MSG, @"SDK not initialized");
      return 0;
    }
    
    // Create banner view if needed
    if (currentBannerView == nil) {
      currentBannerView = [[KidozBannerView alloc] init];
      currentBannerView.delegate = library.kidozBannerDelegate;
      
      KidozAdInfo *adInfo = [[KidozAdInfo alloc] initWithAd:@(ADTYPE_BANNER)];
      kidozObjects[@(ADTYPE_BANNER)] = adInfo;
    }
    
    // Load banner
    [currentBannerView load];
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
  // For banner visibility check
  bool isShowing = (currentBannerView != nil && currentBannerView.superview != nil);
  lua_pushboolean(L, isShowing);
  return 1;
}

int
KidozPlugin::version(lua_State *L)
{
  lua_pushstring(L, PLUGIN_VERSION);
  return 1;
}

// [Lua] hide(adType [, options])
int
KidozPlugin::hide(lua_State *L)
{
  const char *adType = NULL;
  
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
  
  // Only banners can be hidden
  if (UTF8IsEqual(adType, ADTYPE_BANNER)) {
    if (currentBannerView != nil) {
      [currentBannerView close];
    }
  }
  else {
    logMsg(L, WARNING_MSG, MsgFormat(@"adType '%s' cannot be hidden", adType));
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
    // actions for interstitials
    if (UTF8IsEqual(adType, ADTYPE_INTERSTITIAL)) {
      if (currentInterstitialAd != nil && [currentInterstitialAd isLoaded]) {
        [currentInterstitialAd showWithViewController:library.coronaViewController];
      }
    }
    // actions for rewarded video
    else if (UTF8IsEqual(adType, ADTYPE_REWARDEDVIDEO)) {
      if (currentRewardedAd != nil && [currentRewardedAd isLoaded]) {
        [currentRewardedAd showWithViewController:library.coronaViewController];
      }
    }
    // banner is shown automatically after load
    else if (UTF8IsEqual(adType, ADTYPE_BANNER)) {
      // Banner shows automatically after load, this is a no-op
      logMsg(L, WARNING_MSG, @"Banner is shown automatically after load");
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

- (void)onInitSuccess {
  NSDictionary *coronaEvent = @{
    @(CoronaEventPhaseKey()) : PHASE_INIT
  };
  [self dispatchLuaEvent:coronaEvent];
}

- (void)onInitError:(NSString *)error {
  NSDictionary *coronaEvent = @{
    @(CoronaEventPhaseKey()) : PHASE_INIT_FAILED,
    @(CoronaEventIsErrorKey()) : @(true),
    @(CoronaEventResponseKey()) : error
  };
  [self dispatchLuaEvent:coronaEvent];
}

@end

@implementation KidozInterstitialDelegate

- (void)onInterstitialAdLoadedWithKidozInterstitialAd:(KidozInterstitialAd *)kidozInterstitialAd {
  // Store the loaded ad
  currentInterstitialAd = kidozInterstitialAd;
  
  // Mark as loaded
  KidozAdInfo *adObject = kidozObjects[@(ADTYPE_INTERSTITIAL)];
  if (adObject != nil) {
    adObject.isLoaded = true;
  }
  
  NSDictionary *coronaEvent = @{
    @(CoronaEventPhaseKey()) : PHASE_LOADED,
    @(CoronaEventTypeKey()) : @(ADTYPE_INTERSTITIAL)
  };
  [self dispatchLuaEvent:coronaEvent];
}

- (void)onInterstitialAdFailedToLoadWithKidozError:(KidozError *)kidozError {
  KidozAdInfo *adObject = kidozObjects[@(ADTYPE_INTERSTITIAL)];
  if (adObject != nil) {
    adObject.isLoaded = false;
  }
  
  NSString *errorMsg = kidozError.message ?: RESPONSE_LOAD_FAILED;
  
  NSDictionary *coronaEvent = @{
    @(CoronaEventPhaseKey()) : PHASE_FAILED,
    @(CoronaEventTypeKey()) : @(ADTYPE_INTERSTITIAL),
    @(CoronaEventIsErrorKey()) : @(true),
    @(CoronaEventResponseKey()) : errorMsg
  };
  [self dispatchLuaEvent:coronaEvent];
}

- (void)onInterstitialAdShownWithKidozInterstitialAd:(KidozInterstitialAd *)kidozInterstitialAd {
  KidozAdInfo *adObject = kidozObjects[@(ADTYPE_INTERSTITIAL)];
  if (adObject != nil) {
    adObject.isLoaded = false;
  }
  
  NSDictionary *coronaEvent = @{
    @(CoronaEventPhaseKey()) : PHASE_DISPLAYED,
    @(CoronaEventTypeKey()) : @(ADTYPE_INTERSTITIAL)
  };
  [self dispatchLuaEvent:coronaEvent];
}

- (void)onInterstitialAdFailedToShowWithKidozInterstitialAd:(KidozInterstitialAd *)kidozInterstitialAd kidozError:(KidozError *)kidozError {
  NSString *errorMsg = kidozError.message ?: @"Failed to show";
  
  NSDictionary *coronaEvent = @{
    @(CoronaEventPhaseKey()) : PHASE_FAILED,
    @(CoronaEventTypeKey()) : @(ADTYPE_INTERSTITIAL),
    @(CoronaEventIsErrorKey()) : @(true),
    @(CoronaEventResponseKey()) : errorMsg
  };
  [self dispatchLuaEvent:coronaEvent];
}

- (void)onInterstitialImpressionWithKidozInterstitialAd:(KidozInterstitialAd *)kidozInterstitialAd {
  // Optional: dispatch impression event if needed
}

- (void)onInterstitialAdClosedWithKidozInterstitialAd:(KidozInterstitialAd *)kidozInterstitialAd {
  currentInterstitialAd = nil;
  
  NSDictionary *coronaEvent = @{
    @(CoronaEventPhaseKey()) : PHASE_CLOSED,
    @(CoronaEventTypeKey()) : @(ADTYPE_INTERSTITIAL)
  };
  [self dispatchLuaEvent:coronaEvent];
}

@end

@implementation KidozRewardedDelegate

- (void)onRewardedAdLoadedWithKidozRewardedAd:(KidozRewardedAd *)kidozRewardedAd {
  // Store the loaded ad
  currentRewardedAd = kidozRewardedAd;
  
  // Mark as loaded
  KidozAdInfo *adObject = kidozObjects[@(ADTYPE_REWARDEDVIDEO)];
  if (adObject != nil) {
    adObject.isLoaded = true;
  }
  
  NSDictionary *coronaEvent = @{
    @(CoronaEventPhaseKey()) : PHASE_LOADED,
    @(CoronaEventTypeKey()) : @(ADTYPE_REWARDEDVIDEO)
  };
  [self dispatchLuaEvent:coronaEvent];
}

- (void)onRewardedAdFailedToLoadWithKidozError:(KidozError *)kidozError {
  KidozAdInfo *adObject = kidozObjects[@(ADTYPE_REWARDEDVIDEO)];
  if (adObject != nil) {
    adObject.isLoaded = false;
  }
  
  NSString *errorMsg = kidozError.message ?: RESPONSE_LOAD_FAILED;
  
  NSDictionary *coronaEvent = @{
    @(CoronaEventPhaseKey()) : PHASE_FAILED,
    @(CoronaEventTypeKey()) : @(ADTYPE_REWARDEDVIDEO),
    @(CoronaEventIsErrorKey()) : @(true),
    @(CoronaEventResponseKey()) : errorMsg
  };
  [self dispatchLuaEvent:coronaEvent];
}

- (void)onRewardedAdShownWithKidozRewardedAd:(KidozRewardedAd *)kidozRewardedAd {
  KidozAdInfo *adObject = kidozObjects[@(ADTYPE_REWARDEDVIDEO)];
  if (adObject != nil) {
    adObject.isLoaded = false;
  }
  
  NSDictionary *coronaEvent = @{
    @(CoronaEventPhaseKey()) : PHASE_DISPLAYED,
    @(CoronaEventTypeKey()) : @(ADTYPE_REWARDEDVIDEO)
  };
  [self dispatchLuaEvent:coronaEvent];
}

- (void)onRewardedAdFailedToShowWithKidozRewardedAd:(KidozRewardedAd *)kidozRewardedAd kidozError:(KidozError *)kidozError {
  NSString *errorMsg = kidozError.message ?: @"Failed to show";
  
  NSDictionary *coronaEvent = @{
    @(CoronaEventPhaseKey()) : PHASE_FAILED,
    @(CoronaEventTypeKey()) : @(ADTYPE_REWARDEDVIDEO),
    @(CoronaEventIsErrorKey()) : @(true),
    @(CoronaEventResponseKey()) : errorMsg
  };
  [self dispatchLuaEvent:coronaEvent];
}

- (void)onRewardReceivedWithKidozRewardedAd:(KidozRewardedAd *)kidozRewardedAd {
  NSDictionary *coronaEvent = @{
    @(CoronaEventPhaseKey()) : PHASE_REWARD,
    @(CoronaEventTypeKey()) : @(ADTYPE_REWARDEDVIDEO)
  };
  [self dispatchLuaEvent:coronaEvent];
}

- (void)onRewardedImpressionWithKidozRewardedAd:(KidozRewardedAd *)kidozRewardedAd {
  // Optional: dispatch impression event if needed
}

- (void)onRewardedAdClosedWithKidozRewardedAd:(KidozRewardedAd *)kidozRewardedAd {
  currentRewardedAd = nil;
  
  NSDictionary *coronaEvent = @{
    @(CoronaEventPhaseKey()) : PHASE_CLOSED,
    @(CoronaEventTypeKey()) : @(ADTYPE_REWARDEDVIDEO)
  };
  [self dispatchLuaEvent:coronaEvent];
}

@end

@implementation KidozBannerDelegate

- (void)onBannerAdLoadedWithKidozBannerView:(KidozBannerView *)kidozBannerView {
  KidozAdInfo *adObject = kidozObjects[@(ADTYPE_BANNER)];
  if (adObject != nil) {
    adObject.isLoaded = true;
  }
  
  NSDictionary *coronaEvent = @{
    @(CoronaEventPhaseKey()) : PHASE_LOADED,
    @(CoronaEventTypeKey()) : @(ADTYPE_BANNER)
  };
  [self dispatchLuaEvent:coronaEvent];
}

- (void)onBannerAdFailedToLoadWithKidozBannerView:(KidozBannerView *)kidozBannerView error:(KidozError *)error {
  KidozAdInfo *adObject = kidozObjects[@(ADTYPE_BANNER)];
  if (adObject != nil) {
    adObject.isLoaded = false;
  }
  
  NSString *errorMsg = error.message ?: RESPONSE_LOAD_FAILED;
  
  NSDictionary *coronaEvent = @{
    @(CoronaEventPhaseKey()) : PHASE_FAILED,
    @(CoronaEventTypeKey()) : @(ADTYPE_BANNER),
    @(CoronaEventIsErrorKey()) : @(true),
    @(CoronaEventResponseKey()) : errorMsg
  };
  [self dispatchLuaEvent:coronaEvent];
}

- (void)onBannerAdShownWithKidozBannerView:(KidozBannerView *)kidozBannerView {
  NSDictionary *coronaEvent = @{
    @(CoronaEventPhaseKey()) : PHASE_DISPLAYED,
    @(CoronaEventTypeKey()) : @(ADTYPE_BANNER)
  };
  [self dispatchLuaEvent:coronaEvent];
}

- (void)onBannerAdFailedToShowWithKidozBannerView:(KidozBannerView *)kidozBannerView error:(KidozError *)error {
  NSString *errorMsg = error.message ?: @"Failed to show";
  
  NSDictionary *coronaEvent = @{
    @(CoronaEventPhaseKey()) : PHASE_FAILED,
    @(CoronaEventTypeKey()) : @(ADTYPE_BANNER),
    @(CoronaEventIsErrorKey()) : @(true),
    @(CoronaEventResponseKey()) : errorMsg
  };
  [self dispatchLuaEvent:coronaEvent];
}

- (void)onBannerAdImpressionWithKidozBannerView:(KidozBannerView *)kidozBannerView {
  // Optional: dispatch impression event if needed
}

- (void)onBannerAdClosedWithKidozBannerView:(KidozBannerView *)kidozBannerView {
  NSDictionary *coronaEvent = @{
    @(CoronaEventPhaseKey()) : PHASE_CLOSED,
    @(CoronaEventTypeKey()) : @(ADTYPE_BANNER)
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
  }
  
  return self;
}

@end

// ----------------------------------------------------------------------------

CORONA_EXPORT int luaopen_plugin_kidoz( lua_State *L )
{
  return KidozPlugin::Open( L );
}
