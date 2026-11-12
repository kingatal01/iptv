package com.yamking.iptv.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TvListTest {
    private val film = Channel("Ciné", "http://h/1.m3u8", groups = listOf("Movies"), country = "FR", quality = "720p")
    private val both = Channel("Mixte", "http://h/2.m3u8", groups = listOf("Movies", "Series"), language = "French")
    private val news = Channel("Infos", "http://h/3.m3u8", groups = listOf("News"), logo = "http://h/l.png", tvgId = "Infos.fr")
    private val bare = Channel("Sans rien", "http://h/4.m3u8")
    private val all = listOf(film, both, news, bare)
    private val favorites = setOf(both.url, news.url)

    @Test
    fun selectsChannelsByCategory() {
        assertEquals(listOf(film, both), TvListFormat.select(all, setOf("Movies"), favorites, favoritesOnly = false))
        assertEquals(listOf(both), TvListFormat.select(all, setOf("Series"), favorites, false))
        assertEquals(listOf(bare), TvListFormat.select(all, setOf(TvListFormat.NO_CATEGORY), favorites, false))
        assertEquals(listOf(both), TvListFormat.select(all, setOf("Movies"), favorites, favoritesOnly = true))
        assertTrue(TvListFormat.select(all, emptySet(), favorites, false).isEmpty())
    }

    @Test
    fun exportedFileReadsBackIdentically() {
        val list = TvList(exportedAt = 42, categories = listOf("Movies", "News"), channels = listOf(film, both, news.copy(country = "FR")), favorites = favorites.toList())
        val text = TvListFormat.write(list)
        assertTrue("\"format\": \"iptv-player-list\"" in text)
        assertEquals(list, TvListFormat.read(text))
    }

    @Test
    fun fillsMissingCountryFromIdentifierAndCountsCategories() {
        // « news » n'a pas de pays mais son identifiant est « Infos.fr ».
        val read = TvListFormat.read(TvListFormat.write(TvList(channels = all)))!!
        assertEquals("FR", read.channels.first { it.name == "Infos" }.country)
        assertNull(read.channels.first { it.name == "Sans rien" }.country)
        assertEquals(
            listOf("Movies" to 2, "Series" to 1, "News" to 1, TvListFormat.NO_CATEGORY to 1),
            TvListFormat.categories(read),
        )
    }

    @Test
    fun rejectsOtherFiles() {
        assertNull(TvListFormat.read("#EXTM3U\n#EXTINF:-1,A\nhttp://h/a.m3u8"))
        assertNull(TvListFormat.read("""{"all":[{"name":"A","url":"http://h/a"}],"version":2}""")) // sauvegarde complète
        assertNull(TvListFormat.read("""{"format":"autre","channels":[{"name":"A","url":"http://h/a"}]}"""))
    }
}
