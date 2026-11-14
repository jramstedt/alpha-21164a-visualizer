package dec.alpha.ev56

data class LineSchedule(
    val lineNumber: Int,
    val isInstruction: Boolean,
    val assignedSlot: PipeSlot? = null,
    val stallCyclesBefore: Int = 0,
    val stallReason: String? = null,
    val branchPenalty: Int? = null,
    val fetchEvent: IFetchEvent? = null,
    val activeSlotsThisCycle: Set<PipeSlot> = emptySet()
)

class Alpha21164ASimulator {

    fun analyze(documentText: String): Map<Int, LineSchedule> {
        val rawLines = documentText.lines()
        val cleanLines = AlphaInstructionTable.stripComments(documentText)
        val result = mutableMapOf<Int, LineSchedule>()

        val mafTracker = MafTracker()
        val wbufTracker = WriteBufferTracker()
        val icacheTracker = ICacheTracker()
        val condTracker = GasConditionalTracker()

        val regReadyCycle = mutableMapOf<String, Int>()
        var currentCycle = 0
        var currentPc = 0x10000L
        var pendingLabel = false
        var usedSlotsInCurrentCycle = mutableSetOf<PipeSlot>()
        var fmUnitFreeCycle = 0
        var e0MulUnitFreeCycle = 0

        for ((lineIdx, cleanLine) in cleanLines.withIndex()) {
            if (cleanLine.isEmpty()) continue

            if (condTracker.processDirective(cleanLine)) continue
            if (!condTracker.isActive()) continue

            if (cleanLine.startsWith(".align")) {
                val power = cleanLine.removePrefix(".align").trim().toIntOrNull() ?: 4
                val alignBytes = 1L shl power
                val rem = currentPc % alignBytes
                if (rem != 0L) currentPc += (alignBytes - rem)
                continue
            }

            if (cleanLine.startsWith(".") || cleanLine.startsWith("#")) continue

            val codePart = if (cleanLine.contains(":")) {
                pendingLabel = true
                cleanLine.substringAfterLast(":").trim()
            } else {
                cleanLine
            }

            if (codePart.isEmpty()) continue

            val rawLine = rawLines.getOrElse(lineIdx) { cleanLine }
            val inst = AlphaInstructionTable.decodeClean(lineIdx, codePart, rawLine) ?: continue
            val isICacheMiss = rawLine.contains("# icache_miss", ignoreCase = true)

            var stallCycles = 0
            val reasons = mutableListOf<String>()

            // 1. I-Cache fetch check
            val fetchEvent = icacheTracker.fetchInstruction(
                pc = currentPc,
                currentCycle = currentCycle,
                hasLabelAbove = pendingLabel,
                isMarkedICacheMiss = isICacheMiss
            )
            pendingLabel = false

            if (fetchEvent.icacheMissStall > 0) {
                stallCycles += fetchEvent.icacheMissStall
                currentCycle += fetchEvent.icacheMissStall
                usedSlotsInCurrentCycle.clear()
                reasons.add("I-Cache Miss")
            }

            // 2. RAW data hazard check
            val earliestDataCycle = inst.srcRegs.maxOfOrNull { regReadyCycle[it] ?: 0 } ?: 0
            if (earliestDataCycle > currentCycle) {
                val rawStall = earliestDataCycle - currentCycle
                stallCycles += rawStall
                currentCycle = earliestDataCycle
                usedSlotsInCurrentCycle.clear()
                reasons.add("RAW")
            }

            // 3. Non-pipelined unit check (FP Divide / Integer Multiply)
            if ((inst.type == InstType.FP_DIV || inst.type == InstType.FP_MUL) && fmUnitFreeCycle > currentCycle) {
                val unitStall = fmUnitFreeCycle - currentCycle
                stallCycles += unitStall
                currentCycle = fmUnitFreeCycle
                usedSlotsInCurrentCycle.clear()
                reasons.add("FM Busy")
            }
            if (inst.type == InstType.IMUL && e0MulUnitFreeCycle > currentCycle) {
                val unitStall = e0MulUnitFreeCycle - currentCycle
                stallCycles += unitStall
                currentCycle = e0MulUnitFreeCycle
                usedSlotsInCurrentCycle.clear()
                reasons.add("IMUL Busy")
            }

            // 4. Memory subsystem (MAF & Write Buffer)
            var effectiveLatency = inst.latency
            when (inst.type) {
                InstType.LOAD -> {
                    val wbufConflict = wbufTracker.checkLoadConflict(currentCycle, inst.baseReg ?: "$31", inst.memOffset)
                    if (wbufConflict > 0) {
                        stallCycles += wbufConflict
                        currentCycle += wbufConflict
                        usedSlotsInCurrentCycle.clear()
                        reasons.add("WBUF Conflict")
                    }
                    val isPrefetch = inst.destReg in setOf("$31", "\$f31", "zero")
                    val (mafStall, memReady) = mafTracker.accessMemory(
                        currentCycle = currentCycle,
                        baseReg = inst.baseReg ?: "$31",
                        offset = inst.memOffset,
                        isPrefetchOrZeroDest = isPrefetch,
                        assumeL1Miss = inst.isMissHint
                    )
                    if (mafStall > 0) {
                        stallCycles += mafStall
                        currentCycle += mafStall
                        usedSlotsInCurrentCycle.clear()
                        reasons.add("MAF Full (6/6)")
                    }
                    effectiveLatency = maxOf(1, memReady - currentCycle)
                }
                InstType.PREFETCH -> {
                    mafTracker.accessMemory(
                        currentCycle = currentCycle,
                        baseReg = inst.baseReg ?: "$31",
                        offset = inst.memOffset,
                        isPrefetchOrZeroDest = true,
                        assumeL1Miss = true
                    )
                }
                InstType.STORE -> {
                    val wbufStall = wbufTracker.store(
                        currentCycle = currentCycle,
                        baseReg = inst.baseReg ?: "$31",
                        offset = inst.memOffset,
                        activeLoadMisses = mafTracker.activeLoadCount(currentCycle)
                    )
                    if (wbufStall > 0) {
                        stallCycles += wbufStall
                        currentCycle += wbufStall
                        usedSlotsInCurrentCycle.clear()
                        reasons.add("WBUF Full (6/6)")
                    }
                }
                InstType.BARRIER -> {
                    val barrierStall = wbufTracker.flushBarrier(currentCycle)
                    if (barrierStall > 0) {
                        stallCycles += barrierStall
                        currentCycle += barrierStall
                        usedSlotsInCurrentCycle.clear()
                        reasons.add("WBUF Drain")
                    }
                }
                else -> {}
            }

            // 5. Slot assignment & structural hazard check
            var chosenSlot = inst.allowedSlots.firstOrNull { it !in usedSlotsInCurrentCycle }
            if (chosenSlot == null) {
                currentCycle++
                usedSlotsInCurrentCycle.clear()
                chosenSlot = inst.allowedSlots.first()
            }

            usedSlotsInCurrentCycle.add(chosenSlot)

            if (inst.nonPipelinedBusyCycles > 0) {
                if (inst.type == InstType.FP_DIV) fmUnitFreeCycle = currentCycle + inst.nonPipelinedBusyCycles
                if (inst.type == InstType.IMUL) e0MulUnitFreeCycle = currentCycle + inst.nonPipelinedBusyCycles
            }

            if (inst.destReg != null && inst.destReg !in setOf("$31", "\$f31", "zero")) {
                regReadyCycle[inst.destReg] = currentCycle + effectiveLatency
            }

            result[lineIdx] = LineSchedule(
                lineNumber = lineIdx,
                isInstruction = true,
                assignedSlot = chosenSlot,
                stallCyclesBefore = stallCycles,
                stallReason = reasons.joinToString(", ").ifEmpty { null },
                branchPenalty = inst.branchPenalty,
                fetchEvent = fetchEvent,
                activeSlotsThisCycle = usedSlotsInCurrentCycle.toSet()
            )

            currentPc += 4L
        }

        return result
    }
}
