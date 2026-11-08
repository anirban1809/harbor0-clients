package app.harbor0.android

import org.junit.Assert.*
import org.junit.Test

class DriveAccessTest {
    @Test fun ownerMayDoEverything() {
        assertEquals(ItemAccess(owner = true, edit = true, manage = true, share = true), itemAccess(null))
        assertEquals(ItemAccess(owner = true, edit = true, manage = true, share = true), itemAccess(null, "EDITOR"))
    }
    @Test fun editorManagesOnlyInsideASharedFolder() {
        // The shared root itself: no folder access, so no rename, move or trash.
        assertEquals(ItemAccess(owner = false, edit = true, manage = false, share = false), itemAccess("EDITOR"))
        assertEquals(ItemAccess(owner = false, edit = true, manage = true, share = false), itemAccess("EDITOR", "EDITOR"))
    }
    @Test fun viewerOnlyViews() {
        assertEquals(ItemAccess(owner = false, edit = false, manage = false, share = false), itemAccess("VIEWER", "VIEWER"))
        assertEquals(ItemAccess(owner = false, edit = false, manage = false, share = false), itemAccess(DriveItem("a", "a", "FILE", access = "VIEWER")))
    }
    @Test fun viewersCannotWriteIntoAFolder() {
        assertTrue(canWriteIn(null))
        assertTrue(canWriteIn("EDITOR"))
        assertFalse(canWriteIn("VIEWER"))
    }
}
