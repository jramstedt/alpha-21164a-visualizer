package dec.alpha.ev56

enum class PipeSlot { E0, E1, FA, FM }

enum class InstType {
    ALU, SHIFT_BWX, MVI, IMUL, LOAD, STORE,
    IBRANCH, UBRANCH, IJUMP,
    FP_ADD, FP_MUL, FP_DIV, FP_BRANCH,
    BARRIER, PREFETCH, NOP, PAL
}

data class DecodedInstruction(
    val line: Int,
    val rawOpcode: String,
    val baseOpcode: String,
    val type: InstType,
    val destReg: String?,
    val srcRegs: List<String>,
    val allowedSlots: Set<PipeSlot>,
    val latency: Int,
    val nonPipelinedBusyCycles: Int = 0,
    val branchPenalty: Int? = null,
    val memOffset: Int = 0,
    val baseReg: String? = null,
    val isMissHint: Boolean = false
)

object AlphaInstructionTable {

    private val memRegex = Regex("""(-?\d+|0x[0-9a-fA-F]+)?\(\s*(\$\w+)\s*\)""")

    private val intAluOps = setOf(
        "addl", "addq", "s4addl", "s8addl", "s4addq", "s8addq",
        "subl", "subq", "s4subl", "s8subl", "s4subq", "s8subq",
        "and", "bic", "bis", "eqv", "ornot", "xor",
        "cmpeq", "cmplt", "cmple", "cmpult", "cmpule", "cmpbge",
        "lda", "ldah", "rpcc", "rc", "rs"
    )

    private val intCmovOps = setOf(
        "cmoveq", "cmovne", "cmovlt", "cmovle", "cmovgt", "cmovge", "cmovlbs", "cmovlbc"
    )

    private val shiftAndBwxOps = setOf(
        "sll", "srl", "sra",
        "extbl", "extwl", "extll", "extql", "extwh", "extlh", "extqh",
        "insbl", "inswl", "insll", "insql", "inswh", "inslh", "insqh",
        "mskbl", "mskwl", "mskll", "mskql", "mskwh", "msklh", "mskqh",
        "zap", "zapnot", "sextb", "sextw"
    )

    private val mviOps = setOf(
        "minub8", "minsb8", "minuw4", "minsw4",
        "maxub8", "maxsb8", "maxuw4", "maxsw4",
        "perr", "pklb", "pkwb", "unpkbl", "unpkbw"
    )

    private val intLoads = setOf("ldbu", "ldwu", "ldl", "ldq", "ldq_u", "ldl_l", "ldq_l")
    private val fpLoads = setOf("ldf", "ldg", "lds", "ldt")
    private val intStores = setOf("stb", "stw", "stl", "stq", "stq_u", "stl_c", "stq_c")
    private val fpStores = setOf("stf", "stg", "sts", "stt")

    private val condIntBranches = setOf("beq", "bne", "blt", "ble", "bgt", "bge", "blbs", "blbc")
    private val uncondBranches = setOf("br", "bsr")
    private val indirectJumps = setOf("jmp", "jsr", "ret", "jsr_coroutine")
    private val fpBranches = setOf("fbeq", "fbne", "fblt", "fble", "fbgt", "fbge")

    private val fpAddOps = setOf(
        "adds", "addt", "addf", "addg",
        "subs", "subt", "subf", "subg",
        "cmpteq", "cmptlt", "cmptle", "cmptun", "cmpgeq", "cmpglt", "cmpgle",
        "cvtts", "cvtst", "cvttq", "cvtqt", "cvtqs", "cvtqf", "cvtfq", "cvtdg", "cvtgd", "cvtgf",
        "cpys", "cpysn", "cpyse", "mt_fpcr", "mf_fpcr"
    )

    private val fpCmovOps = setOf("fcmoveq", "fcmovne", "fcmovlt", "fcmovle", "fcmovgt", "fcmovge")
    private val fpMulOps = setOf("muls", "mult", "mulf", "mulg")
    private val fpDivSingleOps = setOf("divs", "divf")
    private val fpDivDoubleOps = setOf("divt", "divg")
    private val intFpXferOps = setOf("itofs", "itoff", "itoft", "ftois", "ftoit")

    /**
     * Strips C block comments, C++ line comments, and GNU GAS '#' comments
     * while preserving line count and leading '#' C preprocessor directives.
     */
    fun stripComments(documentText: String): List<String> {
        val withoutBlockComments = Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL)
            .replace(documentText) { match ->
                match.value.replace(Regex("[^\n]"), " ")
            }

