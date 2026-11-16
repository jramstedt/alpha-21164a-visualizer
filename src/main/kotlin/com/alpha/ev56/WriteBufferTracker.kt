package dec.alpha.ev56

data class WbufEntry(
    val cacheBlockTag: String,
    var drainCompleteCycle: Int,
    var closedByBarrier: Boolean = false
)

class WriteBufferTracker(
    private val sCacheWriteLatency: Int = 8
) {
    // EV56 Write Buffer has 6 entries, each holding up to one 32-byte cache line
    private val wbuf = mutableListOf<WbufEntry>()
    private var lastCycle = 0

    /**
     * Advances background draining of the Write Buffer, honoring EV56 MTU arbitration
     * where active Load MAF misses take priority over WBUF drains.
     */
    fun advanceCycle(currentCycle: Int, activeLoadMisses: Int) {
        if (currentCycle <= lastCycle) return
        val elapsed = currentCycle - lastCycle
        lastCycle = currentCycle

        // If Load MAF misses are active on the Memory Transaction Unit (MTU),
        // WBUF draining is delayed by contention.
        if (activeLoadMisses > 0) {
            for (entry in wbuf) {
                entry.drainCompleteCycle += elapsed
            }
        }

        wbuf.removeAll { it.drainCompleteCycle <= currentCycle }
    }

    /**
     * Issues a store into the 6-entry Write Buffer.
     * Sequential stores to the same 32-byte block merge into the open WBUF entry
     * unless closed by a memory barrier (wmb/mb).
     * Returns the number of stall cycles if all 6 entries are full.
     */
    fun store(
        currentCycle: Int,
        baseReg: String,
        offset: Int,
        activeLoadMisses: Int
    ): Int {
        advanceCycle(currentCycle, activeLoadMisses)

        val blockIndex = offset shr 5 // 32-byte cache block index
        val blockKey = "$baseReg:$blockIndex"

        // Merge into an existing open WBUF entry for the same 32-byte cache line
        val existing = wbuf.lastOrNull {
            it.cacheBlockTag == blockKey &&
                !it.closedByBarrier &&
                it.drainCompleteCycle > currentCycle
        }
        if (existing != null) {
            existing.drainCompleteCycle = maxOf(existing.drainCompleteCycle, currentCycle + 2)
            return 0
        }

        var stallCycles = 0
        var issueCycle = currentCycle

        // If all 6 WBUF entries are occupied, stall until the oldest entry drains
        if (wbuf.size >= 6) {
            val oldestFree = wbuf.minOf { it.drainCompleteCycle }
            if (oldestFree > currentCycle) {
                stallCycles = oldestFree - currentCycle
                issueCycle = oldestFree
            }
            wbuf.removeAll { it.drainCompleteCycle <= issueCycle }
        }

        wbuf.add(
            WbufEntry(
                cacheBlockTag = blockKey,
                drainCompleteCycle = issueCycle + sCacheWriteLatency
            )
        )
        return stallCycles
    }

    /**
     * Flushes and closes all active Write Buffer entries on wmb / mb barriers.
     * Returns the number of stall cycles required to drain pending stores.
     */
    fun flushBarrier(currentCycle: Int): Int {
        wbuf.removeAll { it.drainCompleteCycle <= currentCycle }
        if (wbuf.isEmpty()) return 0

        val maxDrainCycle = wbuf.maxOf { it.drainCompleteCycle }
        for (entry in wbuf) {
            entry.closedByBarrier = true
        }
        wbuf.clear()
        return maxOf(0, maxDrainCycle - currentCycle)
    }

    /**
     * Checks whether a load hits an active 32-byte block currently buffered in the WBUF.
     * Returns stall cycles required for the conflicting store block to drain first.
     */
    fun checkLoadConflict(currentCycle: Int, baseReg: String, offset: Int): Int {
        wbuf.removeAll { it.drainCompleteCycle <= currentCycle }
        val blockIndex = offset shr 5
        val blockKey = "$baseReg:$blockIndex"

        val conflict = wbuf.lastOrNull {
            it.cacheBlockTag == blockKey && it.drainCompleteCycle > currentCycle
        } ?: return 0

        return maxOf(0, conflict.drainCompleteCycle - currentCycle)
    }
}
