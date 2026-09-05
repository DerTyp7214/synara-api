package dev.dertyp.services.cover

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.time.Duration.Companion.seconds

class CoverAssetPackServiceTest {
    private fun pack(root: Path, id: String, tags: List<String>, nsfw: Boolean, backgrounds: Int = 2) {
        val dir = root.resolve(id).createDirectories()
        dir.resolve("backgrounds").createDirectories()
        repeat(backgrounds) { Files.write(dir.resolve("backgrounds/bg-$it.jpg"), byteArrayOf(1, 2, 3)) }
        dir.resolve("pack.json").writeText(
            """{"id":"$id","name":"$id","tags":${tags.joinToString(",", "[", "]") { "\"$it\"" }},"nsfw":$nsfw,"backgrounds":["backgrounds/*.jpg"]}"""
        )
    }

    private fun service(root: Path, nsfw: Boolean) = runBlocking {
        CoverAssetPackService(CoverConfig(root.toString(), nsfw, true, 1.seconds)).also { it.reload() }
    }

    @Test
    fun `loads packs, resolves globs and skips invalid manifests`(@TempDir root: Path) {
        pack(root, "grunge", listOf("rock", "energy:high"), nsfw = false, backgrounds = 3)
        root.resolve("broken").createDirectories().resolve("pack.json").writeText("{not json")
        val service = service(root, nsfw = false)
        val packs = service.packs(includeNsfw = true)
        assertEquals(listOf(CoverAssetPack.BUILTIN_ID, "grunge"), packs.map { it.id })
        assertEquals(3, packs.first { it.id == "grunge" }.backgrounds.size)
    }

    @Test
    fun `nsfw packs are hidden and never selected unless enabled and requested`(@TempDir root: Path) {
        pack(root, "spicy", listOf("explicit", "mood:sensual"), nsfw = true)
        pack(root, "calm", listOf("mood:calm"), nsfw = false)

        val disabled = service(root, nsfw = false)
        assertFalse(disabled.packs(includeNsfw = false).any { it.nsfw })
        assertEquals("calm", disabled.select(setOf("mood:calm"), 1, allowNsfw = true, packId = null).id)
        assertThrows(IllegalArgumentException::class.java) { disabled.select(emptySet(), 1, allowNsfw = true, packId = "spicy") }
        repeat(20) { seed ->
            assertFalse(disabled.select(setOf("explicit", "mood:sensual"), seed.toLong(), allowNsfw = true, packId = null).nsfw)
        }

        val enabled = service(root, nsfw = true)
        assertFalse(enabled.select(setOf("explicit"), 1, allowNsfw = false, packId = null).nsfw)
        assertEquals("spicy", enabled.select(setOf("explicit", "mood:sensual"), 1, allowNsfw = true, packId = null).id)
        assertEquals("spicy", enabled.select(emptySet(), 1, allowNsfw = true, packId = "spicy").id)
    }

    @Test
    fun `selection scores by tag overlap and is deterministic per seed`(@TempDir root: Path) {
        pack(root, "rock", listOf("rock", "metal", "energy:high"), nsfw = false)
        pack(root, "pop", listOf("pop", "energy:mid"), nsfw = false)
        val service = service(root, nsfw = false)
        assertEquals("rock", service.select(setOf("metal", "energy:high"), 5, allowNsfw = false, packId = null).id)
        assertEquals("pop", service.select(setOf("pop"), 5, allowNsfw = false, packId = null).id)
        val a = service.select(emptySet(), 99, allowNsfw = false, packId = null).id
        val b = service.select(emptySet(), 99, allowNsfw = false, packId = null).id
        assertEquals(a, b)
        assertThrows(IllegalArgumentException::class.java) { service.select(emptySet(), 1, allowNsfw = false, packId = "missing") }
    }

    @Test
    fun `missing asset directory yields only the builtin pack`(@TempDir root: Path) {
        val service = service(root.resolve("nope"), nsfw = false)
        val packs = service.packs(includeNsfw = true)
        assertEquals(1, packs.size)
        assertTrue(packs.single().isBuiltin)
        assertTrue(service.select(setOf("rock"), 1, allowNsfw = false, packId = null).isBuiltin)
    }
}
