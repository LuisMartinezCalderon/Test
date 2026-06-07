package com.stormunblessed

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import org.jsoup.nodes.Element

class MundoDonghuaProvider : MainAPI() {

    override var name = "MundoDonghua"
    override var mainUrl = "https://www.mundodonghua.com"
    override var lang = "es"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)

    // ===== PÁGINA PRINCIPAL =====
    override val mainPage = mainPageOf(
        "$mainUrl/lista-donghuas/" to "Populares",
        "$mainUrl/lista-episodios/" to "Últimos Episodios",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = request.data + page
        val document = app.get(url).document
        val items = document.select("a[href*='/donghua/']").mapNotNull { element ->
            animeFromElement(element)
        }
        return newHomePageResponse(request.name, items)
    }

    private fun animeFromElement(element: Element): SearchResponse? {
        val href = element.attr("href").takeIf { it.isNotEmpty() } ?: return null
        val rawText = element.text()
        val title = rawText
            .replace(Regex("(\\d{1,3}([,.]\\d{3})*|\\d+)$"), "")
            .replace(" Donghua", "")
            .replace(" Especial", "")
            .replace(" OVA", "")
            .trim()
            .takeIf { it.isNotEmpty() } ?: return null
        val posterUrl = element.selectFirst("img")?.attr("abs:src")
        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = posterUrl
        }
    }

    // ===== BÚSQUEDA =====
    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/busquedas/$query").document
        return document.select("a[href*='/donghua/']").mapNotNull { animeFromElement(it) }
    }

    // ===== DETALLES =====
    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val title = document.selectFirst("h1")?.text()?.trim() ?: return null

        val posterUrl = document.selectFirst(".md-detail-poster img, img.img-fluid[src*='/thumbs/']")
            ?.attr("abs:src")
            ?: run {
                val style = document.selectFirst("div[style*='background-image']")?.attr("style") ?: ""
                if (style.contains("background-image")) {
                    mainUrl + style.substringAfter("url(").substringBefore(")")
                } else null
            }

        val description = document.selectFirst(".md-detail-synopsis")
            ?.text()?.removeSurrounding("\"")

        val genres = document.select("a[href*='/genero/']").map { it.text() }

        val statusText = document.selectFirst(".md-emision-badge")?.text() ?: ""
        val showStatus = when {
            statusText.contains("En Emisión") -> ShowStatus.Ongoing
            statusText.contains("Finalizada") -> ShowStatus.Completed
            else -> null
        }

        // Episodios — usando newEpisode en lugar del constructor deprecated
        val episodes = document.select("ul li a[href*='/ver/']").mapNotNull { el ->
            val epUrl = el.attr("href").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val epNum = epUrl.split("/").last().toFloatOrNull() ?: 0f
            val nameEpsd = el.selectFirst(".md-episode-details h5")?.text()?.trim()
            val epName = if (!nameEpsd.isNullOrEmpty()) nameEpsd else "Episodio ${epNum.toInt()}"
            newEpisode(fixUrl(epUrl)) {
                name = epName
                episode = epNum.toInt()
            }
        }

        return newAnimeLoadResponse(title, url, TvType.Anime) {
            this.posterUrl = posterUrl
            this.plot = description
            this.tags = genres
            this.showStatus = showStatus
            addEpisodes(DubStatus.Subbed, episodes)
        }
    }

    // ===== EXTRACCIÓN DE VIDEOS =====

    private fun fetchUrls(text: String?): List<String> {
        if (text.isNullOrEmpty()) return emptyList()
        val linkRegex =
            Regex("(http|ftp|https):\\/\\/([\\w_-]+(?:(?:\\.[\\w_-]+)+))([\\w.,@?^=%&:\\/~+#-]*[\\w@?^=%&\\/~+#-])")
        return linkRegex.findAll(text).map { it.value.trim().removeSurrounding("\"") }.toList()
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val document = app.get(data).document

        document.select("script").forEach { script ->
            val scriptData = script.data()
            if (!scriptData.contains("eval(function(p,a,c,k,e")) return@forEach

            val packedRegex = Regex("eval\\(function\\(p,a,c,k,e,.*?\\)\\)", RegexOption.DOT_MATCHES_ALL)
            packedRegex.findAll(scriptData).forEach { match ->
                val packed = match.value

                // --- VOE (amagi_tab) ---
                if (packed.contains("amagi_tab") || packed.contains("amagi")) {
                    fetchUrls(packed).forEach { url ->
                        if (url.contains("voe.sx") || url.contains("voe-unblock")) {
                            runCatching {
                                loadExtractor(url, data, subtitleCallback, callback)
                            }
                        }
                    }
                }

                // --- Filemoon (fmoon_tab) ---
                if (packed.contains("fmoon_tab") || packed.contains("fmoon")) {
                    val fmoonRegex = Regex(
                        """https?://(?:filemoon\.sx|moonembed\.pw|filemoon\.to|fmoonembed\.com|embedwish\.com|vgembed\.com|bysekoze\.com)[^\s"']+"""
                    )
                    val fallbackRegex = Regex("""'(https?://[^']+?)'""")
                    val urls = (
                        fmoonRegex.findAll(packed).map { it.value } +
                        fallbackRegex.findAll(packed).map { it.groupValues[1] }
                    ).distinct().toList()

                    urls.forEach { url ->
                        runCatching {
                            loadExtractor(url, data, subtitleCallback, callback)
                        }
                    }
                }

                // --- Tamamo / Dailymotion (tamamo_tab) ---
                if (packed.contains("tamamo_tab") || packed.contains("tamamo")) {
                    runCatching {
                        val slug = packed.substringAfter("\"slug\":\"").substringBefore("\"")
                        if (slug.isNotEmpty()) {
                            val apiResponse = app.get(
                                "$mainUrl/api_donghua.php?slug=$slug",
                                referer = data,
                            ).text
                            val slugPlayer = apiResponse
                                .substringAfter("\"url\":\"").substringBefore("\"")
                            if (slugPlayer.isNotEmpty()) {
                                val playerPage = app.get(
                                    "https://www.mdplayer.xyz/nemonicplayer/dmplayer.php?key=$slugPlayer",
                                    referer = "$mainUrl/",
                                ).text
                                val videoId = playerPage
                                    .substringAfter("video-id=\"").substringBefore("\"")
                                if (videoId.isNotEmpty()) {
                                    loadExtractor(
                                        "https://www.dailymotion.com/embed/video/$videoId",
                                        data,
                                        subtitleCallback,
                                        callback,
                                    )
                                }
                            }
                        }
                    }
                }

                // --- Asura / HLS directo (asura_tab) ---
                if (packed.contains("asura_tab") || packed.contains("asura")) {
                    fetchUrls(packed).forEach { url ->
                        if (url.contains("redirector") || url.contains("mdnemonicplayer")) {
                            runCatching {
                                // Usando newExtractorLink en lugar del constructor deprecated
                                callback.invoke(
                                    newExtractorLink(
                                        source = "Asura",
                                        name = "Asura",
                                        url = url,
                                        type = if (url.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO,
                                    ) {
                                        this.referer = "$mainUrl/"
                                        this.quality = Qualities.Unknown.value
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }

        return true
    }
}