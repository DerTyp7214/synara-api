package dev.dertyp.services.lyrics.parse

import java.io.ByteArrayOutputStream
import java.util.zip.Deflater

object LyricsFixtures {
    private val krcKey = intArrayOf(0x40, 0x47, 0x61, 0x77, 0x5e, 0x32, 0x74, 0x47, 0x51, 0x36, 0x31, 0x2d, 0xce, 0xd2, 0x6e, 0x69)

    val duetTtml = """
        <?xml version="1.0" encoding="UTF-8"?>
        <tt xmlns="http://www.w3.org/ns/ttml" xmlns:ttm="http://www.w3.org/ns/ttml#metadata" xmlns:itunes="http://music.apple.com/lyric-ttml-internal" itunes:timing="Word" xml:lang="en">
          <head>
            <metadata>
              <ttm:agent type="person" xml:id="v1"><ttm:name type="full">Alice</ttm:name></ttm:agent>
              <ttm:agent type="person" xml:id="v2"><ttm:name type="full">Bob</ttm:name></ttm:agent>
              <ttm:agent type="group" xml:id="v1000"/>
            </metadata>
          </head>
          <body dur="3:05.500">
            <div begin="00:01.000" end="00:20.000">
              <p begin="00:01.000" end="00:04.000" ttm:agent="v1"><span begin="00:01.000" end="00:01.400">Hel</span><span begin="00:01.400" end="00:01.900">lo</span> <span begin="00:02.000" end="00:02.600">world</span><span ttm:role="x-translation" xml:lang="de">Hallo Welt</span><span ttm:role="x-bg"><span begin="00:02.700" end="00:03.200">(oh</span> <span begin="00:03.200" end="00:03.900">yeah)</span></span></p>
              <p begin="00:05.000" end="00:08.000" ttm:agent="v2"><span begin="5s" end="5.5s">Second</span> <span begin="5500ms" end="6.2s">voice</span>, <span begin="0:0:6.300" end="0:0:7.000">sings</span></p>
              <p begin="00:09.000" end="00:11.000" ttm:agent="v1">Untimed line</p>
            </div>
          </body>
        </tt>
    """.trimIndent()

    val enhancedLrc = """
        [ti:Song]
        [ar:Artist]
        [offset:+500]
        [00:10.50]<00:10.50>Hel<00:10.90>lo <00:11.20>world<00:12.00>
        [00:13.00]<00:13.00>Out <00:14.00>of <00:13.50>order
        [00:15.00][00:20.00]Chorus line
    """.trimIndent()

    val krcText = """
        [id:${'$'}00000000]
        [ar:Artist]
        [ti:Song]
        [total:185000]
        [offset:0]
        [0,1500]<0,750,0>Song <750,750,0>- Artist
        [1500,1000]<0,1000,0>作词：Somebody
        [3000,2000]<0,500,0>I&apos;m <500,700,0>sing<1200,800,0>ing
        [5000,2000]<0,1000,0>我在<1000,1000,0>唱歌
        [7000,2500]<0,1200,0>Second <1200,1300,0>line
        [9500,2000]<0,2000,0>第二行
    """.trimIndent()

    val lyricsPlusWord = """
        {
          "type": "Word",
          "metadata": {"source": "test"},
          "lyrics": [
            {
              "time": 1000, "duration": 3000, "text": "Hello world",
              "syllabus": [
                {"time": 1000, "duration": 400, "text": "Hel", "isBackground": false},
                {"time": 1400, "duration": 500, "text": "lo ", "isBackground": false},
                {"time": 2000, "duration": 600, "text": "world", "isBackground": false},
                {"time": 2700, "duration": 900, "text": "(yeah)", "isBackground": true}
              ]
            },
            {
              "time": 5000, "duration": 2000, "text": "Bad timing",
              "syllabus": [
                {"time": 4000, "duration": 500, "text": "Bad ", "isBackground": false},
                {"time": 5500, "duration": 500, "text": "timing", "isBackground": false}
              ]
            }
          ]
        }
    """.trimIndent()

    val lyricsPlusZero = """
        {
          "type": "Word",
          "lyrics": [
            {"time": 0, "duration": 0, "text": "No timing", "syllabus": [
              {"time": 0, "duration": 0, "text": "No ", "isBackground": false},
              {"time": 0, "duration": 0, "text": "timing", "isBackground": false}
            ]},
            {"time": 0, "duration": 0, "text": "At all", "syllabus": []}
          ]
        }
    """.trimIndent()

    fun krcBlob(text: String): ByteArray {
        val deflater = Deflater()
        deflater.setInput(text.toByteArray(Charsets.UTF_8))
        deflater.finish()
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(1024)
        while (!deflater.finished()) output.write(buffer, 0, deflater.deflate(buffer))
        deflater.end()
        val compressed = output.toByteArray()
        val encrypted = ByteArray(compressed.size) { i -> (compressed[i].toInt() xor krcKey[i % krcKey.size]).toByte() }
        return "krc1".toByteArray(Charsets.US_ASCII) + encrypted
    }
}
