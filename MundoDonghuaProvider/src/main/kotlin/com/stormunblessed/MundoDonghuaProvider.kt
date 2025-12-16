package com.stormunblessed

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper.Companion.generateM3u8
import com.lagradost.cloudstream3.utils.getAndUnpack
import com.lagradost.cloudstream3.utils.loadExtractor
import java.util.EnumSet

class MundoDonghuaProvider : MainAPI() {

    override var mainUrl = "https://www.mundodonghua.com"
    override var name = "MundoDonghua"
    override var lang = "es"

    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true

    override val supportedTypes = setOf(TvType.Anime)

    // ===================== MAIN PAGE =====================

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {

        val items = mutableListOf<HomePageList>()

        val latest = app.get(mainUrl).document
            .select("div.row .col-xs-4")
            .map {
                val title = it.selectFirst("h5")?.text() ?: return@map null
                val poster = it.selectFirst(".fit-1 img")?.attr("src")
                val href = it.selectFirst("a")?.attr("href") ?: return@map null

                val cleanUrl = href
                    .replace(Regex("(\\/\\d+$)"), "")
                    .replace("/ver/", "/donghua/")

                val dubStatus =
                    if (title.contains("Latino") || title.contains("Castellano"))
                        DubStatus.Dubbed
                    else
                        DubStatus.Subbed

                newAnimeSearchResponse(
                    title.replace(Regex("Episodio\\s*\\d+"), "").trim(),
                    fixUrl(cleanUrl)
                ) {
                    posterUrl = fixUrl(poster)
                    addDubStatus(dubStatus)
                }
            }
            .filterNotNull()

        items.add(HomePageList("Últimos episodios", latest))

        return HomePageResponse(items)
    }

    // ===================== SEARCH =====================

    override suspend fun search(query: String): List<SearchResponse> {
        return app.get("$mainUrl/busquedas/$query").document
            .select(".col-xs-4")
            .map {
                val title = it.selectFirst(".fs-14")?.text() ?: return@map null
                val href = it.selectFirst("a")?.attr("href") ?: return@map null
                val poster = it.selectFirst(".fit-1 img")?.attr("src")

                AnimeSearchResponse(
                    title,
                    fixUrl(href),
                    name,
                    TvType.Anime,
                    fixUrl(poster),
                    null,
                    if (title.contains("Latino") || title.contains("Castellano"))
                        EnumSet.of(DubStatus.Dubbed)
                    else
                        EnumSet.of(DubStatus.Subbed),
                )
            }
            .filterNotNull()
    }

    // ===================== LOAD =====================

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document

        val title = doc.selectFirst(".ls-title-serie")?.text() ?: ""
        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")
        val description = doc.selectFirst("p.text-justify.fc-dark")?.text()

        val episodes = doc.select("ul.donghua-list a")
            .mapNotNull {
                val href = it.attr("href")
                val ep = Regex("ver/.*/(\\d+)").find(href)?.groupValues?.get(1)
                Episode(fixUrl(href), ep?.toIntOrNull())
            }

        return newAnimeLoadResponse(title, url, TvType.Anime) {
            posterUrl = poster
            plot = description
            addEpisodes(DubStatus.Subbed, episodes.sortedBy { it.episode })
        }
    }

    // ===================== LINKS =====================

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        val headers = mapOf(
            "User-Agent" to USER_AGENT,
            "Referer" to data,
            "X-Requested-With" to "XMLHttpRequest"
        )

        app.get(data).document.select("script").forEach { script ->

            if (!script.data().contains("eval(function(p,a,c,k,e")) return@forEach

            val packed = Regex("""eval\(function\(p,a,c,k,e,.*?\)\)""")
                .findAll(script.data())
                .map { it.value }

            packed.forEach { js ->

                val unpacked = getAndUnpack(js)

                // -------- FILEMOON / VOE / ASURA --------
                fetchUrls(unpacked).forEach { url ->
                    loadExtractor(url, data, subtitleCallback, callback)
                }

                // -------- DAILYMOTION (INTERMEDIO) --------
                if (unpacked.contains("protea_tab")) {

                    val slug = Regex("""protea_tab.*?slug.*?\"(.*?)\"""")
                        .find(unpacked)
                        ?.groupValues
                        ?.get(1)
                        ?: return@forEach

                    val api = app.get(
                        "$mainUrl/api_donghua.php?slug=$slug",
                        headers = headers
                    ).text

                    val key = api
                        .substringAfter("\"url\":\"")
                        .substringBefore("\"")

                    if (key.isEmpty()) return@forEach

                    val playerUrl =
                        "https://www.mdnemonicplayer.xyz/nemonicplayer/dmplayer.php?key=$key"

                    val playerHtml = app.get(
                        playerUrl,
                        headers = mapOf("Referer" to mainUrl),
                        allowRedirects = false
                    ).text

                    val videoId = Regex("""video.*?\"(.*?)\"""")
                        .find(playerHtml)
                        ?.groupValues
                        ?.get(1)
                        ?: return@forEach

                    val embed =
                        "https://www.dailymotion.com/embed/video/$videoId"

                    loadExtractor(
                        embed,
                        mainUrl,
                        subtitleCallback,
                        callback
                    )
                }

                // -------- ASURA DIRECT HLS --------
                if (unpacked.contains("asura_player")) {
                    val hls = Regex("""file.*?\"(https.*?\.m3u8)\"""")
                        .find(unpacked)
                        ?.groupValues
                        ?.get(1)

                    if (!hls.isNullOrEmpty()) {
                        generateM3u8("Asura", hls, mainUrl)
                            .forEach(callback)
                    }
                }
            }
        }

        return true
    }
}