        return withoutBlockComments.lines().map { rawLine ->
            val trimmed = rawLine.trim()
            if (trimmed.startsWith("#")) {
                trimmed.substringBefore("//").trim()
            } else {
                trimmed.substringBefore("#")
                    .substringBefore("//")
                    .trim()
            }
        }
    }

    fun decodeClean(lineNum: Int, cleanCode: String, rawLine: String): DecodedInstruction? {
        if (cleanCode.isEmpty() || cleanCode.startsWith(".")) return null

        val isMissHint = rawLine.contains("miss", ignoreCase = true) && !rawLine.contains("icache_miss", ignoreCase = true)
        val tokens = cleanCode.split(Regex("[\\s,()]+")).filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return null

        val rawOp = tokens.first().lowercase()
        val baseOp = rawOp.substringBefore("/")
        val regs = tokens.drop(1).filter { it.startsWith("$") }
        val memInfo = parseMemoryOperand(cleanCode)

        return when {
            // --- Hardware NOPs (including EV56 fetch / fetch_m) & GAS Pseudo-Instructions ---
            baseOp in setOf("nop", "fetch", "fetch_m") -> DecodedInstruction(
                lineNum, rawOp, baseOp, InstType.NOP, null, emptyList(), setOf(PipeSlot.E0, PipeSlot.E1), 1
            )
            baseOp == "fnop" -> DecodedInstruction(
                lineNum, rawOp, "cpys", InstType.NOP, null, emptyList(), setOf(PipeSlot.FA), 1
            )
            baseOp == "unop" -> DecodedInstruction(
                lineNum, rawOp, "ldq_u", InstType.NOP, null, emptyList(), setOf(PipeSlot.E0), 1
            )
            baseOp in setOf("mov", "negq", "negl", "not") -> DecodedInstruction(
                lineNum, rawOp, baseOp, InstType.ALU, regs.lastOrNull(), regs.dropLast(1), setOf(PipeSlot.E0, PipeSlot.E1), 1
            )
            baseOp == "clr" -> DecodedInstruction(
                lineNum, rawOp, "bis", InstType.ALU, regs.lastOrNull(), emptyList(), setOf(PipeSlot.E0, PipeSlot.E1), 1
            )
            baseOp in setOf("fmov", "fneg", "fabs") -> DecodedInstruction(
                lineNum, rawOp, "cpys", InstType.FP_ADD, regs.lastOrNull(), regs.dropLast(1), setOf(PipeSlot.FA), 4
            )
            baseOp == "fclr" -> DecodedInstruction(
                lineNum, rawOp, "cpys", InstType.FP_ADD, regs.lastOrNull(), emptyList(), setOf(PipeSlot.FA), 4
            )

            // --- Integer ALU & CMOV ---
            baseOp in intAluOps -> DecodedInstruction(
                lineNum, rawOp, baseOp, InstType.ALU, regs.lastOrNull(), regs.dropLast(1),
                setOf(PipeSlot.E0, PipeSlot.E1), 1, memOffset = memInfo?.first ?: 0, baseReg = memInfo?.second
            )
            baseOp in intCmovOps -> DecodedInstruction(
                lineNum, rawOp, baseOp, InstType.ALU, regs.lastOrNull(), regs, setOf(PipeSlot.E0, PipeSlot.E1), 1
            )

            // --- Shifts, BWX, and MVI (E0 Only) ---
            baseOp in shiftAndBwxOps -> DecodedInstruction(
                lineNum, rawOp, baseOp, InstType.SHIFT_BWX, regs.lastOrNull(), regs.dropLast(1), setOf(PipeSlot.E0), 1
            )
            baseOp in mviOps -> DecodedInstruction(
                lineNum, rawOp, baseOp, InstType.MVI, regs.lastOrNull(), regs.dropLast(1), setOf(PipeSlot.E0), 1
            )

            // --- Integer Multiply (E0 Only, 8 / 12 / 14 cycle latency) ---
            baseOp == "mull" -> DecodedInstruction(
                lineNum, rawOp, baseOp, InstType.IMUL, regs.lastOrNull(), regs.dropLast(1), setOf(PipeSlot.E0), 8, nonPipelinedBusyCycles = 4
            )
            baseOp == "mulq" -> DecodedInstruction(
                lineNum, rawOp, baseOp, InstType.IMUL, regs.lastOrNull(), regs.dropLast(1), setOf(PipeSlot.E0), 12, nonPipelinedBusyCycles = 8
            )
            baseOp == "umulh" -> DecodedInstruction(
                lineNum, rawOp, baseOp, InstType.IMUL, regs.lastOrNull(), regs.dropLast(1), setOf(PipeSlot.E0), 14, nonPipelinedBusyCycles = 10
            )

            // --- Loads (Integer & FP, E0 Only; destination $31/$f31 acts as EV56 prefetch) ---
            baseOp in intLoads || baseOp in fpLoads -> DecodedInstruction(
                lineNum, rawOp, baseOp, InstType.LOAD, regs.firstOrNull(), listOfNotNull(memInfo?.second),
                setOf(PipeSlot.E0), 2, memOffset = memInfo?.first ?: 0, baseReg = memInfo?.second, isMissHint = isMissHint
            )

            // --- Stores (Integer & FP, E0 Only) ---
            baseOp in intStores || baseOp in fpStores -> DecodedInstruction(
                lineNum, rawOp, baseOp, InstType.STORE, null, regs,
                setOf(PipeSlot.E0), 1, memOffset = memInfo?.first ?: 0, baseReg = memInfo?.second
            )

            // --- Int <-> FP Register Transfers (FIX) ---
            baseOp in intFpXferOps -> DecodedInstruction(
                lineNum, rawOp, baseOp, InstType.ALU, regs.lastOrNull(), regs.dropLast(1), setOf(PipeSlot.E0), 4
            )

            // --- Branches & Jumps ---
            baseOp in condIntBranches -> DecodedInstruction(
                lineNum, rawOp, baseOp, InstType.IBRANCH, null, regs, setOf(PipeSlot.E0, PipeSlot.E1), 1, branchPenalty = 5
            )
            baseOp in uncondBranches -> DecodedInstruction(
                lineNum, rawOp, baseOp, InstType.UBRANCH, regs.firstOrNull(), emptyList(), setOf(PipeSlot.E0, PipeSlot.E1), 1, branchPenalty = 1
            )
            baseOp in indirectJumps -> DecodedInstruction(
                lineNum, rawOp, baseOp, InstType.IJUMP, regs.firstOrNull(), regs.drop(1), setOf(PipeSlot.E0), 1, branchPenalty = 6
            )
            baseOp in fpBranches -> DecodedInstruction(
                lineNum, rawOp, baseOp, InstType.FP_BRANCH, null, regs, setOf(PipeSlot.FA), 1, branchPenalty = 5
            )

            // --- Floating-Point Add / Cmp / Cvt / CMOV (FA Only, 4-cycle latency) ---
            baseOp in fpAddOps -> DecodedInstruction(
                lineNum, rawOp, baseOp, InstType.FP_ADD, regs.lastOrNull(), regs.dropLast(1), setOf(PipeSlot.FA), 4
            )
            baseOp in fpCmovOps -> DecodedInstruction(
                lineNum, rawOp, baseOp, InstType.FP_ADD, regs.lastOrNull(), regs, setOf(PipeSlot.FA), 4
            )

            // --- Floating-Point Multiply (FM Only, 4-cycle pipelined) ---
            baseOp in fpMulOps -> DecodedInstruction(
                lineNum, rawOp, baseOp, InstType.FP_MUL, regs.lastOrNull(), regs.dropLast(1), setOf(PipeSlot.FM), 4
            )

            // --- Floating-Point Divide (FM Only, Non-Pipelined: 17c single / 30c double) ---
            baseOp in fpDivSingleOps -> DecodedInstruction(
                lineNum, rawOp, baseOp, InstType.FP_DIV, regs.lastOrNull(), regs.dropLast(1), setOf(PipeSlot.FM), 17, nonPipelinedBusyCycles = 14
            )
            baseOp in fpDivDoubleOps -> DecodedInstruction(
                lineNum, rawOp, baseOp, InstType.FP_DIV, regs.lastOrNull(), regs.dropLast(1), setOf(PipeSlot.FM), 30, nonPipelinedBusyCycles = 27
            )

            // --- Cache Block Hints (ecb / wh64) ---
            baseOp in setOf("ecb", "wh64") -> DecodedInstruction(
                lineNum, rawOp, baseOp, InstType.PREFETCH, null, listOfNotNull(memInfo?.second),
                setOf(PipeSlot.E0), 1, memOffset = memInfo?.first ?: 0, baseReg = memInfo?.second
            )

            // --- Barriers & PALcode ---
            baseOp in setOf("mb", "wmb", "trapb", "excb") -> DecodedInstruction(
                lineNum, rawOp, baseOp, InstType.BARRIER, null, emptyList(), setOf(PipeSlot.E0), 1
            )
            baseOp == "call_pal" -> DecodedInstruction(
                lineNum, rawOp, baseOp, InstType.PAL, null, emptyList(), setOf(PipeSlot.E0), 10
            )

            // Fallback for unknown macros/instructions
            else -> DecodedInstruction(
                lineNum, rawOp, baseOp, InstType.ALU, regs.lastOrNull(), regs.dropLast(1), setOf(PipeSlot.E0), 1
            )
        }
    }

    private fun parseMemoryOperand(line: String): Pair<Int, String>? {
        val m = memRegex.find(line) ?: return null
        val offStr = m.groupValues[1]
        val offset = when {
            offStr.isEmpty() -> 0
            offStr.startsWith("0x", true) -> offStr.substring(2).toIntOrNull(16) ?: 0
            else -> offStr.toIntOrNull() ?: 0
        }
        return Pair(offset, m.groupValues[2])
    }
}
