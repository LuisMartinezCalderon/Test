package com.stormunblessed

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class MundoDonghuaProvider : MainAPI() {

    override var name = "MundoDonghua"
    override var mainUrl = "https://www.mundodonghua.com"
    override var lang = "es"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)

    // ===== PÁGINA PRINCIPAL =====
    override val mainPage = mainPageOf(
        "$mainUrl/lista-donghuas" to "Todas las Series",
        "$mainUrl/lista-donghuas-emision" to "En Emisión",
        "$mainUrl/lista-donghuas-finalizados" to "Finalizadas",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        // La paginación es /lista-donghuas/2, /lista-donghuas/3, etc.
        // Página 1 no lleva número
        val url = if (page == 1) request.data else "${request.data}/$page"
        val document = app.get(url).document
        val items = document.select("a[href*='/donghua/']").mapNotNull { animeFromElement(it) }
        return newHomePageResponse(request.name, items)
    }

    // La lista NO tiene imágenes en el HTML — se construyen desde el slug de la URL
    // Estructura real del poster: /thumbs/INICIAL/Nombre_Serie/imagen.jpg
    // Lo más fiable es obtener la imagen desde el og:image de la página de detalle,
    // pero para la lista usamos una imagen placeholder y la real se carga en load()
    private fun animeFromElement(element: Element): SearchResponse? {
        val href = element.attr("href").takeIf { it.isNotEmpty() } ?: return null
        if (!href.contains("/donghua/")) return null

        // El texto tiene formato "Titulo Donghua Titulo 123,456"
        // Limpiamos el número de vistas y el badge de tipo
        val fullText = element.text()
        val title = fullText
            .replace(Regex("\\s+(Donghua|Especial|OVA|Película)\\s+"), " ")
            .replace(Regex("\\s+\\d[\\d,.]*$"), "")
            .trim()
            .takeIf { it.isNotEmpty() } ?: return null

        // Imagen: la lista no tiene img tags, usamos og:image del detalle
        // Para mostrar algo en la lista usamos null — Cloudstream lo maneja con placeholder
        return newAnimeSearchResponse(title, fixUrl(href), TvType.Anime) {
            this.posterUrl = null
        }
    }

    // ===== BÚSQUEDA =====
    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/busquedas/$query").document
        return document.select("a[href*='/donghua/']").mapNotNull { animeFromElement(it) }
    }

    // ===== DETALLES =====
    // Estructura real del HTML:
    // <img src="/thumbs/A/Against_the_Gods/bg_xxx.jpg">          ← background, sin alt
    // <img src="/thumbs/A/Against_the_Gods/xxx.jpg" alt="Titulo"> ← POSTER ✓
    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val title = document.selectFirst("h1")?.text()?.trim() ?: return null

        // El poster es el img con alt igual al título (segundo img de la página)
        // El background es el primer img sin atributo alt
        val posterUrl = document.select("img[alt]")
            .firstOrNull { it.attr("alt").isNotEmpty() && !it.attr("src").contains("logo") }
            ?.attr("abs:src")
            // Fallback: og:image del meta
            ?: document.selectFirst("meta[property=og:image]")?.attr("content")

        val description = document.selectFirst("p, .sinopsis, section")
            ?.text()
            ?.takeIf { it.length > 30 }

        val genres = document.select("a[href*='/genero/']").map { it.text() }

        val statusText = document.select("p, span, div").map { it.text() }
            .firstOrNull { it.contains("Finalizada") || it.contains("En Emisión") } ?: ""
        val showStatus = when {
            statusText.contains("En Emisión") -> ShowStatus.Ongoing
            statusText.contains("Finalizada") -> ShowStatus.Completed
            else -> null
        }

        // Episodios — estructura: <li><a href="/ver/slug/numero">Titulo - numero</a></li>
        val episodes = document.select("li a[href*='/ver/']").mapNotNull { el ->
            val epUrl = el.attr("href").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val epNum = epUrl.split("/").last().toIntOrNull() ?: return@mapNotNull null
            val epName = el.text().trim().takeIf { it.isNotEmpty() } ?: "Episodio $epNum"
            newEpisode(fixUrl(epUrl)) {
                name = epName
                episode = epNum
            }
        }.reversed() // El sitio lista del más nuevo al más viejo — invertimos

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
        val linkRegex = Regex(
            "(https?|ftp):\\/\\/([\\w_-]+(?:\\.[\\w_-]+)+)([\\w.,@?^=%&:\\/~+#-]*[\\w@?^=%&\\/~+#-])"
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

            val packedRegex = Regex(
                "eval\\(function\\(p,a,c,k,e,.*?\\)\\)",
                RegexOption.DOT_MATCHES_ALL
            )
            packedRegex.findAll(scriptData).forEach { match ->
                val packed = match.value

                // --- VOE ---
                if (packed.contains("amagi_tab") || packed.contains("amagi")) {
                    fetchUrls(packed).forEach { url ->
                        if (url.contains("voe.sx") || url.contains("voe-unblock")) {
                            runCatching { loadExtractor(url, data, subtitleCallback, callback) }
                        }
                    }
                }

                // --- Filemoon ---
                if (packed.contains("fmoon_tab") || packed.contains("fmoon")) {
                    val fmoonRegex = Regex(
                        """https?://(?:filemoon\.sx|moonembed\.pw|filemoon\.to|fmoonembed\.com|embedwish\.com|vgembed\.com|bysekoze\.com)[^\s"']+"""
                    )
                    val fallbackRegex = Regex("""'(https?://[^']+?)'""")
                    (fmoonRegex.findAll(packed).map { it.value } +
                     fallbackRegex.findAll(packed).map { it.groupValues[1] })
                        .distinct()
                        .forEach { url ->
                            runCatching { loadExtractor(url, data, subtitleCallback, callback) }
                        }
                }

                // --- Dailymotion (tamamo_tab) ---
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
                                        data, subtitleCallback, callback,
                                    )
                                }
                            }
                        }
                    }
                }

                // --- Asura / HLS directo ---
                if (packed.contains("asura_tab") || packed.contains("asura")) {
                    fetchUrls(packed).forEach { url ->
                        if (url.contains("redirector") || url.contains("mdnemonicplayer")) {
                            runCatching {
                                callback.invoke(
                                    newExtractorLink(
                                        source = "Asura",
                                        name = "Asura",
                                        url = url,
                                        type = if (url.contains(".m3u8"))
                                            ExtractorLinkType.M3U8
                                        else
                                            ExtractorLinkType.VIDEO,
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