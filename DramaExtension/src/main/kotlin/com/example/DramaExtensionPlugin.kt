package com.example

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class DramaExtensionPlugin : Plugin() {
    override fun load(context: Context) {
        // All providers are registered here.
        registerMainAPI(DramaNice())
        registerMainAPI(KDramaIn())
    }
}
