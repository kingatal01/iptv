package com.yamking.iptv.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class M3uParserTest {
    private val sample = """
        #EXTM3U url-tvg="https://example.org/epg.xml.gz"
        #PLAYLIST:Popular Movies

        #EXTINF:-1 tvg-id="4everCinema.ua@SD" tvg-logo="https://i.imgur.com/vFOgVbG.png" group-title="Movies",4ever Cinema (1080p)
        http://stream.mcquack.net/258/index.m3u8

        #EXTINF:-1 tvg-id="30ATV.us@SD" group-title="Movies",30A TV Classic Movies (720p)
        https://30a-tv.com/feeds/pzaz/30atvmovies.m3u8

        #EXTINF:-1 tvg-id="x" tvg-logo="https://a/b.png" group-title="Movies;Series",50 Cent Action, Vol. 2 (720p) [Geo-blocked]
        #EXTVLCOPT:http-user-agent=Mozilla
        https://jmp2.uk/plu-x.m3u8

        #EXTINF:-1,Orpheline
        pas-une-url
    """.trimIndent()

    @Test
    fun parsesChannels() {
        val channels = M3uParser.parse(sample)
        assertEquals(3, channels.size)

        val first = channels[0]
        assertEquals("4ever Cinema", first.name)
        assertEquals("1080p", first.quality)
        assertEquals("http://stream.mcquack.net/258/index.m3u8", first.url)
        assertEquals("https://i.imgur.com/vFOgVbG.png", first.logo)
        assertEquals(listOf("Movies"), first.groups)
    }

    @Test
    fun handlesMissingLogoMultipleGroupsAndCommas() {
        val channels = M3uParser.parse(sample)
        assertNull(channels[1].logo)
        assertEquals(listOf("Movies", "Series"), channels[2].groups)
        assertEquals("50 Cent Action, Vol. 2", channels[2].name)
        assertEquals("https://jmp2.uk/plu-x.m3u8", channels[2].url)
    }

    @Test
    fun recoversNameFromMalformedLine() {
        val line = "#EXTINF:-1 tvg-id=\"Dorama.ru@HD\" group-title=\"Movies\",like Gecko) Safari/537.36\" group-title=\"Movies\",Dorama HD (1080p)\nhttp://host/412/index.m3u8"
        val channel = M3uParser.parse(line).single()
        assertEquals("Dorama HD", channel.name)
        assertEquals("1080p", channel.quality)
        assertEquals("Sans attribut", M3uParser.parse("#EXTINF:-1,Sans attribut\nhttp://host/a.m3u8").single().name)
    }

    @Test
    fun readsCountryAndLanguage() {
        val channels = M3uParser.parse(sample)
        assertEquals("UA", channels[0].country) // déduit de « 4everCinema.ua@SD »
        assertEquals("US", channels[1].country)
        assertNull(channels[2].country)         // identifiant technique sans pays
        assertNull(channels[0].language)        // jamais devinée

        val declared = M3uParser.parse(
            "#EXTINF:-1 tvg-id=\"BBCOne.uk\" tvg-country=\"FR;BE\" tvg-language=\"French;Dutch\",Test\nhttp://h/a.m3u8\n" +
                "#EXTINF:-1 tvg-id=\"BBCOne.uk@HD\",BBC\nhttp://h/b.m3u8",
        )
        assertEquals("FR", declared[0].country)     // le pays déclaré prime sur l'identifiant
        assertEquals("French", declared[0].language)
        assertEquals("GB", declared[1].country)     // « uk » n'est pas un code ISO
    }

    @Test
    fun resolvesRelativeHlsAddresses() {
        val base = "https://host.tv/live/ch1/master.m3u8?token=a/b"
        assertEquals("https://host.tv/live/ch1/720p.m3u8", StreamChecker.resolve(base, "720p.m3u8"))
        assertEquals("https://host.tv/seg/1.ts", StreamChecker.resolve(base, "/seg/1.ts"))
        assertEquals("https://cdn.tv/1.ts", StreamChecker.resolve(base, "//cdn.tv/1.ts"))
        assertEquals("http://other.tv/x.m3u8", StreamChecker.resolve(base, "http://other.tv/x.m3u8"))
    }

    @Test
    fun exportedPlaylistReadsBackIdentically() {
        val channels = M3uParser.parse(sample)
        assertEquals(channels, M3uParser.parse(M3uParser.write(channels)))
    }
}