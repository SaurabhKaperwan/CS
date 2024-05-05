package com.megix

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.network.CloudflareKiller

open class VegaMoviesProvider : MainAPI() { // all providers must be an instance of MainAPI
    override var mainUrl = "https://vegamovies.ph"
    override var name = "VegaMovies"
    override val hasMainPage = true
    override var lang = "hi"
    override val hasDownloadSupport = true
    private val cfInterceptor = CloudflareKiller()
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries
    )

    override val mainPage = mainPageOf(
        "$mainUrl/page/%d/" to "Home",
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val document = app.get(request.data.format(page), interceptor = cfInterceptor).document
        val home = document.select("article.post-item").mapNotNull {
            it.toSearchResult()
        }
        return newHomePageResponse(request.name, home)
    }

    private suspend fun Element.toSearchResult(): SearchResponse? {
        val title = this.selectFirst("a")?.attr("title") ?: return null
        val href = fixUrl(this.selectFirst("a")?.attr("href").toString())
        val document = app.get(href).document
        val posterUrl = fixUrlNull(document.selectFirst("meta[property=og:image]")?.attr("content"))

        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/?s=$query", interceptor = cfInterceptor).document

        return document.select("article.post-item").mapNotNull {
            it.toSearchResult()
        }
    }

override suspend fun load(url: String): LoadResponse? {
    val document = app.get(url).document
    val title = document.selectFirst("meta[property=og:title]")?.attr("content") ?: return null
    val posterUrl = fixUrlNull(document.selectFirst("meta[property=og:image]")?.attr("content"))
    val regexTV = Regex("""Series-SYNOPSIS\/PLOT""")

    val tvType = if (regexTV.containsMatchIn(document.html())) TvType.TvSeries else TvType.Movie

    if (tvType == TvType.TvSeries) {
        val regex = Regex("""https:\/\/unilinks\.lol\/[a-zA-Z0-9]+\/""")
        val urls = regex.findAll(document.html()).mapNotNull { it.value }.toList()
        var seasonNum = 1
        val tvSeriesEpisodes = mutableListOf<Episode>()

        for (url in urls) {
            val document2 = app.get(url).document
            val vcloudRegex = Regex("""https:\/\/vcloud\.lol\/[^\s"]+""")
            val vcloudLinks = vcloudRegex.findAll(document2.html()).mapNotNull { it.value }.toList()
            val episodes = vcloudLinks.mapNotNull { vcloudlink ->
                Episode(
                    data = vcloudlink,
                    season = seasonNum,
                    episode = vcloudLinks.indexOf(vcloudlink) + 1,
                )
            }
            tvSeriesEpisodes.addAll(episodes)
            seasonNum++
        }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, tvSeriesEpisodes) {
            this.posterUrl = posterUrl
        }
    } else {
        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = posterUrl
        }
    }
}

    override suspend fun loadLinks(
    data: String,
    isCasting: Boolean,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit
): Boolean {
    if (data.contains("vcloud.lol")) {
        loadExtractor(data, subtitleCallback, callback)
        return true
    } else {
        val document1 = app.get(data).document
        val regex = Regex("""<a.*?formsubmit\(\'(.*?)\'.*?>.*?V-Cloud \[Resumable\].*?<\/a>""")
        val links = regex.findAll(document1.html()).mapNotNull { it.value }.toList()

        links.mapNotNull { link ->
            val document2 = app.get(link).document
            val vcloudRegex = Regex("""https:\/\/vcloud\.lol\/[^\s"]+""")
            val vcloudLinks = vcloudRegex.findAll(document2.html()).mapNotNull { it.value }.toList()

            if (vcloudLinks.isNotEmpty()) {
                loadExtractor(vcloudLinks.first(), subtitleCallback, callback)
            }
        }
        return true
    }
}
}
