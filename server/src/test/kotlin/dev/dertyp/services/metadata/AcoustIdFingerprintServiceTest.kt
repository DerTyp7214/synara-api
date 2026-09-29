package dev.dertyp.services.metadata

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.spyk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class AcoustIdFingerprintServiceTest {

    private fun spiedService(output: String?, toolPath: String? = "/opt/fpcalc"): AcoustIdFingerprintService {
        val service = spyk<AcoustIdFingerprintService>(recordPrivateCalls = true)
        every { service getProperty "fpcalcPath" } returns toolPath
        coEvery { service["runFpcalc"](any<String>()) } returns output
        return service
    }

    @Test
    fun `fpcalc json output is parsed`() = runBlocking {
        val service = spiedService("""{"duration": 243.53, "fingerprint": "AQADtEmUaEkSRZEGAAAA"}""")

        val result = service.fingerprint("song.flac")

        assertEquals(Fingerprint(243, "AQADtEmUaEkSRZEGAAAA"), result)
        coVerify { service["runFpcalc"]("song.flac") }
    }

    @Test
    fun `warnings around the json are ignored`() = runBlocking {
        val service = spiedService(
            "[mp3float @ 0x1] Could not update timestamps for skipped samples.\n{\"duration\": 12, \"fingerprint\": \"AQAB\"}\n"
        )

        assertEquals(Fingerprint(12, "AQAB"), service.fingerprint("song.mp3"))
    }

    @Test
    fun `failed or unusable output yields no fingerprint`() = runBlocking {
        assertNull(spiedService(null).fingerprint("song.flac"))
        assertNull(spiedService("ERROR: Could not open the input file").fingerprint("song.flac"))
        assertNull(spiedService("""{"duration": 10, "fingerprint": ""}""").fingerprint("song.flac"))
    }

    @Test
    fun `a missing fpcalc never runs a process`() = runBlocking {
        val service = spiedService("""{"duration": 1, "fingerprint": "AQAB"}""", toolPath = null)

        assertNull(service.fingerprint("song.flac"))
        coVerify(exactly = 0) { service["runFpcalc"](any<String>()) }
    }
}
