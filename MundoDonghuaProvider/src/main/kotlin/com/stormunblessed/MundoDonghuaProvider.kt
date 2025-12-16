package com.stormunblessed

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class MundoDonghuaProvider : MainAPI() {
    override var mainUrl = "https://www.mundodonghua.com"
    override var name = "MundoDonghua"
    override var lang = "es"
    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime)

    override val mainPage = mainPageOf(
        "lista-donghuas" to "Donghuas",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get("$mainUrl/${request.data}?page=$page").documentLarge

        val home = document.select("div.row .col-xs-4").mapNotNull { it.toSearchResult() }

        return newHomePageResponse(
            list = HomePageList(
                name = request.name,
                list = home,
                isHorizontalImages = false
            ),
            hasNext = true
        )
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = this.selectFirst("h5")?.text() ?: return null
        val href = fixUrl(this.selectFirst("a")?.attr("href") ?: return null)
        val poster = fixUrlNull(this.selectFirst(".fit-1 img")?.attr("src"))

        return newAnimeSearchResponse(title, href) {
            this.posterUrl = poster
            val epNum = Regex("((\\d+)$)").find(title)?.value?.toIntOrNull()
            val dubstat = if (title.contains("Latino") || title.contains("Castellano")) DubStatus.Dubbed else DubStatus.Subbed
            addDubStatus(dubstat, epNum)
        }
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val document = app.get("$mainUrl/busquedas/$query?page=$page").documentLarge
        val results = document.select(".col-xs-4").mapNotNull { it.toSearchResult() }
        return results.toNewSearchResponseList()
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).documentLarge
        val title = document.selectFirst(".ls-title-serie")?.text()?.trim() ?: ""
        val poster = document.selectFirst("head meta[property=og:image]")?.attr("content") ?: ""
        val description = document.selectFirst("p.text-justify.fc-dark")?.text()?.trim()
        val genres = document.select("span.label.label-primary.f-bold").map { it.text() }
        val status = when (document.selectFirst("div.col-md-6.col-xs-6.align-center.bg-white.pt-10.pr-15.pb-0.pl-15 p span.badge")?.text()) {
            "En Emisión" -> ShowStatus.Ongoing
            "Finalizada" -> ShowStatus.Completed
            else -> null
        }

        val episodes = document.select("ul.donghua-list a").mapNotNull { ep ->
            val link = ep.attr("href")
            val epNum = Regex("ver\\/.*\\/(\\d+)").find(link)?.groupValues?.get(1)?.toIntOrNull()
            newEpisode(fixUrl(link)) {
                this.episode = epNum
                this.name = "Episodio $epNum"
            }
        }

        return newTvSeriesLoadResponse(title, url, TvType.Anime, episodes.sortedBy { it.episode }) {
            this.posterUrl = poster
            this.plot = description
            this.tags = genres
            this.showStatus = status
        }
    }

      override suspend fun loadLinks(
    data: String,
    isCasting: Boolean,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit
): Boolean {
    val document = app.get(data).documentLarge

    // Buscar iframe intermedio (ej. tamamo_player)
    val iframeUrl = document.selectFirst("#tamamo_player")?.attr("src")
    if (!iframeUrl.isNullOrEmpty()) {
        val iframeDoc = app.get(iframeUrl).documentLarge

        // Caso 1: DM.player (Dailymotion)
        val scriptContent = iframeDoc.select("script").html()
        val regex = Regex("""video:\s*"([^"]+)"""")
        val id = regex.find(scriptContent)?.groupValues?.get(1)

        if (id != null) {
            val dmUrl = "https://www.dailymotion.com/video/$id"
            loadExtractor(dmUrl, referer = data, subtitleCallback, callback)
            return true
        }

        // Caso 2: otros hosts (OK.ru, Streamtape, Filemoon, etc.)
        iframeDoc.select("iframe, a").mapNotNull { it.attr("src").ifEmpty { it.attr("href") } }
            .filter { url ->
                url.contains("ok.ru") ||
                url.contains("streamtape") ||
                url.contains("filemoon") ||
                url.contains("watchsb") ||
                url.contains("dailymotion")
            }
            .forEach { playerUrl ->
                loadExtractor(playerUrl, referer = data, subtitleCallback, callback)
            }

        return true
    }

    return false
}

}
