package dec.alpha.ev56

data class WbufEntry(
    val cacheBlockTag: String,
    var drainCompleteCycle: Int,
    var isDraining: Boolean = false
)

class WriteBufferTracker(
    private val maxEntries: Int = 6,
    private val sCacheWriteLatency: Int = 8
) {
    private val entries = mutableListOf<WbufEntry>()

    fun advanceCycle(currentCycle: Int, activeLoadMisses: Int) {
        entries.removeAll { it.drainCompleteCycle <= currentCycle }

        if (activeLoadMisses == 0 && entries.isNotEmpty()) {
            val oldest = entries.first()
            if (!oldest.isDraining) {
                oldest.isDraining = true
                oldest.drainCompleteCycle = currentCycle + sCacheWriteLatency
            }
        }
    }

    fun store(
        currentCycle: Int,
        baseReg: String,
        offset: Int,
        activeLoadMisses: Int
    ): Int {
        advanceCycle(currentCycle, activeLoadMisses)
        val blockKey = "$baseReg:${offset shr 5}"

        val mergeTarget = entries.find { it.cacheBlockTag == blockKey && !it.isDraining }
        if (mergeTarget != null) {
            return 0
        }

        var stallCycles = 0
        var issueCycle = currentCycle
        if (entries.size >= maxEntries) {
            val earliest = entries.first()
            if (!earliest.isDraining) {
                earliest.isDraining = true
                earliest.drainCompleteCycle = currentCycle + sCacheWriteLatency
            }
            if (earliest.drainCompleteCycle > currentCycle) {
                stallCycles = earliest.drainCompleteCycle - currentCycle
                issueCycle = earliest.drainCompleteCycle
                entries.removeAt(0)
            }
        }

        val drainCycle = issueCycle + sCacheWriteLatency + (activeLoadMisses * 2)
        entries.add(WbufEntry(blockKey, drainCycle, isDraining = (activeLoadMisses == 0)))
        return stallCycles
    }

    fun checkLoadConflict(currentCycle: Int, baseReg: String, offset: Int): Int {
        val blockKey = "$baseReg:${offset shr 5}"
        val conflict = entries.find { it.cacheBlockTag == blockKey } ?: return 0
        return if (conflict.drainCompleteCycle > currentCycle) {
            conflict.drainCompleteCycle - currentCycle
        } else {
            0
        }
    }

    fun flushBarrier(currentCycle: Int): Int {
        if (entries.isEmpty()) return 0
        val lastDrain = entries.maxOf { it.drainCompleteCycle }
        entries.clear()
        return if (lastDrain > currentCycle) lastDrain - currentCycle else 0
    }
}
