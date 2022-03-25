--
--  main.lua
--  KIDOZ Sample App
--
--  Copyright (c) 2016 Corona Labs Inc. All rights reserved.
--

local kidoz = require( "plugin.kidoz" )
local widget = require( "widget" )
local json = require("json")

----------------------------------------------------------------------
-- basic UI setup
----------------------------------------------------------------------
display.setStatusBar( display.HiddenStatusBar )
display.setDefault( "background", 0.2, 0.2, 0.2 )

local kidozLogo = display.newImage("logo_retina.png")
kidozLogo.anchorY = 0
kidozLogo.x, kidozLogo.y = display.contentCenterX, 0
kidozLogo:scale(0.3, 0.3)

local subTitle = display.newText {
  text = "plugin for Corona SDK",
  x = display.contentCenterX,
  y = 70,
  font = display.systemFont,
  fontSize = 12
}

local eventText = native.newTextBox( display.contentCenterX, display.contentHeight - 150, 310, 200)
eventText.placeholder = "Event data will appear here"

local processEventTable = function(event)
  local logString = json.prettify(event):gsub("\\","")
  logString = "\nPHASE: "..event.phase.." - - - - - - - - - - - -\n" .. logString
  print(logString)
  return logString
end

----------------------------------------------------------------------
-- plugin
----------------------------------------------------------------------
local interstitialButton
local rewardedButton
local bannerButton
local backgroundMusic

local kidozListener = function(event)
  eventText.text = processEventTable(event) .. eventText.text

  if event.phase == "loaded" then
    if event.type == "interstitial" then
      interstitialButton:setLabel("Show Interstitial")
    elseif event.type == "rewardedVideo" then
      rewardedButton:setLabel("Show Rewarded Video")
    elseif event.type == "banner" then
      bannerButton:setLabel("Show Banner")
    else -- show the ad unit
      kidoz.show(event.type)
    end
  end

  if event.phase == "failed" then
    if event.type == "interstitial" then
      interstitialButton:setLabel("Load Interstitial")
    elseif event.type == "rewardedVideo" then
      rewardedButton:setLabel("Load Rewarded Video")
    elseif event.type == "banner" then
      bannerButton:setLabel("Load Banner")
    end
  end

  if event.phase == "displayed" then
    if event.type == "banner" then
      bannerButton:setLabel("Hide Banner")
    end
  end
end

kidoz.init(kidozListener, {
  -- publisherID = "7",
  -- securityToken = "QVBIh5K3tr1AxO4A1d4ZWx1YAe5567os"
  publisherID = "5",
  securityToken = "i0tnrdwdtq0dm36cqcpg6uyuwupkj76s"
})

-- kidoz.load("flexiView", {
--   adPosition = "center",
--   draggable = true,
--   closable = true
-- })

-- kidoz.load("panelView", {
--   adPosition = "bottom",
--   handlePosition = "center"
-- })

interstitialButton = widget.newButton {
  label = "Load Interstitial",
  labelColor = {default={0.9}, over={1}},
  shape = "roundedRect",
  width = 200,
  height = 35,
  fillColor = {default={1, 0.2, 0.5, 0.8}, over={1, 0.2, 0.5}},
  strokeColor = {default={0.9}, over={0.9}},
  strokeWidth = 1,
  onRelease = function(event)
    if not kidoz.isLoaded("interstitial") then
      kidoz.load("interstitial")
      interstitialButton:setLabel("Loading...")
    else
      kidoz.show("interstitial")
      interstitialButton:setLabel("Load Interstitial")
    end
  end
}
interstitialButton.x, interstitialButton.y = display.contentCenterX, 155

rewardedButton = widget.newButton {
  label = "Load Rewarded Video",
  labelColor = {default={0.9}, over={1}},
  shape = "roundedRect",
  width = 200,
  height = 35,
  fillColor = {default={1, 0.2, 0.5, 0.8}, over={1, 0.2, 0.5}},
  strokeColor = {default={0.9}, over={0.9}},
  strokeWidth = 1,
  onRelease = function(event)
    if not kidoz.isLoaded("rewardedVideo") then
      kidoz.load("rewardedVideo")
      rewardedButton:setLabel("Loading...")
    else
      kidoz.show("rewardedVideo")
      rewardedButton:setLabel("Load Rewarded Video")
    end
  end
}
rewardedButton.x, rewardedButton.y = display.contentCenterX, 110

bannerButton = widget.newButton {
  label = "Load Banner",
  labelColor = {default={0.9}, over={1}},
  shape = "roundedRect",
  width = 140,
  height = 35,
  fillColor = {default={1, 0.5, 0.1, 0.8}, over={1, 0.5, 0.1, 1}},
  strokeColor = {default={0.9}, over={0.9}},
  strokeWidth = 1,
  onRelease = function(event)
     if bannerButton:getLabel() == "Hide Banner" then
       kidoz.hide("banner")
       bannerButton:setLabel("Load Banner")
     elseif not kidoz.isLoaded("banner") then
       kidoz.load("banner", {adPosition="topLeft"})
       bannerButton:setLabel("Loading Banner...")
     else
       kidoz.show("banner")
     end
  end
}
bannerButton.x, bannerButton.y = display.contentCenterX , 200
