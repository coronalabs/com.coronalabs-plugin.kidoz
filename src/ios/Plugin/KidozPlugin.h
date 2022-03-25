//
//  KidozPlugin.h
//  Kidoz Plugin
//
//  Copyright (c) 2016 Corona Labs Inc. All rights reserved.
//

#ifndef _KidozPlugin_H_
#define _KidozPlugin_H_

#import "CoronaLua.h"
#import "CoronaMacros.h"

// This corresponds to the name of the library, e.g. [Lua] require "plugin.library"
// where the '.' is replaced with '_'
CORONA_EXPORT int luaopen_plugin_kidoz( lua_State *L );

#endif // _KidozPlugin_H_
