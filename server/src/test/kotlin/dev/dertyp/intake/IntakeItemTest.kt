package dev.dertyp.intake

import dev.dertyp.ui.IntakeItem
import dev.dertyp.ui.UiIntakeCodeKind
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class IntakeItemTest {
    @Test
    fun `lines are classified`() {
        assertEquals(IntakeItem.Url("https://tidal.com/browse/track/1"), IntakeItem.parse("  https://tidal.com/browse/track/1 "))
        assertEquals(IntakeItem.Code(UiIntakeCodeKind.ISRC, "USRC17607839"), IntakeItem.parse("us-rc1-76-07839"))
        assertEquals(IntakeItem.Code(UiIntakeCodeKind.UPC, "0602577389818"), IntakeItem.parse("0602577389818"))
        assertEquals(IntakeItem.Id("tidal", "123"), IntakeItem.parse("tidal:123"))
        assertEquals(IntakeItem.Text("some song name"), IntakeItem.parse("some song name"))
        assertEquals(IntakeItem.Text("artist: title"), IntakeItem.parse("artist: title"))
    }

    @Test
    fun `parseLines skips blank lines`() {
        assertEquals(
            listOf(IntakeItem.Url("https://a"), IntakeItem.Text("b")),
            IntakeItem.parseLines("https://a\n\n  b \n"),
        )
    }
}
