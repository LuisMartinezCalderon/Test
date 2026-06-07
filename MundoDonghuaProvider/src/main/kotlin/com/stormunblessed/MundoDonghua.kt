package com.stormunblessed

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class MundoDonghuaProvider : MainAPI() {

    override var mainUrl = "https://www.mundodonghua.com"
    override var name = "MundoDonghua"
    override var lang = "es-mx"
    override val hasMainPage = true
    override val hasDownloadSupport   = true
    override val hasQuickSearch       = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)

    // ===== PÁGINA PRINCIPAL =====
    override val mainPage =
            mainPageOf(
                    "$mainUrl/lista-donghuas/" to "Populares",
                    "$mainUrl/lista-episodios/" to "Últimos Episodios",
            )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = request.data + page
        val document = app.get(url).document
        val home = document.select("a[href*='/donghua/']").mapNotNull { it.animeFromElement() }

        return newHomePageResponse(
                list = HomePageList(name = request.name, list = home, isHorizontalImages = false),
                hasNext = true
        )
    }

    private fun Element.animeFromElement(): SearchResponse {
        val href = this.attr("href")
        val title = this.select("h1").text()
        val posterUrl = fixUrlNull(this.selectFirst("img")?.getImageAttr())
        val isDub     = title.contains("Latino") || title.contains("Castellano")
        return newAnimeSearchResponse(title, href, TvType.Anime) {
             this.posterUrl = posterUrl
            addDubStatus(isDub)
        }
    }

    // ===== BÚSQUEDA =====
    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/busquedas/$query").document
        return document.select("a[href*='/donghua/']").mapNotNull { it.animeFromElement() }
    }

    // ===== DETALLES =====
    override suspend fun load(url: String): LoadResponse {
        val document    = app.get(url).document
        val title       = document.selectFirst("h1")?.text() ?: "Desconocido"
        val poster      = document.select(".md-detail-banner-bg img")?.attr("src")?.trim()
                               ?.let { fixUrlNull(it) }
        val description = document.selectFirst(".md-detail-synopsis")?.text()
        val tags        = document.select("a[href*='/genero/']").map { it.text() }
        val epsAnchor   = document.select("ul li a[href*='/ver/']")

        return if (epsAnchor.size > 1) {
            val episodes: List<Episode>? = epsAnchor.map {
                val epPoster = it.select("img")?.attr("src")?.trim()
                               ?.let { fixUrlNull(it) }
                val epHref   = it.attr("href")

                newEpisode(epHref) {
                    this.posterUrl = epPoster
                }
            }

            newAnimeLoadResponse(title, url, TvType.Anime) {
                addEpisodes(DubStatus.Subbed, episodes)
                this.posterUrl = poster
                this.plot = description
                this.tags = tags

            }
        } else newMovieLoadResponse(title, url, TvType.AnimeMovie, epsAnchor.attr("href")) {
            this.posterUrl = poster
            this.plot = description
            this.tags = tags
        }
    }
    // ===== EXTRACCIÓN DE VIDEOS =====

    private fun fetchUrls(text: String?): List<String> {
        if (text.isNullOrEmpty()) return emptyList()
        val linkRegex =
                Regex(
                        "(http|ftp|https):\\/\\/([\\w_-]+(?:(?:\\.[\\w_-]+)+))([\\w.,@?^=%&:\\/~+#-]*[\\w@?^=%&\\/~+#-])"
                )
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

            val packedRegex =
                    Regex("eval\\(function\\(p,a,c,k,e,.*?\\)\\)", RegexOption.DOT_MATCHES_ALL)
            packedRegex.findAll(scriptData).forEach { match ->
                val packed = match.value

                // --- VOE (amagi_tab) ---
                if (packed.contains("amagi_tab") || packed.contains("amagi")) {
                    fetchUrls(packed).forEach { url ->
                        if (url.contains("voe.sx") || url.contains("voe-unblock")) {
                            runCatching { loadExtractor(url, data, subtitleCallback, callback) }
                        }
                    }
                }

                // --- Filemoon (fmoon_tab) ---
                if (packed.contains("fmoon_tab") || packed.contains("fmoon")) {
                    val fmoonRegex =
                            Regex(
                                    """https?://(?:filemoon\.sx|moonembed\.pw|filemoon\.to|fmoonembed\.com|embedwish\.com|vgembed\.com|bysekoze\.com)[^\s"']+"""
                            )
                    val fallbackRegex = Regex("""'(https?://[^']+?)'""")
                    val urls =
                            (fmoonRegex.findAll(packed).map { it.value } +
                                            fallbackRegex.findAll(packed).map { it.groupValues[1] })
                                    .distinct()
                                    .toList()

                    urls.forEach { url ->
                        runCatching { loadExtractor(url, data, subtitleCallback, callback) }
                    }
                }

                // --- Tamamo / Dailymotion (tamamo_tab) ---
                if (packed.contains("tamamo_tab") || packed.contains("tamamo")) {
                    runCatching {
                        val slug = packed.substringAfter("\"slug\":\"").substringBefore("\"")
                        if (slug.isNotEmpty()) {
                            val apiResponse =
                                    app.get(
                                                    "$mainUrl/api_donghua.php?slug=$slug",
                                                    referer = data,
                                            )
                                            .text
                            val slugPlayer =
                                    apiResponse.substringAfter("\"url\":\"").substringBefore("\"")
                            if (slugPlayer.isNotEmpty()) {
                                val playerPage =
                                        app.get(
                                                        "https://www.mdplayer.xyz/nemonicplayer/dmplayer.php?key=$slugPlayer",
                                                        referer = "$mainUrl/",
                                                )
                                                .text
                                val videoId =
                                        playerPage
                                                .substringAfter("video-id=\"")
                                                .substringBefore("\"")
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
                                                type =
                                                        if (url.contains(".m3u8"))
                                                                ExtractorLinkType.M3U8
                                                        else ExtractorLinkType.VIDEO,
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
    private fun Element.getImageAttr(): String? {
        val candidates = listOf("data-src", "src", "data-original", "data-lazy", "data-image")

        return candidates.map { this.attr(it) }.firstOrNull { it.isNotBlank() }
    }
    fun fixUrlNull(url: String?): String? {
    if (url.isNullOrBlank()) return null
    return if (url.startsWith("/")) {
        "$mainUrl:$url"   // aquí pones el dominio real
    } else url
}
}
