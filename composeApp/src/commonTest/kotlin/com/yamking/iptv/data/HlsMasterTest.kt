package com.yamking.iptv.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HlsMasterTest {
    private val base = "https://cdn.tv/live/ch/master.m3u8?t=1"
    private val text = """
        #EXTM3U
        #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="aud",NAME="Français",LANGUAGE="fr",DEFAULT=YES,URI="audio/fr.m3u8"
        #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="aud",NAME="English",LANGUAGE="en",DEFAULT=NO,URI="audio/en.m3u8"
        #EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=640x360,CODECS="avc1.4d401e,mp4a.40.2",AUDIO="aud"
        360/index.m3u8
        #EXT-X-STREAM-INF:BANDWIDTH=5000000,RESOLUTION=1920x1080,AUDIO="aud"
        1080/index.m3u8
        #EXT-X-STREAM-INF:BANDWIDTH=2500000,RESOLUTION=1280x720,AUDIO="aud"
        https://other.tv/720.m3u8
        #EXT-X-STREAM-INF:BANDWIDTH=2000000,RESOLUTION=1280x720,AUDIO="aud"
        720-low/index.m3u8
    """.trimIndent()

    private val master = HlsMaster.parse(text, base)!!

    @Test
    fun listsQualitiesFromHighestToLowest() {
        assertEquals(listOf("1080p", "720p", "360p"), master.qualities.map { it.second })
        assertEquals("https://other.tv/720.m3u8", master.variants[master.qualities[1].first].url)
    }

    @Test
    fun picksBestQualityUnderTheCap() {
        assertEquals(1080, master.pick(null, null).height)
        assertEquals(720, master.pick(null, 720).height)
        assertEquals(360, master.pick(null, 480).height)
        assertEquals(360, master.pick(null, 240).height) // toutes trop hautes : la plus basse
        assertEquals(1080, master.pick(1, 480).height)   // le choix de l'utilisateur prime sur le plafond
    }

    @Test
    fun listsAudioTracksAndBuildsSinglePlaylist() {
        val variant = master.pick(null, 480)
        assertEquals(listOf("Français", "English"), master.audiosFor(variant).map { master.audios[it].name })
        assertEquals(0, master.defaultAudio(variant))

        val single = master.single(variant, master.audios[1])
        val reparsed = HlsMaster.parse(single, "file:///tmp/x.m3u8")!!
        assertEquals("https://cdn.tv/live/ch/360/index.m3u8", reparsed.variants.single().url)
        assertEquals("https://cdn.tv/live/ch/audio/en.m3u8", reparsed.audios.single().url)
        assertTrue(reparsed.audios.single().isDefault)
    }

    @Test
    fun fallsBackToBitrateWhenResolutionIsNotAnnounced() {
        val noResolution = HlsMaster.parse(
            "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=640000\na.m3u8\n#EXT-X-STREAM-INF:BANDWIDTH=3321000\nb.m3u8\n" +
                "#EXT-X-STREAM-INF:BANDWIDTH=1577000\nc.m3u8\n",
            base,
        )!!
        assertEquals(listOf("3321 kb/s", "1577 kb/s", "640 kb/s"), noResolution.qualities.map { it.second })
        assertEquals(3321000, noResolution.pick(null, 720).bandwidth)
        assertEquals(1577000, noResolution.pick(null, 480).bandwidth)
        assertEquals(640000, noResolution.pick(null, 360).bandwidth)
        val picked = noResolution.pick(null, 480)
        assertEquals(picked, noResolution.variants[noResolution.qualityOf(picked)])
    }

    @Test
    fun ignoresPlaylistsThatAreNotMasters() {
        assertNull(HlsMaster.parse("#EXTM3U\n#EXTINF:6.0,\nseg1.ts\n", base))
        assertNull(HlsMaster.parse("<html>", base))
    }
}
