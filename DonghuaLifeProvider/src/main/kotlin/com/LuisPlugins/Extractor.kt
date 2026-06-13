package com.LuisPlugins

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.extractors.Filesim
import com.lagradost.cloudstream3.extractors.StreamSB
import com.lagradost.cloudstream3.extractors.StreamWishExtractor
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.JsUnpacker
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlin.text.Regex

open class Vtbe : ExtractorApi() {
    override var name = "Vtbe"
    override var mainUrl = "https://vtbe.to"
    override val requiresReferer = true

    override suspend fun getUrl(url: String, referer: String?): List<ExtractorLink>? {
        val response = app.get(url,referer=mainUrl).documentLarge
        val extractedpack =response.selectFirst("script:containsData(function(p,a,c,k,e,d))")?.data().toString()
        JsUnpacker(extractedpack).unpack()?.let { unPacked ->
            Regex("sources:\\[\\{file:\"(.*?)\"").find(unPacked)?.groupValues?.get(1)?.let { link ->
                return listOf(
                    newExtractorLink(
                        this.name,
                        this.name,
                        url = link,
                        ExtractorLinkType.M3U8
                    ) {
                        this.referer = referer ?: ""
                        this.quality = Qualities.Unknown.value
                    }

                )
            }
        }
        return null
    }
}

class wishfast : StreamWishExtractor() {
    override var mainUrl = "https://wishfast.top"
    override var name = "StreamWish"
}

class waaw : StreamSB() {
    override var mainUrl = "https://waaw.to"
}

class FileMoonSx : Filesim() {
    override val mainUrl = "https://filemoon.sx"
    override val name = "FileMoonSx"
}

fun Http(url: String): String {
    return if (url.startsWith("//")) {
        "https:$url"
    } else {
        url
    }
}

class RumbleExtractor : ExtractorApi() {
    override var name = "Rumble"
    override var mainUrl = "https://rumble.com"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?
    ): List<ExtractorLink>? {

        val headers = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                    "AppleWebKit/537.36 (KHTML, like Gecko) " +
                    "Chrome/124.0.0.0 Safari/537.36",
            "Referer" to (referer ?: mainUrl)
        )

        val response = app.get(url, headers = headers).text

        val links = mutableListOf<ExtractorLink>()

        // ── 1. Busca el JSON embedsito con las fuentes ──────────────────────────
        // Rumble inyecta algo como: "ua":{"mp4":{"360":{"url":"https://..."}}}
        val videoJsonRegex = Regex(""""ua"\s*:\s*(\{[\s\S]+?"mp4"[\s\S]+?\})\s*,\s*"i""")
        val jsonBlock = videoJsonRegex.find(response)?.groupValues?.get(1)

        if (jsonBlock != null) {
            // Extrae cada URL de mp4 con su etiqueta de calidad
            val mp4Regex = Regex(""""(\d+)"\s*:\s*\{"url"\s*:\s*"([^"]+\.mp4[^"]*)"""")
            mp4Regex.findAll(jsonBlock).forEach { match ->
                val quality = match.groupValues[1].toIntOrNull() ?: Qualities.Unknown.value
                val videoUrl = match.groupValues[2].replace("\\/", "/")
                links.add(
                    newExtractorLink(
                        source = name,
                        name = "$name ${quality}p",
                        url = videoUrl,
                        type = ExtractorLinkType.VIDEO
                    ) {
                        this.referer = url
                        this.quality = quality
                    }
                )
            }
        }

        // ── 2. Fallback: busca un m3u8 directo ─────────────────────────────────
        if (links.isEmpty()) {
            val m3u8Regex = Regex(""""hls"\s*:\s*\{"url"\s*:\s*"([^"]+\.m3u8[^"]*)"""")
            m3u8Regex.find(response)?.groupValues?.get(1)
                ?.replace("\\/", "/")
                ?.let { m3u8Url ->
                    links.add(
                        newExtractorLink(
                            source = name,
                            name = name,
                            url = m3u8Url,
                            type = ExtractorLinkType.M3U8
                        ) {
                            this.referer = url
                            this.quality = Qualities.Unknown.value
                        }
                    )
                }
        }

        return links.ifEmpty { null }
    }
}
class Stremeable : ExtractorApi() {
    override var name = "Stremeable"
    override var mainUrl = "https://stremeable.com"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?
    ): List<ExtractorLink>? {

        val headers = mapOf(
            "User-Agent" to USER_AGENT,
            "Referer" to (referer ?: mainUrl)
        )

        val response = app.get(url, headers = headers).text

        // Stremeable expone el mp4 en una variable JS: file:"https://...mp4"
        val mp4Url = Regex("""file\s*:\s*["']([^"']+\.mp4[^"']*)["']""")
            .find(response)?.groupValues?.get(1)
            ?: Regex("""source\s+src=["']([^"']+\.mp4[^"']*)["']""")
                .find(response)?.groupValues?.get(1)
            ?: Regex("""["'](https?://[^"']+\.mp4[^"']*)["']""")
                .find(response)?.groupValues?.get(1)

        if (!mp4Url.isNullOrEmpty()) {
            return listOf(
                newExtractorLink(
                    source = name,
                    name = name,
                    url = mp4Url,
                    type = ExtractorLinkType.VIDEO
                ) {
                    this.referer = url
                    this.quality = Qualities.Unknown.value
                }
            )
        }

        // Fallback: m3u8
        val m3u8Url = Regex("""file\s*:\s*["']([^"']+\.m3u8[^"']*)["']""")
            .find(response)?.groupValues?.get(1)

        if (!m3u8Url.isNullOrEmpty()) {
            return listOf(
                newExtractorLink(
                    source = name,
                    name = name,
                    url = m3u8Url,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.referer = url
                    this.quality = Qualities.Unknown.value
                }
            )
        }

        return null
    }
}
class OdyseeExtractor : ExtractorApi() {
    override var name = "Odysee"
    override var mainUrl = "https://odysee.com"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?
    ): List<ExtractorLink>? {

        val headers = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                    "AppleWebKit/537.36 (KHTML, like Gecko) " +
                    "Chrome/124.0.0.0 Safari/537.36",
            "Referer" to (referer ?: mainUrl)
        )

        val response = app.get(url, headers = headers).text

        val links = mutableListOf<ExtractorLink>()

        // ── 1. Busca el m3u8 directo en el HTML del embed ──────────────────────
        Regex(""""contentUrl"\s*:\s*"([^"]+\.m3u8[^"]*)"""")
            .find(response)?.groupValues?.get(1)
            ?.replace("\\/", "/")
            ?.let { m3u8Url ->
                links.add(
                    newExtractorLink(
                        source = name,
                        name = name,
                        url = m3u8Url,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.referer = url
                        this.quality = Qualities.Unknown.value
                    }
                )
            }

        // ── 2. Fallback: mp4 directo ───────────────────────────────────────────
        if (links.isEmpty()) {
            Regex(""""contentUrl"\s*:\s*"([^"]+\.mp4[^"]*)"""")
                .find(response)?.groupValues?.get(1)
                ?.replace("\\/", "/")
                ?.let { mp4Url ->
                    links.add(
                        newExtractorLink(
                            source = name,
                            name = name,
                            url = mp4Url,
                            type = ExtractorLinkType.VIDEO
                        ) {
                            this.referer = url
                            this.quality = Qualities.Unknown.value
                        }
                    )
                }
        }

        return links.ifEmpty { null }
    }
}