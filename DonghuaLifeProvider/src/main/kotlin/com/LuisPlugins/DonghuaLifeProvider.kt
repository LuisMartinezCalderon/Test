package com.LuisPlugins

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.extractors.Dailymotion

//import com.lagradost.cloudstream3.extractors.Okrulink

@CloudstreamPlugin
class DonghuaLifePlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(DonghuaLifeProvider())
        registerExtractorAPI(Vtbe())
        registerExtractorAPI(waaw())
        registerExtractorAPI(wishfast())
        registerExtractorAPI(FileMoonSx())
        registerExtractorAPI(Dailymotion())
        //registerExtractorAPI(Okrulink())
    }
}
