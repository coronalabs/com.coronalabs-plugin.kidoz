//
//  KidozOMIDSessionManager.h
//  KidozSDK
//
//  Created by Maria Yelfimova on 03/06/2024.
//

#import <Foundation/Foundation.h>

@class OMIDAdSession;
@class OMIDKidoznetAdSession;

@interface KidozOMIDSessionManager : NSObject
- (instancetype)initWithOMIDKidoznetAdSession:(OMIDKidoznetAdSession *)omidKidoznetAdSession;
- (void)finish;
- (void)start;

@end
