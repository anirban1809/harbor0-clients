package app.harbor0.android

/**
 * What the caller may do with an item, as the web's `itemAccess` (apps/web/lib/drive-access.ts).
 * [access] is set only on someone else's item reached through a share; [folderAccess] is the open
 * folder's. Outside a shared folder, someone else's item is the shared item itself, which only its
 * owner may rename, move or trash.
 */
data class ItemAccess(
    val owner: Boolean,
    /** Upload new versions and restore old ones. */
    val edit: Boolean,
    /** Rename, move and trash. */
    val manage: Boolean,
    /** Share, send and favourite act on the owner's copy, so only the owner may. */
    val share: Boolean,
)
fun itemAccess(access: String?, folderAccess: String? = null): ItemAccess {
    val owner = access.isNullOrEmpty()
    val edit = owner || access == "EDITOR"
    return ItemAccess(owner, edit, edit && (owner || !folderAccess.isNullOrEmpty()), owner)
}
fun itemAccess(item: DriveItem, folderAccess: String? = null) = itemAccess(item.access, folderAccess)
/** Uploads and new folders are refused in a folder shared with the caller as a viewer. */
fun canWriteIn(folderAccess: String?) = folderAccess != "VIEWER"
