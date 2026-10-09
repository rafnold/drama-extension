package com.example

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

/**
 * v2 rebuild (REPLAN.md B1): version 35. The only change vs v34 is the [ExtLog2] boot lines —
 * providers stay registered exactly as before, so either the app starts working AND we get a
 * log of why it worked, or it keeps failing AND this time the failure stage lands in
 * `/storage/emulated/0/drama_dbg/dbg.log` (public dir: v1's log lived inside the app sandbox
 * where shell cannot read it — that dead channel is what cost three sessions).
 */
@CloudstreamPlugin
class DramaExtensionPlugin : Plugin() {

    override fun load(context: Context) {
        val sdk = android.os.Build.VERSION.SDK_INT
        val version = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }
            .getOrDefault("?")
        ExtLog2.log(
            "boot",
            "v35 loaded pkg=${context.packageName} appVersion=$version sdk=$sdk" +
                " providers=6 (DramaNice,KDramaIn,KissAsian,Dramahood,KissKH,Primeshows)"
        )
        ExtLog2.log("load", "Plugin.load entered — dex class load OK")

        registerMainAPI(DramaNice())
        registerMainAPI(KDramaIn())
        registerMainAPI(KissAsian())
        registerMainAPI(Dramahood())
        registerMainAPI(KissKH())
        registerMainAPI(Primeshows())
        registerMainAPI(Goojara())
        registerMainAPI(YesMovies())
        ExtLog2.log("load", "all 8 providers registered")
    }
}
