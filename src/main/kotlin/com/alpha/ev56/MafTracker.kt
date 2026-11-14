package dec.alpha.ev56

data class MafEntry(
    val cacheBlockTag: String,
    val readyCycle: Int
)

class MafTracker(
    private val maxLoadEntries: Int = 6,
    private val maxIstreamPrefetchEntries: Int = 4,
    private val l2HitLatency: Int = 10
) {
    private val loadMaf = mutableListOf<MafEntry>()
    private val prefetchMaf = mutableListOf<MafEntry>()

    fun activeLoadCount(currentCycle: Int): Int {
        loadMaf.removeAll { it.readyCycle <= currentCycle }
        return loadMaf.size
    }

    fun accessMemory(
        currentCycle: Int,
        baseReg: String,
        offset: Int,
        isPrefetchOrZeroDest: Boolean,
        assumeL1Miss: Boolean = false
    ): Pair<Int, Int> {
        loadMaf.removeAll { it.readyCycle <= currentCycle }
        prefetchMaf.removeAll { it.readyCycle <= currentCycle }

        val blockKey = "$baseReg:${offset shr 5}"

        if (isPrefetchOrZeroDest) {
            val existing = prefetchMaf.find { it.cacheBlockTag == blockKey }
            if (existing != null) return Pair(0, existing.readyCycle)

            if (prefetchMaf.size < maxIstreamPrefetchEntries) {
                val ready = currentCycle + l2HitLatency
                prefetchMaf.add(MafEntry(blockKey, ready))
                return Pair(0, ready)
            }
            return Pair(0, currentCycle + 1)
        }

        if (!assumeL1Miss) {
            val inFlight = loadMaf.find { it.cacheBlockTag == blockKey }
            if (inFlight != null) {
                return Pair(0, inFlight.readyCycle)
            }
            return Pair(0, currentCycle + 2)
        }

        val merged = loadMaf.find { it.cacheBlockTag == blockKey }
        if (merged != null) {
            return Pair(0, merged.readyCycle)
        }

        var stallCycles = 0
        var issueCycle = currentCycle
        if (loadMaf.size >= maxLoadEntries) {
            val earliestFree = loadMaf.minOf { it.readyCycle }
            if (earliestFree > currentCycle) {
                stallCycles = earliestFree - currentCycle
                issueCycle = earliestFree
                loadMaf.removeAll { it.readyCycle <= issueCycle }
            }
        }

        val readyCycle = issueCycle + l2HitLatency
        loadMaf.add(MafEntry(blockKey, readyCycle))
        return Pair(stallCycles, readyCycle)
    }
}
