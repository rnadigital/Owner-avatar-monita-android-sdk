// Copyright RNA Digital PTY LTD
package ai.monita.sdk.internal

import android.content.Context
import android.os.Build

/**
 * Environment detection for the monita_env event: class presence checks for
 * well known vendor analytics SDKs and consent management platform SDKs,
 * plus app version, OS version, coarse device model, and SDK version.
 * Presence only; no per user detail is collected here.
 */
internal class EnvDetector(
    private val context: Context,
    private val sdkVersion: String,
    private val hasTcfString: () -> Boolean,
) {

    private val vendorSdkClasses = linkedMapOf(
        "firebase_analytics" to listOf("com.google.firebase.analytics.FirebaseAnalytics"),
        "facebook" to listOf("com.facebook.appevents.AppEventsLogger"),
        "adjust" to listOf("com.adjust.sdk.Adjust"),
        "appsflyer" to listOf("com.appsflyer.AppsFlyerLib"),
        "branch" to listOf("io.branch.referral.Branch"),
        "amplitude" to listOf("com.amplitude.android.Amplitude", "com.amplitude.api.AmplitudeClient"),
        "mixpanel" to listOf("com.mixpanel.android.mpmetrics.MixpanelAPI"),
        "segment" to listOf(
            "com.segment.analytics.kotlin.core.Analytics",
            "com.segment.analytics.Analytics",
        ),
        "braze" to listOf("com.braze.Braze"),
        "onesignal" to listOf("com.onesignal.OneSignal"),
        "tealium" to listOf("com.tealium.core.Tealium", "com.tealium.library.Tealium"),
        "adobe" to listOf("com.adobe.marketing.mobile.MobileCore"),
        "kochava" to listOf("com.kochava.tracker.Tracker"),
        "singular" to listOf("com.singular.sdk.Singular"),
    )

    private val cmpSdkClasses = linkedMapOf(
        "onetrust" to listOf("com.onetrust.otpublishers.headless.Public.OTPublishersHeadlessSDK"),
        "didomi" to listOf("io.didomi.sdk.Didomi"),
        "usercentrics" to listOf("com.usercentrics.sdk.Usercentrics"),
        "sourcepoint" to listOf("com.sourcepoint.cmplibrary.SpConsentLib"),
        "trustarc" to listOf("com.trustarc.trustarcmobile.TrustArc"),
        "cookiebot" to listOf("com.cookiebot.cmp.CookiebotCMP"),
    )

    /**
     * Version probes: static string constants that well known SDKs expose,
     * read via reflection. Only trivially cheap constants are probed; every
     * other version stays null.
     */
    private val versionProbes = mapOf(
        "facebook" to listOf("com.facebook.FacebookSdkVersion" to "BUILD"),
        "adjust" to listOf("com.adjust.sdk.BuildConfig" to "VERSION_NAME"),
        "braze" to listOf("com.braze.Constants" to "BRAZE_SDK_VERSION"),
        "mixpanel" to listOf("com.mixpanel.android.mpmetrics.MPConfig" to "VERSION"),
        "amplitude" to listOf("com.amplitude.api.Constants" to "VERSION"),
        "branch" to listOf("io.branch.referral.BuildConfig" to "VERSION_NAME"),
        "firebase_analytics" to listOf("com.google.firebase.analytics.BuildConfig" to "VERSION_NAME"),
    )

    /** Builds the environment snapshot map that rides in the monita_env dt payload. */
    fun detect(): MutableMap<String, Any?> {
        val env = LinkedHashMap<String, Any?>()
        env["sdks"] = vendorSdkClasses.mapNotNull { (name, classNames) ->
            if (anyClassPresent(classNames)) {
                linkedMapOf<String, Any?>("name" to name, "version" to probeVersion(name))
            } else {
                null
            }
        }
        env["cmp"] = cmpSdkClasses.mapNotNull { (name, classNames) ->
            if (anyClassPresent(classNames)) {
                linkedMapOf<String, Any?>("name" to name, "version" to null)
            } else {
                null
            }
        }
        env["tcf"] = try {
            hasTcfString()
        } catch (t: Throwable) {
            false
        }
        env["host"] = context.packageName
        val (versionName, versionCode) = appVersion()
        env["app_version"] = versionName
        env["app_build"] = versionCode
        env["os"] = "android ${Build.VERSION.RELEASE ?: Build.VERSION.SDK_INT.toString()}"
        env["model"] = Build.MODEL ?: "unknown"
        env["sdk_version"] = sdkVersion
        return env
    }

    private fun appVersion(): Pair<String?, String?> = try {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        @Suppress("DEPRECATION")
        val code = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode.toString() else info.versionCode.toString()
        info.versionName to code
    } catch (t: Throwable) {
        null to null
    }

    private fun probeVersion(sdkName: String): String? =
        versionProbes[sdkName]?.firstNotNullOfOrNull { (className, fieldName) ->
            try {
                val cls = Class.forName(className, false, context.classLoader)
                val field = cls.getDeclaredField(fieldName)
                field.isAccessible = true
                field.get(null) as? String
            } catch (t: Throwable) {
                null
            }
        }

    private fun anyClassPresent(classNames: List<String>): Boolean = classNames.any { name ->
        try {
            Class.forName(name, false, context.classLoader)
            true
        } catch (t: Throwable) {
            false
        }
    }
}
