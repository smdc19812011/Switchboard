package app.switchboard.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Widths in dp of current Fold screens at Samsung's default 420 dpi scaling (pixels / 2.625). */
class PaneRulesTest {
    private val cover = 411f; private val fold2 = 673f; private val fold7 = 749f; private val fold7Land = 832f
    private val fold8u = 859f; private val fold8uLand = 953f

    @Test fun sidebarOnEveryUnfoldedFoldNotOnCovers() {
        assertTrue(cover < PaneRules.SIDEBAR_FROM)
        listOf(fold2, fold7, fold7Land, fold8u, fold8uLand).forEach { assertTrue("$it", it >= PaneRules.SIDEBAR_FROM) }
    }

    @Test fun sidebarScalesAndChatStaysReadable() {
        assertEquals(240f, PaneRules.defaultSidebar(fold2), 0.5f)            // narrowest unfolded: chat still gets ~430 dp
        assertEquals(292f, PaneRules.defaultSidebar(fold8u), 1f)
        assertEquals(320f, PaneRules.defaultSidebar(1400f), 0.5f)            // tablets / desktop windows cap at 320
        listOf(fold2, fold7, fold8u, fold8uLand).forEach { assertTrue("$it", it - PaneRules.defaultSidebar(it) >= 420f) }
    }

    @Test fun detailsSlidesOverOnFoldsAndIsAColumnOnBigScreens() {
        listOf(fold2, fold7, fold7Land, fold8u, fold8uLand).forEach { w ->
            assertFalse("$w", PaneRules.detailsAsColumn(w, PaneRules.defaultSidebar(w), PaneRules.defaultDetails(w)))
            assertFalse("$w", PaneRules.detailsOpenAtStart(w))
        }
        assertTrue(PaneRules.detailsAsColumn(fold8uLand, 0f, PaneRules.defaultDetails(fold8uLand))) // sidebar hidden: room for both
        assertTrue(PaneRules.detailsAsColumn(1280f, PaneRules.defaultSidebar(1280f), PaneRules.defaultDetails(1280f)))
        assertTrue(PaneRules.detailsOpenAtStart(1280f))
    }
}
