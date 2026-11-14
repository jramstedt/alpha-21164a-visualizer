package dec.alpha.ev56

class GasConditionalTracker {
    private data class CondFrame(
        val parentActive: Boolean,
        var currentBranchActive: Boolean,
        var anyBranchTaken: Boolean
    )

    private val stack = ArrayDeque<CondFrame>()
    private val symbols = mutableMapOf<String, Long>()

    fun isActive(): Boolean = stack.lastOrNull()?.currentBranchActive ?: true

    fun processDirective(cleanLine: String): Boolean {
        if (!cleanLine.startsWith(".") && !cleanLine.startsWith("#")) return false

        val normalized = if (cleanLine.startsWith("#")) {
            "." + cleanLine.removePrefix("#").trim()
        } else {
            cleanLine
        }

        val tokens = normalized.split(Regex("[\\s,()]+")).filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return false
        val dir = tokens.first().lowercase()

        when (dir) {
            ".set", ".equ", ".define" -> {
                if (isActive() && tokens.size >= 2) {
                    val name = tokens[1]
                    val value = if (tokens.size >= 3) {
                        evalExpr(tokens.drop(2).joinToString(" ")) ?: 1L
                    } else {
                        1L
                    }
                    symbols[name] = value
                }
                return true
            }
            ".undef" -> {
                if (isActive() && tokens.size >= 2) {
                    symbols.remove(tokens[1])
                }
                return true
            }
            ".if", ".ifdef", ".ifndef", ".ifeq", ".ifne", ".ifgt", ".ifge", ".iflt", ".ifle" -> {
                val parentActive = isActive()
                val expr = normalized.substringAfter(tokens.first()).trim()
                val condResult = if (!parentActive) false else evaluateCondition(dir, expr)
                stack.addLast(CondFrame(parentActive, condResult, condResult))
                return true
            }
            ".elif", ".elseif" -> {
                val top = stack.lastOrNull() ?: return true
                if (!top.parentActive || top.anyBranchTaken) {
                    top.currentBranchActive = false
                } else {
                    val expr = normalized.substringAfter(tokens.first()).trim()
                    val condResult = evaluateCondition(".if", expr)
                    top.currentBranchActive = condResult
                    if (condResult) top.anyBranchTaken = true
                }
                return true
            }
            ".else" -> {
                val top = stack.lastOrNull() ?: return true
                if (!top.parentActive || top.anyBranchTaken) {
                    top.currentBranchActive = false
                } else {
                    top.currentBranchActive = true
                    top.anyBranchTaken = true
                }
                return true
            }
            ".endif" -> {
                if (stack.isNotEmpty()) stack.removeLast()
                return true
            }
            ".include", ".pragma", ".line" -> return true
        }
        return false
    }

    private fun evaluateCondition(dir: String, expr: String): Boolean {
        val definedMatch = Regex("""^(!)?\s*defined\s*\(?\s*(\w+)\s*\)?$""").find(expr)
        if (definedMatch != null) {
            val negated = definedMatch.groupValues[1] == "!"
            val sym = definedMatch.groupValues[2]
            val exists = symbols.containsKey(sym)
            return if (negated) !exists else exists
        }

        return when (dir) {
            ".ifdef" -> symbols.containsKey(expr)
            ".ifndef" -> !symbols.containsKey(expr)
            ".ifeq" -> (evalExpr(expr) ?: 1L) == 0L
            ".ifne" -> (evalExpr(expr) ?: 0L) != 0L
            ".ifgt" -> (evalExpr(expr) ?: 0L) > 0L
            ".ifge" -> (evalExpr(expr) ?: -1L) >= 0L
            ".iflt" -> (evalExpr(expr) ?: 0L) < 0L
            ".ifle" -> (evalExpr(expr) ?: 1L) <= 0L
            else -> {
                val cmpMatch = Regex("""^(.+?)\s*(==|!=|>=|<=|>|<)\s*(.+)$""").find(expr)
                if (cmpMatch != null) {
                    val lhs = evalExpr(cmpMatch.groupValues[1]) ?: return true
                    val op = cmpMatch.groupValues[2]
                    val rhs = evalExpr(cmpMatch.groupValues[3]) ?: return true
                    when (op) {
                        "==" -> lhs == rhs
                        "!=" -> lhs != rhs
                        ">" -> lhs > rhs
                        ">=" -> lhs >= rhs
                        "<" -> lhs < rhs
                        "<=" -> lhs <= rhs
                        else -> true
                    }
                } else {
                    val value = evalExpr(expr)
                    value == null || value != 0L
                }
            }
        }
    }

    private fun evalExpr(raw: String): Long? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        symbols[trimmed]?.let { return it }
        return if (trimmed.startsWith("0x", ignoreCase = true)) {
            trimmed.substring(2).toLongOrNull(16)
        } else {
            trimmed.toLongOrNull()
        }
    }
}
