package com.stormunblessed

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import java.util.*
import kotlin.collections.ArrayList

class MundoDonghuaProvider : MainAPI() {

    override var mainUrl = "https://www.mundodonghua.com"
    override var name = "MundoDonghua"
    override var lang = "es"

    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true

    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie
    )

    /* =========================
       MAIN PAGE
       ========================= */

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {

        val items = ArrayList<HomePageList>()

        val latest = app.get(mainUrl, timeout = 120).document
            .select("div.row .col-xs-4")
            .mapNotNull { el ->
                val titleRaw = el.selectFirst("h5")?.text() ?: return@mapNotNull null
                val title = titleRaw.replace(Regex("Episodio\\s*\\d+"), "").trim()
                val poster = el.selectFirst(".fit-1 img")?.attr("src").orEmpty()
                val href = el.selectFirst("a")?.attr("href") ?: return@mapNotNull null

                val epNum = Regex("(\\d+)$").find(titleRaw)?.value?.toIntOrNull()
                val dub = if (titleRaw.contains("Latino") || titleRaw.contains("Castellano"))
                    DubStatus.Dubbed else DubStatus.Subbed

                newAnimeSearchResponse(
                    name = title,
                    url = fixUrl(href),
                    apiName = name,
                    type = TvType.Anime
                ) {
                    posterUrl = fixUrl(poster)
                    addDubStatus(dub, epNum)
                }
            }

        items.add(HomePageList("Últimos episodios", latest))

        return newHomePageResponse(items, false)
    }

    /* =========================
       SEARCH
       ========================= */

    override suspend fun search(query: String): List<SearchResponse> {
        return app.get("$mainUrl/busquedas/$query", timeout = 120).document
            .select(".col-xs-4")
            .mapNotNull { el ->
                val title = el.selectFirst(".fs-14")?.text() ?: return@mapNotNull null
                val href = el.selectFirst("a")?.attr("href") ?: return@mapNotNull null
                val poster = el.selectFirst(".fit-1 img")?.attr("src").orEmpty()

                newAnimeSearchResponse(
                    name = title,
                    url = fixUrl(href),
                    apiName = name,
                    type = TvType.Anime
                ) {
                    posterUrl = fixUrl(poster)
                    addDubStatus(
                        if (title.contains("Latino") || title.contains("Castellano"))
                            DubStatus.Dubbed else DubStatus.Subbed
                    )
                }
            }
    }

    /* =========================
       LOAD ANIME
       ========================= */

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url, timeout = 120).document

        val title = doc.selectFirst(".ls-title-serie")?.text()
            ?: throw ErrorLoadingException("Sin título")

        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content").orEmpty()
        val description = doc.selectFirst("p.text-justify.fc-dark")?.text().orEmpty()
        val genres = doc.select("span.label.label-primary").map { it.text() }

        val status = when (
            doc.selectFirst("span.badge")?.text()
        ) {
            "En Emisión" -> ShowStatus.Ongoing
            "Finalizada" -> ShowStatus.Completed
            else -> null
        }

        val episodes = doc.select("ul.donghua-list a").mapNotNull { el ->
            val href = el.attr("href")
            val epNum = Regex("(\\d+)$").find(href)?.value?.toIntOrNull()

            newEpisode(fixUrl(href)) {
                episode = epNum
            }
        }

        val typeInfo = doc.select("div.col-md-6.pl-15 p.fc-dark").text()
        val tvType = if (typeInfo.contains("Película", true))
            TvType.AnimeMovie else TvType.Anime

        return newAnimeLoadResponse(title, url, tvType) {
            posterUrl = poster
            plot = description
            tags = genres
            showStatus = status
            addEpisodes(DubStatus.Subbed, episodes.sortedBy { it.episode })
        }
    }

    /* =========================
       API MODELS
       ========================= */

    data class Protea(
        @JsonProperty("source") val source: List<Source>,
        @JsonProperty("poster") val poster: String?
    )

    data class Source(
        @JsonProperty("file") val file: String,
        @JsonProperty("label") val label: String?,
        @JsonProperty("type") val type: String?,
        @JsonProperty("default") val default: String?
    )

    /* =========================
       LOAD LINKS
       ========================= */

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        app.get(data).document.select("script").forEach { script ->
            val content = script.data()

            if (!content.contains("eval(function(p,a,c,k,e")) return@forEach

            val packed = Regex("eval\\(function\\(p,a,c,k,e,.*?\\)\\)")
                .findAll(content)
                .map { getAndUnpack(it.value) }

            packed.forEach { unpack ->

                fetchUrls(unpack).forEach { url ->
                    loadExtractor(url, data, subtitleCallback, callback)
                }

                // ASURA
                if (unpack.contains("asura_player")) {
                    Regex("file\":\"(.*?)\"").find(unpack)?.groupValues?.get(1)
                        ?.let { m3u8 ->
                            if (app.get(m3u8).text.contains("#EXTM3U")) {
                                generateM3u8("Asura", m3u8).forEach(callback)
                            }
                        }
                }

                // PROTEA → DAILYMOTION (reproductor intermedio)
                if (unpack.contains("protea_tab")) {
                    val slug = Regex("slug\":\"(.*?)\"").find(unpack)
                        ?.groupValues?.get(1) ?: return@forEach

                    val json = app.get("$mainUrl/api_donghua.php?slug=$slug").text
                    val key = json.substringAfter("url\":\"").substringBefore("\"")

                    val inter = app.get(
                        "https://www.mdnemonicplayer.xyz/nemonicplayer/dmplayer.php?key=$key",
                        allowRedirects = false
                    ).text

                    Regex("video\":\"(.*?)\"").find(inter)?.groupValues?.get(1)
                        ?.let { id ->
                            loadExtractor(
                                "https://www.dailymotion.com/embed/video/$id",
                                subtitleCallback,
                                callback
                            )
                        }
                }
            }
        }
        return true
    }
}
