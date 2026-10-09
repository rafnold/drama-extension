// DramaExtension - Asian drama streaming providers
// (DramaNice + KDrama.in + KissAsian + Dramahood + KissKH + Primeshows)

// Use a dotted string for version numbers (AI_RULES §6): 35.0.0.1 for the
// next release after v35. Bump only on meaningful changes — not reflexively.
version = "35.0.0.1"

dependencies {
    // jsoup 1.18.3 is compiled against jspecify annotations (provided scope),
    // so they must be present on the compile classpath.
    implementation("org.jspecify:jspecify:1.0.0")
}

cloudstream {
    description = "Asian drama streaming sites: DramaNice (dramanice.boo), KDrama.in (k-drama.in), KissAsian (kissasian.com.lv), Dramahood (dramahood.mom) and KissKH (kisskh.or.at) and Primeshows (primeshows.org). Genre and country tabs (wuxia, fantasy, historical, K/C/J/Thai...), TMDB ratings and episode counts on cards, final m3u8 sources with subtitles."
    authors = listOf("drama-scraper")

    /**
     * Status int as one of the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta-only
     **/
    status = 1

    tvTypes = listOf("TV", "Movie")
    language = "en"

    // No resources are required (no custom fragment/settings UI).
    requiresResources = false

    iconUrl = "https://dramanice.boo/wp-content/uploads/2026/08/Hold-on-Harutora-kun-2026-poster.jpg"
}
