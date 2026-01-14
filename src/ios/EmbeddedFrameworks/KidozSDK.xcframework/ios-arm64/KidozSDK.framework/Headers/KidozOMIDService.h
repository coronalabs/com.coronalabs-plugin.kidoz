//
//  KidozOMIDService.h
//  KidozSDK
//
//  Created by Maria Yelfimova on 03/06/2024.
//

#import <Foundation/Foundation.h>
#import <WebKit/WebKit.h>

@class KidozOMIDSessionManager;
@class OMIDAdSession;
@class OMIDSDK;

@interface KidozOMIDService : NSObject

@property (class, nonatomic, readonly) NSString * _Nonnull partnerName;

+ (void)activateOMID;
+ (KidozOMIDSessionManager *_Nonnull)createSession:(WKWebView * _Nonnull)webview;

@end
