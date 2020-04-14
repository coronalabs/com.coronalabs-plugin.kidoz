-- Kidoz plugin

local Library = require "CoronaLibrary"

-- Create library
local lib = Library:new{ name="plugin.kidoz", publisherId="com.coronalabs", version=1 }

-------------------------------------------------------------------------------
-- BEGIN
-------------------------------------------------------------------------------

-- This sample implements the following Lua:
-- 
--    local PLUGIN_NAME = require "plugin_PLUGIN_NAME"
--    PLUGIN_NAME:showPopup()
--    

local function showWarning(functionName)
    print( functionName .. " WARNING: The KIDOZ plugin is only supported on Android & iOS devices. Please build for device");
end

function lib.init()
    showWarning("kidoz.init()")
end

function lib.load()
    showWarning("kidoz.load()")
end

function lib.isLoaded()
    showWarning("kidoz.isLoaded()")
end

function lib.show()
    showWarning("kidoz.show()")
end

function lib.hide()
    showWarning("kidoz.hide()")
end

-------------------------------------------------------------------------------
-- END
-------------------------------------------------------------------------------

-- Return an instance
return lib
