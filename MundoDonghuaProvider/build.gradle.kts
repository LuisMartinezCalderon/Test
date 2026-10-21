// use an integer for version numbers
version = 9


cloudstream {
    // All of these properties are optional, you can safely remove them

    description = "Anime and Movies"
    language    = "es"
    authors = listOf("LuisM")

    /**
    * Status int as the following:
    * 0: Down
    * 1: Ok
    * 2: Slow
    * 3: Beta only
    * */
    status = 1 // will be 3 if unspecified

    // List of video source types. Users are able to filter for extensions in a given category.
    // You can find a list of available types here:
    // https://recloudstream.github.io/cloudstream/html/app/com.lagradost.cloudstream3/-tv-type/index.html
    tvTypes = listOf("AnimeMovie","Anime","Cartoon")
    iconUrl = "https://th.bing.com/th/id/ODF.Nvq9EjlTS5Yx4c6ObQ_ZzQ?w=32&h=32&qlt=90&pcl=fffffa&r=0&o=6&cb=ucfimg1&pid=1.2&ucfimg=1"

    isCrossPlatform = true
}
