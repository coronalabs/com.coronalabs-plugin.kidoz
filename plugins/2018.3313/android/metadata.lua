local metadata =
{
    plugin =
    {
        format = 'jar',
        manifest =
        {
            permissions = {},

            usesPermissions =
            {
                "android.permission.INTERNET",
                "android.permission.ACCESS_NETWORK_STATE"
            },

            usesFeatures =
            {
            },

            applicationChildElements =
            {
                [[
				<receiver android:name="com.kidoz.sdk.api.receivers.SdkReceiver" >
					<intent-filter>
						<action android:name="android.intent.action.PACKAGE_ADDED" android:enabled="true"/>
						<data android:scheme="package" />
					</intent-filter>
				</receiver>

				<activity android:name="com.kidoz.sdk.api.ui_views.interstitial.KidozAdActivity"
					android:configChanges="keyboard|keyboardHidden|orientation|screenLayout|uiMode|screenSize|smallestScreenSize"/>
                ]]
            },
        }
    },

    coronaManifest = {
        dependencies = {
            ["shared.android.support.v4"] = "com.coronalabs",
            ["shared.android.support.v7.appcompat"] = "com.coronalabs"
        }
    }
}

return metadata
