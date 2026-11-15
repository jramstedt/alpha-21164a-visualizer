package dec.alpha.ev56

data class LineSchedule(
    val lineNumber: Int,
    val isInstruction: Boolean,
    val cycle: Int = 0,
    val assignedSlot: PipeSlot? = null,
    val stallCyclesBefore: Int = 0,
    val branchPenalty: Int? = null,
    val fetchEvent: IFetchEvent? = null,
    val activeSlotsThisCycle: Set<PipeSlot> = emptySet()
)

class Alpha21164ASimulator {

    fun analyze(documentText: String): Map<Int, LineSchedule> {
        val rawLines = documentText.lines()
        val cleanLines = AlphaInstructionTable.stripComments(documentText)
        val result = mutableMapOf<Int, LineSchedule>()

        val condTracker = GasConditionalTracker()
        val mafTracker = MafTracker()
        val wbufTracker = WriteBufferTracker()
        val icacheTracker = ICacheTracker()

        data class ActiveLine(
            val lineIdx: Int,
            val pc: Long,
            val hasLabelAbove: Boolean,
            val inst: DecodedInstruction,
            val isICacheMiss: Boolean
        )

        val activeInstructions = mutableListOf<ActiveLine>()
        var currentPc = 0x10000L
        var pendingLabel = false

        // Pass 1: Filter comments/conditionals, handle .align, and decode active instructions
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

            if (cleanLine.startsWith(".")) continue

            val codePart = if (cleanLine.contains(":")) {
                pendingLabel = true
                cleanLine.substringAfterLast(":").trim()
            } else {
                cleanLine
            }

            if (codePart.isEmpty()) continue

            val rawLine = rawLines.getOrElse(lineIdx) { "" }
            val inst = AlphaInstructionTable.decodeClean(lineIdx, codePart, rawLine) ?: continue
            val isICacheMiss = rawLine.contains("icache_miss", ignoreCase = true)

            activeInstructions.add(
                ActiveLine(
                    lineIdx = lineIdx,
                    pc = currentPc,
                    hasLabelAbove = pendingLabel,
                    inst = inst,
                    isICacheMiss = isICacheMiss
                )
            )

            pendingLabel = false
            currentPc += 4L
        }

        // Pass 2: Cycle-accurate EV56 quad-issue scheduling
        val regReadyCycle = mutableMapOf<String, Int>()
        var currentCycle = 0
        val usedSlotsInCurrentCycle = mutableSetOf<PipeSlot>()
        var fmUnitFreeCycle = 0
        var e0MulUnitFreeCycle = 0

