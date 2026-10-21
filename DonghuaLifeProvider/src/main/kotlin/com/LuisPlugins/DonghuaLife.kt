package com.LuisPlugins

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.utils.*
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
                    "donghuas?page=" to "Donghuas",
                    "finalizado?page=" to "Finalizados",
            )
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        // 1. Siempre construimos la URL con el parámetro ?page=
        // Asumimos que el primer parámetro es page-1, así page=1 se convierte en ?page=0, page=2 en
        // ?page=1, etc.
        val document = app.get("$mainUrl/${request.data}$page").document
        // val url = request.data + {page}
        // val url = "$mainUrl${page - 1}"
        //  "$mainUrl/donghuas?page=${page - 1}"

        // Para depuración, imprime la URL que se está usando
        println("Intentando cargar: $mainUrl/${request.data}${page - 1}")

        // 2. Hacemos la petición (igual que en tu buscador)
        // val document = app.get(url).document

        // 3. Seleccionamos las series
        // El selector .view-donghuas .serie es específico para esta vista y debería funcionar.
        val home =
                document.select("#block-dlife-content .serie").mapNotNull { it.animeFromElement() }

        // 4. Lógica para saber si hay más páginas
        // Comprobamos si el enlace "Siguiente" existe y si no tiene la clase 'disabled'
        val nextPageLink = document.select(".pager__item--next a")
        val hasNext = nextPageLink.any() && !nextPageLink.attr("href").isNullOrEmpty()

        return newHomePageResponse(
                list = HomePageList(name = request.name, list = home, isHorizontalImages = false),
                hasNext = hasNext
        )
    }

    private fun Element.animeFromElement(): SearchResponse? {
        val title = this.select(".titulo")?.text()?.trim() ?: ""
        val href = "$mainUrl" + this.select("a")?.attr("href") ?: ""
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
        val genreTags = document.select("a[href*='/donghuas/']").map { it.text() } // 👈 renombrada
        val fecha =
                document.selectFirst(".fecha time")
                        ?.attr("datetime")
                        ?.substring(0, 4)
                        ?.toIntOrNull()

        val statusText = document.selectFirst(".estado a")?.text()?.trim()
        val status =
                when {
                    statusText?.contains("En Emisión", ignoreCase = true) == true ->
                            ShowStatus.Ongoing
                    statusText?.contains("Finalizado", ignoreCase = true) == true ->
                            ShowStatus.Completed
                    else -> null
                }

        val seasons = document.select(".temporada .serie")
        val episodes = mutableListOf<Episode>()

        seasons.forEach { seasonItem ->
            val seasonUrl = fixUrl(seasonItem.selectFirst("a")?.attr("href") ?: return@forEach)

            val seasonTitle = seasonItem.selectFirst(".titulo")?.text() ?: ""

            val seasonNumber =
                    Regex("(\\d+)$").find(seasonTitle)?.groupValues?.get(1)?.toIntOrNull() ?: 1

            val seasonDoc = app.get(seasonUrl).document

            seasonDoc.select("tbody tr").forEach { row ->
                val epNumber = row.selectFirst("th")?.text()?.toIntOrNull()

                val link = row.selectFirst("a") ?: return@forEach

                val epUrl = fixUrl(link.attr("href"))

                episodes.add(
                        newEpisode(epUrl) {
                            // name = link.text()
                            episode = epNumber
                            this.season = seasonNumber
                        }
                )
            }
        }
        episodes.reverse()
        return newAnimeLoadResponse(title, url, TvType.Anime) {
            addEpisodes(DubStatus.Subbed, episodes)
            posterUrl = poster
            plot = description
            tags = genreTags
            year = fecha
            showStatus = status
        }
    }
    // ===== EXTRACCIÓN DE VIDEOS =====
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

        // ── 1. Links directos en data-video ────────────────────────────────────────
        document.select("a.toggle-enlace[data-video]").amap { anchor ->
            val videoUrl = anchor.attr("data-video").trim()
            if (videoUrl.startsWith("http")) {
                // Rumble lo maneja RumbleExtractor, el resto va al loadExtractor normal
                if (videoUrl.contains("rumble.com")) {
                    RumbleExtractor().getUrl(videoUrl, datafix)?.forEach(callback)
                } else {
                    loadExtractor(videoUrl, datafix, subtitleCallback, callback)
                }
            }
        }

        // ── 2. Iframe activo como fallback ──────────────────────────────────────────
        document.select("iframe#iframe-episode[src]").amap { iframe ->
            val src = iframe.attr("src").trim()
            if (src.startsWith("http")) {
                if (src.contains("rumble.com")) {
                    RumbleExtractor().getUrl(src, datafix)?.forEach(callback)
                } else {
                    loadExtractor(src, datafix, subtitleCallback, callback)
                }
            }
        }
        document.select("a.toggle-enlace[data-video]").amap { anchor ->
            val videoUrl = anchor.attr("data-video").trim()
            if (videoUrl.startsWith("http")) {
                when {
                    videoUrl.contains("rumble.com") ->
                            RumbleExtractor().getUrl(videoUrl, datafix)?.forEach(callback)
                    videoUrl.contains("odysee.com") ->
                            OdyseeExtractor().getUrl(videoUrl, datafix)?.forEach(callback)
                    else -> loadExtractor(videoUrl, datafix, subtitleCallback, callback)
                }
            }
        }

        document.select("iframe#iframe-episode[src]").amap { iframe ->
            val src = iframe.attr("src").trim()
            if (src.startsWith("http")) {
                when {
                    src.contains("rumble.com") ->
                            RumbleExtractor().getUrl(src, datafix)?.forEach(callback)
                    src.contains("odysee.com") ->
                            OdyseeExtractor().getUrl(src, datafix)?.forEach(callback)
                    else -> loadExtractor(src, datafix, subtitleCallback, callback)
                }
            }
        }
        document.select("a.toggle-enlace[data-video]").amap { anchor ->
            val videoUrl = anchor.attr("data-video").trim()
            val title = anchor.attr("title").trim().lowercase()

            if (videoUrl.startsWith("http")) {
                when {
                    title.contains("rumble") ->
                            RumbleExtractor().getUrl(videoUrl, datafix)?.forEach(callback)
                    title.contains("stremeable") || title.contains("streamable") ->
                            Stremeable().getUrl(videoUrl, datafix)?.forEach(callback)
                    else -> loadExtractor(videoUrl, datafix, subtitleCallback, callback)
                }
            }
        }

        // Fallback iframe
        document.select("iframe#iframe-episode[src]").amap { iframe ->
            val src = iframe.attr("src").trim()
            if (src.startsWith("http")) {
                when {
                    src.contains("rumble.com") ->
                            RumbleExtractor().getUrl(src, datafix)?.forEach(callback)
                    src.contains("streamable.com") ->
                            Stremeable().getUrl(src, datafix)?.forEach(callback)
                    else -> loadExtractor(src, datafix, subtitleCallback, callback)
                }
            }
        }
        return true
    }
}
