package com.stormunblessed

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.M3u8Helper.Companion.generateM3u8
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
        val title = this.select("h1").text()
        val href = this.attr("href")
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
    val datafix = data.replace("ñ", "%C3%B1")
    val reqHEAD = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept" to "*/*",
        "Accept-Language" to "en-US,en;q=0.5",
        "X-Requested-With" to "XMLHttpRequest",
        "Referer" to datafix,
        "DNT" to "1",
        "Connection" to "keep-alive",
        "Sec-Fetch-Dest" to "empty",
        "Sec-Fetch-Mode" to "cors",
        "Sec-Fetch-Site" to "same-origin",
        "TE" to "trailers"
    )

    val document = app.get(datafix).document

    // ── 1. Scripts estáticos con eval (lógica original) ──────────────────────
    document.select("script").amap { script ->
        if (script.data().contains("eval(function(p,a,c,k,e")) {
            val packedRegex = Regex("eval\\(function\\(p,a,c,k,e,.*\\)\\)")
            packedRegex.findAll(script.data()).map { it.value }.toList().amap { packed ->
                val unpack = getAndUnpack(packed).replace("diasfem", "embedsito")

                fetchUrls(unpack).amap { url ->
                    val newUrl = url.replace("https://sbbrisk.com", "https://watchsb.com")
                    loadExtractor(newUrl, datafix, subtitleCallback, callback)
                }

                if (unpack.contains("protea_tab")) {
                    val protearegex = Regex("protea_tab.*slug.*\\\"(.*)\\\".*,type")
                    val ssee = protearegex.find(unpack)?.destructured?.component1()
                    if (!ssee.isNullOrEmpty()) {
                        val aa = app.get("$mainUrl/api_donghua.php?slug=$ssee", headers = reqHEAD).text
                        val secondK = aa.substringAfter("url\":\"").substringBefore("\"}")
                        val se = "https://www.mdnemonicplayer.xyz/nemonicplayer/dmplayer.php?key=$secondK"
                        val aa3 = app.get(se, headers = reqHEAD, allowRedirects = false).text
                        val idReg = Regex("video.*\\\"(.*?)\\\"")
                        val vidID = idReg.find(aa3)?.destructured?.component1()
                        if (!vidID.isNullOrEmpty()) {
                            loadExtractor(
                                "https://www.dailymotion.com/embed/video/$vidID",
                                subtitleCallback, callback
                            )
                        }
                    }
                }

                if (unpack.contains("asura_player")) {
                    val asuraRegex = Regex("file.*\\\"(.*)\\\".*type")
                    val aass = asuraRegex.find(unpack)?.destructured?.component1()
                    if (!aass.isNullOrEmpty()) {
                        val test = app.get(aass).text
                        if (test.contains(Regex("#EXTM3U"))) {
                            generateM3u8("Asura", aass, "").forEach(callback)
                        }
                    }
                }
            }
        }
    }

    // ── 2. Iframes directos en el HTML (Vh, Sw, Voe, Fm, etc.) ──────────────
    // Algunos episodios NO usan eval y ponen el iframe directo en el HTML
    document.select("iframe[src]").amap { iframe ->
        val src = iframe.attr("src").trim()
        if (src.isNotEmpty() && src.startsWith("http")) {
            val fixed = src.replace("https://sbbrisk.com", "https://watchsb.com")
            loadExtractor(fixed, datafix, subtitleCallback, callback)
        }
    }

    // ── 3. data-src lazy iframes ─────────────────────────────────────────────
    document.select("iframe[data-src]").amap { iframe ->
        val src = iframe.attr("data-src").trim()
        if (src.isNotEmpty() && src.startsWith("http")) {
            val fixed = src.replace("https://sbbrisk.com", "https://watchsb.com")
            loadExtractor(fixed, datafix, subtitleCallback, callback)
        }
    }

    // ── 4. Scripts sin eval que contengan URLs de embeds directamente ────────
    document.select("script").amap { script ->
        val content = script.data()
        // Busca patrones como: src: "https://vidhide.com/..." o file:"https://..."
        if (!content.contains("eval(function(p,a,c,k,e")) {
            fetchUrls(content).amap { url ->
                if (url.contains("vidhide|streamwish|swish|voe|filemoon|dood|streamtape"
                        .toRegex(RegexOption.IGNORE_CASE))
                ) {
                    val fixed = url.replace("https://sbbrisk.com", "https://watchsb.com")
                    loadExtractor(fixed, datafix, subtitleCallback, callback)
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
