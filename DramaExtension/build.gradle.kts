// DramaExtension - Asian drama streaming providers (DramaNice + KDrama.in)

// Use an integer for version numbers
version = 1

dependencies {
    // jsoup 1.18.3 is compiled against jspecify annotations (provided scope),
    // so they must be present on the compile classpath.
    implementation("org.jspecify:jspecify:1.0.0")
}

cloudstream {
    description = "Asian drama streaming sites: DramaNice (dramanice.boo) and KDrama.in (k-drama.in). Resolves final m3u8/mp4 sources."
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