        for (i in activeInstructions.indices) {
            val item = activeInstructions[i]
            val inst = item.inst
            val nextInst = activeInstructions.getOrNull(i + 1)?.inst

            // Advance background WBUF drain respecting active Load MAF priority
            wbufTracker.advanceCycle(currentCycle, mafTracker.activeLoadCount(currentCycle))

            // 1. Check I-Cache / IDU fetch event
            val fetchEvent = icacheTracker.fetchInstruction(
                pc = item.pc,
                currentCycle = currentCycle,
                hasLabelAbove = item.hasLabelAbove,
                isMarkedICacheMiss = item.isICacheMiss
            )

            var stallCycles = 0
            if (fetchEvent.icacheMissStall > 0) {
                stallCycles += fetchEvent.icacheMissStall
                currentCycle += fetchEvent.icacheMissStall
                usedSlotsInCurrentCycle.clear()
            }

            // 2. Check RAW register dependencies
            val earliestDataCycle = inst.srcRegs.maxOfOrNull { regReadyCycle[it] ?: 0 } ?: 0
            if (earliestDataCycle > currentCycle) {
                // Advancing from currentCycle -> currentCycle + 1 when slots were already used
                // is normal pipeline progression (0 stall cycles), NOT a red stall.
                val naturalNextCycle = if (usedSlotsInCurrentCycle.isNotEmpty()) currentCycle + 1 else currentCycle
                if (earliestDataCycle > naturalNextCycle) {
                    stallCycles += (earliestDataCycle - naturalNextCycle)
                }
                currentCycle = earliestDataCycle
                usedSlotsInCurrentCycle.clear()
            }

            // 3. Check non-pipelined functional unit occupancy (FP divide & Integer multiply)
            if (inst.type == InstType.FP_DIV || inst.type == InstType.FP_MUL) {
                if (fmUnitFreeCycle > currentCycle) {
                    val naturalNext = if (usedSlotsInCurrentCycle.isNotEmpty()) currentCycle + 1 else currentCycle
                    if (fmUnitFreeCycle > naturalNext) {
                        stallCycles += (fmUnitFreeCycle - naturalNext)
                    }
                    currentCycle = fmUnitFreeCycle
                    usedSlotsInCurrentCycle.clear()
                }
            }
            if (inst.type == InstType.IMUL) {
                if (e0MulUnitFreeCycle > currentCycle) {
                    val naturalNext = if (usedSlotsInCurrentCycle.isNotEmpty()) currentCycle + 1 else currentCycle
                    if (e0MulUnitFreeCycle > naturalNext) {
                        stallCycles += (e0MulUnitFreeCycle - naturalNext)
                    }
                    currentCycle = e0MulUnitFreeCycle
                    usedSlotsInCurrentCycle.clear()
                }
            }

            // 4. Check memory subsystem hazards (WBUF / Load MAF / Barriers)
            var effectiveLatency = inst.latency
            when (inst.type) {
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
                    }
                }
                InstType.BARRIER -> {
                    val barrierStall = wbufTracker.flushBarrier(currentCycle)
                    if (barrierStall > 0) {
                        stallCycles += barrierStall
                        currentCycle += barrierStall
                        usedSlotsInCurrentCycle.clear()
                    }
                }
                InstType.LOAD -> {
                    val storeConflictStall = wbufTracker.checkLoadConflict(
                        currentCycle = currentCycle,
                        baseReg = inst.baseReg ?: "$31",
                        offset = inst.memOffset
                    )
                    if (storeConflictStall > 0) {
                        stallCycles += storeConflictStall
                        currentCycle += storeConflictStall
                        usedSlotsInCurrentCycle.clear()
                    }

                    val isPrefetch = AlphaRegisterMap.isZeroRegister(inst.destReg)
                    val (mafStall, memReadyCycle) = mafTracker.accessMemory(
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
                    }
                    effectiveLatency = maxOf(1, memReadyCycle - currentCycle)
                }
                InstType.PREFETCH -> {
                    mafTracker.accessMemory(
                        currentCycle = currentCycle,
                        baseReg = inst.baseReg ?: "$31",
                        offset = inst.memOffset,
                        isPrefetchOrZeroDest = true,
                        assumeL1Miss = false
                    )
                }
                else -> {}
            }

            // 5. General EV56 slot selection:
            // Yield E0 to E1 when the next independent instruction strictly requires E0.
            val nextHasRawOnCurrent = inst.destReg != null &&
                !AlphaRegisterMap.isZeroRegister(inst.destReg) &&
                inst.destReg in (nextInst?.srcRegs ?: emptyList())

            val nextCanPairOnE0 = nextInst != null &&
                !nextHasRawOnCurrent &&
                nextInst.allowedSlots == setOf(PipeSlot.E0)

            val preferredOrder = if (nextCanPairOnE0 && inst.allowedSlots.containsAll(setOf(PipeSlot.E0, PipeSlot.E1))) {
                listOf(PipeSlot.E1, PipeSlot.E0, PipeSlot.FA, PipeSlot.FM)
            } else {
                listOf(PipeSlot.E0, PipeSlot.E1, PipeSlot.FA, PipeSlot.FM)
            }

            var chosenSlot = preferredOrder.firstOrNull { it in inst.allowedSlots && it !in usedSlotsInCurrentCycle }
            if (chosenSlot == null) {
                currentCycle++
                usedSlotsInCurrentCycle.clear()
                chosenSlot = preferredOrder.first { it in inst.allowedSlots }
            }

            usedSlotsInCurrentCycle.add(chosenSlot)

            // Update non-pipelined unit timers
            if ((inst.type == InstType.FP_DIV || inst.type == InstType.FP_MUL) && inst.nonPipelinedBusyCycles > 0) {
                fmUnitFreeCycle = currentCycle + inst.nonPipelinedBusyCycles
            }
            if (inst.type == InstType.IMUL && inst.nonPipelinedBusyCycles > 0) {
                e0MulUnitFreeCycle = currentCycle + inst.nonPipelinedBusyCycles
            }

            // 6. Update destination register readiness (ignoring hardwired zero registers $31 and $f31)
            if (inst.destReg != null && !AlphaRegisterMap.isZeroRegister(inst.destReg)) {
                regReadyCycle[inst.destReg] = currentCycle + effectiveLatency
            }

            result[item.lineIdx] = LineSchedule(
                lineNumber = item.lineIdx,
                isInstruction = true,
                cycle = currentCycle,
                assignedSlot = chosenSlot,
                stallCyclesBefore = stallCycles,
                branchPenalty = inst.branchPenalty,
                fetchEvent = fetchEvent,
                activeSlotsThisCycle = usedSlotsInCurrentCycle.toSet()
            )
        }

        return result
    }
}
