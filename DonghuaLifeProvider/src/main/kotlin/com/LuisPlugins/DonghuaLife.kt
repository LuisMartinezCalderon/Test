package com.LuisPlugins

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.M3u8Helper.Companion.generateM3u8
import org.jsoup.nodes.Element

class DonghuaLifeProvider : MainAPI() {

    override var mainUrl = "https://donghualife.com/"
    override var name = "DonghuaLife"
    override val hasMainPage = true
    override var lang = "es-mx"
    override val hasDownloadSupport = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)

    // ===== PÁGINA PRINCIPAL =====
    override val mainPage =
            mainPageOf(
                    "donghuas" to "Donghuas",
                    "finalizado" to "Finalizados",
            )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get("$mainUrl/${request.data}&p=$page").document
        val home = document.select(".view.view-donghuas .serie").mapNotNull { it.animeFromElement() }

        return newHomePageResponse(
                list = HomePageList(
                    name = request.name,
                    list = home,
                    isHorizontalImages = false
                    ),
                hasNext = true
        )
    }

    private fun Element.animeFromElement(): SearchResponse? {
        val title = this.select(".titulo")?.text()?.trim()?: ""
        val href = "$mainUrl"+this.select("a")?.attr("href")?: ""
        val posterUrl = this.select("img")?.attr("src")?.trim()?.let { fixUrlNull(it) }
        val isDub = title.contains("Latino") || title.contains("Castellano")

        return newAnimeSearchResponse(title, fixUrl(href), TvType.Anime) {
            this.posterUrl = posterUrl
            addDubStatus(isDub)
        }
    }

    // ===== BÚSQUEDA =====
    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/search?search_api_fulltext=$query").document
        return document.select(".view-search .serie").mapNotNull { it.animeFromElement() }
    }

    // ===== DETALLES =====
    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        val title = document.selectFirst("h2 span")?.text() ?: "Desconocido"
        val poster =
                document.select(".imagen-node img")?.attr("src")?.trim()?.let { fixUrlNull(it) }
        val description = document.selectFirst(".card-body p")?.text()
        val tags = document.select("a[href*='/donghuas/']").map { it.text() }
        val epsAnchor = document.select("a[href*='/season/']")
        val episodes = mutableListOf<Episode>()

        return if (epsAnchor.size > 1) {
            val episodes: List<Episode>? =
                    epsAnchor.map {
                        val epPoster = it.select("img")?.attr("src")?.trim()?.let { fixUrlNull(it) }
                        val epHref = it.attr("href")

                        newEpisode(epHref) { this.posterUrl = epPoster }
                    }

            newAnimeLoadResponse(title, url, TvType.Anime) {
                addEpisodes(DubStatus.Subbed, episodes)
                this.posterUrl = poster
                this.plot = description
                this.tags = tags
            }
        } else
                newMovieLoadResponse(title, url, TvType.AnimeMovie, epsAnchor.attr("href")) {
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

    // ── Reemplaza el loadLinks original en tu Provider ───────────────────────────

    override suspend fun loadLinks(
            data: String,
            isCasting: Boolean,
            subtitleCallback: (SubtitleFile) -> Unit,
            callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val datafix = data.replace("ñ", "%C3%B1")
        val refererHeaders =
                mapOf(
                        "User-Agent" to USER_AGENT,
                        "Accept" to "*/*",
                        "Accept-Language" to "en-US,en;q=0.5",
                        "X-Requested-With" to "XMLHttpRequest",
                        "Referer" to datafix,
                        "DNT" to "1",
                        "Connection" to "keep-alive",
                )

        val document = app.get(datafix, headers = refererHeaders).document

        // Recoge todos los scripts inline de la página
        document.select("script").amap { scriptEl ->
            val scriptText = scriptEl.data()
            if (!scriptText.contains("eval(function(p,a,c,k,e,")) return@amap

            extractPackedBlocks(scriptText).amap { block ->
                val unpacked = unpackPACKED(block).replace("diasfem", "embedsito")
                if (unpacked.isEmpty()) return@amap

                // ── 1. Iframe embed: VidHide / StreamWish / VoeSX / FileMoon ────
                // El unpacked contiene:  src=\'https://dominio.com/e/ID\'
                val iframeSrc =
                        Regex("""src=\\'(https?://[^'\\]+)\\'""")
                                .find(unpacked)
                                ?.groupValues
                                ?.get(1)
                                ?: Regex("""src='(https?://[^']+)'""")
                                        .find(unpacked)
                                        ?.groupValues
                                        ?.get(1)
                                        ?: Regex("""src="(https?://[^"]+)"""")
                                        .find(unpacked)
                                        ?.groupValues
                                        ?.get(1)

                if (!iframeSrc.isNullOrEmpty()) {
                    //  val fixed = iframeSrc.replace("https://sbbrisk.com", "https://watchsb.com")
                    loadExtractor(iframeSrc, datafix, subtitleCallback, callback)
                    return@amap
                }

                // ── 2. Tamamo player → api_donghua.php → mdnemonicplayer → DM ──
                if (unpacked.contains("tamamo_player") || unpacked.contains("api_donghua")) {
                    // El slug aparece como:  "slug":"BASE64VALUE"
                    val slug =
                            Regex("""["']slug["']\s*:\s*["']([^"']+)["']""")
                                    .find(unpacked)
                                    ?.groupValues
                                    ?.get(1)
                                    ?: Regex("""slug=["']([^"']+)["']""")
                                            .find(unpacked)
                                            ?.groupValues
                                            ?.get(1)
                                    // Fallback: parámetro base64 largo en el código
                                    ?: Regex("""slug['"\s:]+([A-Za-z0-9+/=]{20,})""")
                                            .find(unpacked)
                                            ?.groupValues
                                            ?.get(1)

                    if (!slug.isNullOrEmpty() && !slug.contains("slug")) {
                        val apiResp =
                                app.get(
                                                "$mainUrl/api_donghua.php?slug=$slug",
                                                headers = refererHeaders
                                        )
                                        .text

                        // La API devuelve: [{"url":"KEY_BASE64"}]
                        // Intenta parsear el JSON primero, luego fallback a string
                        val key =
                                try {
                                    // Formato array: [{"url":"..."}]
                                    apiResp.substringAfter("\"url\":\"")
                                            .substringBefore("\"")
                                            .takeIf { it.isNotEmpty() && it.length > 10 }
                                } catch (_: Exception) {
                                    null
                                }

                        if (!key.isNullOrEmpty()) {
                            val playerUrl =
                                    "https://www.mdnemonicplayer.xyz/nemonicplayer/dmplayer.php?key=$key"
                            val playerResp =
                                    app.get(
                                                    playerUrl,
                                                    headers = refererHeaders,
                                                    allowRedirects = false
                                            )
                                            .text
                            val vidID =
                                    Regex("""video\W.*?["']([a-zA-Z0-9_\-]{4,}?)["']""")
                                            .find(playerResp)
                                            ?.groupValues
                                            ?.get(1)
                            if (!vidID.isNullOrEmpty()) {
                                loadExtractor(
                                        "https://www.dailymotion.com/embed/video/$vidID",
                                        subtitleCallback,
                                        callback
                                )
                            }
                        }
                    }
                    return@amap
                }

                // ── 3. Asura player → redirector.php (M3U8) ─────────────────────
                if (unpacked.contains("asura_player")) {
                    // Puede tener la URL del redirector directamente en el unpacked
                    val redirectorUrl =
                            Regex("""https?://[^\s"'<>)\\]*redirector\.php[^\s"'<>)\\]*""")
                                    .find(unpacked)
                                    ?.value
                                    ?: Regex("""file\s*:\s*["']([^"']+\.m3u8[^"']*)["']""")
                                            .find(unpacked)
                                            ?.groupValues
                                            ?.get(1)

                    if (!redirectorUrl.isNullOrEmpty()) {
                        // El redirector devuelve el M3U8 real
                        val m3u8 = app.get(redirectorUrl, headers = refererHeaders).text
                        if (m3u8.contains("#EXTM3U")) {
                            generateM3u8("Asura", redirectorUrl, datafix).forEach(callback)
                        } else {
                            // Si no es M3U8 directo, es un redirect: usar como extractor
                            loadExtractor(redirectorUrl, datafix, subtitleCallback, callback)
                        }
                    } else {
                        // Fallback: cualquier URL http en el unpacked
                        fetchUrls(unpacked).amap { url ->
                            val fixed = url.replace("https://sbbrisk.com", "https://watchsb.com")
                            loadExtractor(fixed, datafix, subtitleCallback, callback)
                        }
                    }
                    return@amap
                }

                // ── 4. Fallback: cualquier URL http en el unpacked ───────────────
                fetchUrls(unpacked).amap { url ->
                    // val fixed = url.replace("https://sbbrisk.com", "https://watchsb.com")
                    loadExtractor(url, datafix, subtitleCallback, callback)
                }
            }
        }

        // ── 5. Iframes directos en el HTML (sin eval) ────────────────────────────
        document.select("iframe[src], iframe[data-src]").amap { iframe ->
            val src =
                    (iframe.attr("src").takeIf { it.isNotEmpty() } ?: iframe.attr("data-src"))
                            .trim()
            if (src.startsWith("http")) {
                val fixed = src.replace("https://sbbrisk.com", "https://watchsb.com")
                loadExtractor(fixed, datafix, subtitleCallback, callback)
            }
        }

        return true
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Funciones auxiliares — añade estas fuera del loadLinks (dentro del Provider)
    // ─────────────────────────────────────────────────────────────────────────────

    /**
     * Extrae todos los bloques eval(function(p,a,c,k,e,...){...}(...)) de un string, usando balance
     * de paréntesis para no cortar en medio.
     */
    private fun extractPackedBlocks(text: String): List<String> {
        val results = mutableListOf<String>()
        val marker = "eval(function(p,a,c,k,e,"
        var i = 0
        while (i < text.length) {
            val start = text.indexOf(marker, i)
            if (start == -1) break
            var depth = 0
            var j = start + 4 // justo después de "eval"
            while (j < text.length) {
                when (text[j]) {
                    '(' -> depth++
                    ')' -> {
                        depth--
                        if (depth == 0) {
                            j++
                            break
                        }
                    }
                }
                j++
            }
            results.add(text.substring(start, j))
            i = j
        }
        return results
    }

    /**
     * Desempaqueta el formato P,A,C,K,E,D. Soporta base ≤36 (toString36) y base 62 (alfabeto
     * extendido).
     *
     * Equivalente al unpackPACKED de Node.js validado contra mundodonghua.
     */
    private fun unpackPACKED(packed: String): String {
        // Extrae los argumentos finales: }('CODIGO', BASE, COUNT, 'k0|k1|...'.split('|'), 0, {})
        val m =
                Regex(
                                """\}\s*\(\s*'([\s\S]+?)'\s*,\s*(\d+)\s*,\s*(\d+)\s*,\s*'([\s\S]+?)'\.split\s*\(\s*'\|'\s*\)""",
                                RegexOption.DOT_MATCHES_ALL
                        )
                        .find(packed)
                        ?: return ""

        var p = m.groupValues[1]
        val a = m.groupValues[2].toIntOrNull() ?: return ""
        val keys = m.groupValues[4].split("|")

        // Convierte un token en base `a` a índice entero
        fun toIndex(token: String): Int {
            if (a <= 36) return token.toIntOrNull(a) ?: -1
            // Base 62 / 95
            val alpha = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ_$"
            var n = 0
            for (ch in token) {
                val pos = alpha.indexOf(ch)
                if (pos == -1) return -1
                n = n * a + pos
            }
            return n
        }

        // Reemplaza cada token \bWORD\b con keys[index] o lo deja igual
        p =
                Regex("""\b([0-9a-zA-Z_$]+)\b""").replace(p) { mr ->
                    val token = mr.groupValues[1]
                    val idx = toIndex(token)
                    if (idx >= 0 && idx < keys.size && keys[idx].isNotEmpty()) keys[idx] else token
                }

        return p
    }
    private fun Element.getImageAttr(): String? {
        val candidates = listOf("data-src", "src", "data-original", "data-lazy", "data-image")

        return candidates.map { this.attr(it) }.firstOrNull { it.isNotBlank() }
    }
    fun fixUrlNull(url: String?): String? {
        if (url.isNullOrBlank()) return null
        return if (url.startsWith("/")) {
            "$mainUrl$url"
        } else url
    }
}
