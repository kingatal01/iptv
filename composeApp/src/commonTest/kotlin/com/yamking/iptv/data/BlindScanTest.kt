package com.yamking.iptv.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BlindScanTest {
    @Test
    fun readsDuckDuckGoResultLinks() {
        val html = """
            <a rel="nofollow" class="result__a" href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fgithub.com%2Fiptv%2Dorg%2Fiptv&amp;rut=abc">iptv</a>
            <a class="result__a" href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fexample.org%2Flist.m3u%3Fa%3D1&amp;rut=def">liste</a>
        """.trimIndent()
        assertEquals(
            listOf("https://github.com/iptv-org/iptv", "https://example.org/list.m3u?a=1"),
            BlindScanner.duckDuckGoLinks(html),
        )
    }

    @Test
    fun findsPlaylistLinksInAPage() {
        val html = """
            <a href="https://example.org/fr.m3u">fr</a> texte https://cdn.example.org/iptv/all.m3u8?token=1&amp;x=2 fin
            <a href="https://live.chaine.tv/hls/master.m3u8">flux d'une seule chaîne : ignoré</a>
            <a href="/user/repo/blob/main/lists/fr.m3u">github</a>
            <a href="https://www.youtube.com/watch.m3u8">bloqué</a>
        """.trimIndent()
        assertEquals(
            listOf(
                "https://example.org/fr.m3u",
                "https://cdn.example.org/iptv/all.m3u8?token=1&x=2",
                "https://raw.githubusercontent.com/user/repo/main/lists/fr.m3u",
            ),
            BlindScanner.playlistLinks(html, "https://github.com/user/repo"),
        )
    }

    @Test
    fun tellsChannelListsFromSingleStreams() {
        assertTrue(BlindScanner.isChannelList("#EXTM3U\n#EXTINF:-1 tvg-id=\"a\",A\nhttp://h/a.m3u8\n"))
        assertFalse(BlindScanner.isChannelList("#EXTM3U\n#EXT-X-TARGETDURATION:6\n#EXTINF:6.0,\nseg1.ts\n"))
        assertFalse(BlindScanner.isChannelList("#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1\nlow.m3u8\n"))
        assertFalse(BlindScanner.isChannelList("<html><body>404</body></html>"))
    }

    @Test
    fun recognisesPlaylistAddresses() {
        assertTrue(BlindScanner.isPlaylistUrl("https://h/list.M3U?x=1"))
        assertFalse(BlindScanner.isPlaylistUrl("https://h/page.html"))
        assertEquals(
            "https://raw.githubusercontent.com/u/r/main/a.m3u",
            BlindScanner.rawGitHub("https://github.com/u/r/blob/main/a.m3u"),
        )
        assertEquals(
            "GitHub Free-TV/IPTV · playlist_france.m3u8",
            BlindScanner.sourceLabel("https://raw.githubusercontent.com/Free-TV/IPTV/master/playlists/playlist_france.m3u8"),
        )
        assertEquals("iptv-org.github.io · fra.m3u", BlindScanner.sourceLabel("https://iptv-org.github.io/iptv/languages/fra.m3u?x=1"))
        assertTrue(BlindScanner.isBlocked("www.youtube.com"))
        assertFalse(BlindScanner.isBlocked("raw.githubusercontent.com"))
    }

    @Test
    fun readsYahooResultLinks() {
        val html = """
            <a href="https://r.search.yahoo.com/_ylt=Awr/RV=2/RE=1/RO=10/RU=https%3a%2f%2fgithub.com%2fFree-TV%2fIPTV/RK=2/RS=abc-">a</a>
            <a href="https://r.search.yahoo.com/_ylt=Awr/RV=2/RE=1/RO=10/RU=https%3a%2f%2fwww.yahoo.com%2fnews%2f/RK=2/RS=def-">menu Yahoo : ignoré</a>
            <a href="https://r.search.yahoo.com/_ylt=Awr/RV=2/RE=1/RO=10/RU=https%3a%2f%2fexample.org%2ffr.m3u/RK=2/RS=ghi-">b</a>
        """.trimIndent()
        assertEquals(listOf("https://github.com/Free-TV/IPTV", "https://example.org/fr.m3u"), BlindScanner.yahooLinks(html))
    }

    @Test
    fun readsRepositoriesFromGitHubAndGitLab() {
        val gitHub = """{"total_count":2,"items":[
            {"id":1,"full_name":"Free-TV/IPTV","owner":{"login":"Free-TV"},"default_branch":"master"},
            {"id":2,"full_name":"vide/depot","default_branch":null}]}"""
        assertEquals(listOf("Free-TV/IPTV" to "master"), BlindScanner.repositories(gitHub, "full_name"))

        val gitLab = """[{"id":7,"path_with_namespace":"groupe/tv","default_branch":"main","namespace":{"id":3,"full_path":"groupe"}}]"""
        assertEquals(listOf("groupe/tv" to "main"), BlindScanner.repositories(gitLab, "path_with_namespace"))
        assertTrue(BlindScanner.repositories("<html>limite atteinte</html>", "full_name").isEmpty())
    }

    @Test
    fun findsPlaylistFilesInARepositoryTree() {
        // Liste de fichiers volontairement tronquée, comme une réponse coupée par la limite de taille.
        val tree = """{"tree":[{"path":"README.md","type":"blob"},{"path":"lists/fr.m3u","type":"blob"},
            {"path":"playlists/playlist_france.m3u8","type":"blob"},{"path":"src/main.py","ty"""
        assertEquals(listOf("lists/fr.m3u", "playlists/playlist_france.m3u8"), BlindScanner.treePlaylists(tree))
        assertEquals("GitLab groupe/tv · fr.m3u", BlindScanner.sourceLabel("https://gitlab.com/groupe/tv/-/raw/main/lists/fr.m3u"))
    }
}