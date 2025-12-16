package com.stormunblessed

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class MundoDonghuaPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(MundoDonghuaProvider())
        // Si quieres registrar extractores adicionales:
        // registerExtractorAPI(FileMoonSx())
        // registerExtractorAPI(Dailymotion())
        // registerExtractorAPI(Okrulink())
    }
}
