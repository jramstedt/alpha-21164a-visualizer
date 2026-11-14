package dec.alpha.ev56

data class IFetchEvent(
    val pc: Long,
    val is16ByteFetchBoundary: Boolean,
    val is32ByteLineBoundary: Boolean,
    val isUnalignedTarget: Boolean,
    val icacheMissStall: Int = 0
)

class ICacheTracker(
    private val sCacheFetchLatency: Int = 8
) {
    private val istreamMafReadyCycles = mutableListOf<Int>()

    fun fetchInstruction(
        pc: Long,
        currentCycle: Int,
        hasLabelAbove: Boolean,
        isMarkedICacheMiss: Boolean
    ): IFetchEvent {
        istreamMafReadyCycles.removeAll { it <= currentCycle }

        val is16ByteBoundary = (pc % 16L) == 0L
        val is32ByteBoundary = (pc % 32L) == 0L
        val isUnalignedTarget = hasLabelAbove && !is16ByteBoundary

        var missStall = 0
        if (isMarkedICacheMiss && is32ByteBoundary) {
            var fetchStart = currentCycle
            if (istreamMafReadyCycles.size >= 4) {
                val oldestFree = istreamMafReadyCycles.min()
                if (oldestFree > currentCycle) {
                    missStall += (oldestFree - currentCycle)
                    fetchStart = oldestFree
                    istreamMafReadyCycles.removeAll { it <= fetchStart }
                }
            }
            val ready = fetchStart + sCacheFetchLatency
            istreamMafReadyCycles.add(ready)
            missStall += sCacheFetchLatency
        }

        return IFetchEvent(
            pc = pc,
            is16ByteFetchBoundary = is16ByteBoundary,
            is32ByteLineBoundary = is32ByteBoundary,
            isUnalignedTarget = isUnalignedTarget,
            icacheMissStall = missStall
        )
    }
}
