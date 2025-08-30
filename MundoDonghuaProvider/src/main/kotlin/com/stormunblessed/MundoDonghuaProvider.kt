package com.stormunblessed

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper.Companion.generateM3u8
import com.lagradost.cloudstream3.utils.getAndUnpack
import com.lagradost.cloudstream3.utils.loadExtractor
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
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val urls = listOf(
            Pair("$mainUrl/lista-donghuas", "Donghuas"),
        )

        val items = ArrayList<HomePageList>()
        items.add(
            HomePageList(
                "Últimos episodios",
                app.get(mainUrl, timeout = 120).document.select("div.row .col-xs-4").map {
                    val title = it.selectFirst("h5")?.text() ?: ""
                    val poster = it.selectFirst(".fit-1 img")?.attr("src")
                    val url = it.selectFirst("a")?.attr("href")
                        ?.replace(Regex("(\\/(\\d+)\$|\\/(\\d+).\\/.*)"), "")
                        ?.replace("/ver/", "/donghua/")
                    val epNum = Regex("((\\d+)$)").find(title)?.value?.toIntOrNull()
                    val dubstat = if (title.contains("Latino") || title.contains("Castellano")) DubStatus.Dubbed else DubStatus.Subbed
                    newAnimeSearchResponse(title.replace(Regex("Episodio|(\\d+)"), "").trim(), fixUrl(url ?: "")) {
                        this.posterUrl = fixUrl(poster ?: "")
                        addDubStatus(dubstat, epNum)
                    }
                })
        )

        urls.apmap { (url, name) ->
            val home = app.get(url, timeout = 120).document.select(".col-xs-4").map {
                val title = it.selectFirst(".fs-14")?.text() ?: ""
                val poster = it.selectFirst(".fit-1 img")?.attr("src") ?: ""
                AnimeSearchResponse(
                    title,
                    fixUrl(it.selectFirst("a")?.attr("href") ?: ""),
                    this.name,
                    TvType.Anime,
                    fixUrl(poster),
                    null,
                    if (title.contains("Latino") || title.contains("Castellano")) EnumSet.of(
                        DubStatus.Dubbed
                    ) else EnumSet.of(DubStatus.Subbed),
                )
            }

            items.add(HomePageList(name, home))
        }

        if (items.size <= 0) throw ErrorLoadingException()
        return HomePageResponse(items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return app.get("$mainUrl/busquedas/$query", timeout = 120).document.select(".col-xs-4").map {
            val title = it.selectFirst(".fs-14")?.text() ?: ""
            val href = fixUrl(it.selectFirst("a")?.attr("href") ?: "")
            val image = it.selectFirst(".fit-1 img")?.attr("src")
            AnimeSearchResponse(
                title,
                href,
                this.name,
                TvType.Anime,
                fixUrl(image ?: ""),
                null,
                if (title.contains("Latino") || title.contains("Castellano")) EnumSet.of(
                    DubStatus.Dubbed
                ) else EnumSet.of(DubStatus.Subbed),
            )
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url, timeout = 120).document
        val poster = doc.selectFirst("head meta[property=og:image]")?.attr("content") ?: ""
        val title = doc.selectFirst(".ls-title-serie")?.text() ?: ""
        val description = doc.selectFirst("p.text-justify.fc-dark")?.text() ?: ""
        val genres = doc.select("span.label.label-primary.f-bold").map { it.text() }
        val status = when (doc.selectFirst("div.col-md-6.col-xs-6.align-center.bg-white.pt-10.pr-15.pb-0.pl-15 p span.badge")?.text()) {
            "En Emisión" -> ShowStatus.Ongoing
            "Finalizada" -> ShowStatus.Completed
            else -> null
        }
        val epNumRegex = Regex("ver\\/.*\\/(\\d+)")
        val newEpisodes = ArrayList<Episode>()
        doc.select("ul.donghua-list a").map {
            val link = it.attr("href")
            val epnum = epNumRegex.find(link)?.destructured?.component1()
            newEpisodes.add(
                Episode(
                    fixUrl(link),
                    episode = epnum.toString().toIntOrNull()
                )
            )
        }

        val typeinfo = doc.select("div.row div.col-md-6.pl-15 p.fc-dark").text()
        val tvType = if (typeinfo.contains(Regex("Tipo.*Pel.cula"))) TvType.AnimeMovie else TvType.Anime
        return newAnimeLoadResponse(title, url, tvType) {
            posterUrl = poster
            addEpisodes(DubStatus.Subbed, newEpisodes.sortedBy { it.episode })
            showStatus = status
            plot = description
            tags = genres
        }
    }

    // 👇 Aquí el loadLinks modificado
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val datafix = data.replace("ñ", "%C3%B1")
        val reqHEAD = mapOf(
            "User-Agent" to USER_AGENT,
            "Accept" to "*/*",
            "X-Requested-With" to "XMLHttpRequest",
            "Referer" to datafix
        )

        // 1️⃣ Intentar con la API (evita Cloudflare)
        val slug = datafix.substringAfterLast("/").substringBefore("?")
        try {
            val apiUrl = "$mainUrl/api_donghua.php?slug=$slug"
            val apiRes = app.get(apiUrl, headers = reqHEAD).text
            if (apiRes.contains("url")) {
                val secondK = apiRes.substringAfter("url\":\"").substringBefore("\"}")
                val se = "https://www.mdnemonicplayer.xyz/nemonicplayer/dmplayer.php?key=$secondK"
                val aa3 = app.get(se, headers = reqHEAD, allowRedirects = false).text
                val idReg = Regex("video.*\\\"(.*?)\\\"")
                val vidID = idReg.find(aa3)?.destructured?.component1()
                if (!vidID.isNullOrEmpty()) {
                    val newLink = "https://www.dailymotion.com/embed/video/$vidID"
                    loadExtractor(newLink, datafix, subtitleCallback, callback)
                    return true
                }
            }
        } catch (_: Exception) { }

        // 2️⃣ Si falla, parsea el HTML buscando iframes
        try {
            val doc = app.get(datafix, headers = reqHEAD).document
            doc.select("iframe").forEach { iframe ->
                val embedUrl = iframe.attr("src")
                if (!embedUrl.isNullOrEmpty()) {
                    loadExtractor(embedUrl, datafix, subtitleCallback, callback)
                }
            }
        } catch (_: Exception) { }

        return true
    }
}
