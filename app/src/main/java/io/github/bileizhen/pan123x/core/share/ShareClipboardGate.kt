package io.github.bileizhen.pan123x.core.share

/** Session-local deduplication; does not persist clipboard text or issue any network call. */
class ShareClipboardGate {
    private val seen = LinkedHashSet<String>()
    fun take(text: String, fromThisApp: Boolean = false): SharedLink? {
        if (fromThisApp) return null
        val link = ShareLinkParser.parse(text) ?: return null
        if (!seen.add(link.identity)) return null
        if (seen.size > 32) seen.remove(seen.first())
        return link
    }
}
