package com.stormunblessed

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.extractors.Dailymotion
import com.lagradost.cloudstream3.extractors.Okrulink
import com.lagradost.cloudstream3.extractors.FileMoonSx

@CloudstreamPlugin
class stormunblessed: BasePlugin() {
    override fun load() {
        registerMainAPI(stormunblessed())
        registerExtractorAPI(FileMoonSx())
        registerExtractorAPI(Dailymotion())
        registerExtractorAPI(Okrulink())
    }
}