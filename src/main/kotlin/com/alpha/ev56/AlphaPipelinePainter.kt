package dec.alpha.ev56

import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.LogicalPosition
import com.intellij.openapi.editor.TextAnnotationGutterProvider
import com.intellij.openapi.editor.colors.ColorKey
import com.intellij.openapi.editor.colors.EditorFontType
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.editor.event.EditorFactoryEvent
import com.intellij.openapi.editor.event.EditorFactoryListener
import com.intellij.openapi.editor.ex.EditorGutterComponentEx
import com.intellij.openapi.editor.ex.util.EditorUtil
import com.intellij.openapi.util.Disposer
import com.intellij.ui.JBColor
import java.awt.*
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import javax.swing.JComponent

class AlphaEditorListener : EditorFactoryListener {
    override fun editorCreated(event: EditorFactoryEvent) {
        val editor = event.editor
        val file = editor.virtualFile ?: return
        val ext = file.extension?.lowercase() ?: return
        if (ext !in setOf("s", "asm", "inc")) return

        val gutterEx = editor.gutter as? EditorGutterComponentEx ?: return
        val simulator = Alpha21164ASimulator()
        var scheduleMap = simulator.analyze(editor.document.text)

        val editorDisposable: Disposable = Disposer.newDisposable("AlphaPipelineVisualizer")
        EditorUtil.disposeWithEditor(editor, editorDisposable)

        val visualizer = AlphaGutterVisualizer(editor) { scheduleMap }
        editor.gutter.registerTextAnnotation(visualizer)

        val overlay = object : JComponent() {
            override fun paintComponent(g: Graphics) {
                super.paintComponent(g)
                val g2 = g as Graphics2D

                val annotationX = gutterEx.annotationsAreaOffset
                val annotationWidth = gutterEx.annotationsAreaWidth
                if (annotationX < 0 || annotationWidth <= 0) return

                // Strictly clip painting inside the reserved gutter annotation column
                val oldClip = g2.clip
                g2.clipRect(annotationX, 0, annotationWidth, height)

                val lineHeight = editor.lineHeight
                val visibleArea = editor.scrollingModel.visibleArea
                val startLine = editor.xyToLogicalPosition(Point(0, visibleArea.y)).line
                val endLine = editor.xyToLogicalPosition(Point(0, visibleArea.y + visibleArea.height)).line

                // Leave 22px on the left of annotationX for "IF-8c" labels
                val tracksOriginX = annotationX + 22

                for (line in startLine..endLine) {
                    val yTop = editor.logicalPositionToXY(LogicalPosition(line, 0)).y
                    visualizer.paintTracks(g2, line, xStart = tracksOriginX, yTop = yTop, lineHeight = lineHeight)
                }

                g2.clip = oldClip
            }
        }

        overlay.isOpaque = false
        gutterEx.add(overlay)

        fun syncOverlayBounds() {
            overlay.setBounds(0, 0, gutterEx.width, maxOf(gutterEx.height, editor.contentComponent.height))
            overlay.repaint()
        }
        syncOverlayBounds()

        editor.document.addDocumentListener(object : DocumentListener {
            override fun documentChanged(event: DocumentEvent) {
                scheduleMap = simulator.analyze(editor.document.text)
                gutterEx.revalidateMarkup()
                syncOverlayBounds()
            }
        }, editorDisposable)

        gutterEx.addComponentListener(object : ComponentAdapter() {
            override fun componentResized(e: ComponentEvent) = syncOverlayBounds()
        })
        editor.scrollingModel.addVisibleAreaListener({ syncOverlayBounds() }, editorDisposable)
    }
}

class AlphaGutterVisualizer(
    private val editor: Editor,
    private val scheduleProvider: () -> Map<Int, LineSchedule>
) : TextAnnotationGutterProvider {

    private val slots = listOf(PipeSlot.E0, PipeSlot.E1, PipeSlot.FA, PipeSlot.FM)
    private val trackWidth = 14

    // Reserve ~115px in the gutter annotation column using figure spaces
    override fun getLineText(line: Int, editor: Editor): String = "\u2007".repeat(15)

    override fun getToolTip(line: Int, editor: Editor): String? {
        val info = scheduleProvider()[line] ?: return null
        if (!info.isInstruction) return null
        return buildString {
            append("<html>")
            append("<b>Cycle:</b> ${info.cycle} &nbsp;|&nbsp; ")
            append("<b>PC:</b> 0x${info.fetchEvent?.pc?.toString(16) ?: "?"} &nbsp;|&nbsp; ")
            append("<b>Slot:</b> ${info.assignedSlot}")

            if (info.stallCyclesBefore > 0) {
                val reasonText = if (info.stallReasons.isNotEmpty()) {
                    info.stallReasons.joinToString(", ")
                } else {
                    "Pipeline stall"
                }
                append("<br><font color='#ff6b6b'><b>Stalled:</b> ${info.stallCyclesBefore}c — $reasonText</font>")
            }

            if (info.fetchEvent?.isUnalignedTarget == true) {
                append("<br><font color='#ffa94d'><b>I-Fetch:</b> Unaligned branch target (not 16B aligned)</font>")
            }

            if (info.branchPenalty != null) {
                append("<br><font color='#ffa94d'><b>Branch Mispredict Penalty:</b> ${info.branchPenalty}c</font>")
            }
            append("</html>")
        }
    }

    override fun getStyle(line: Int, editor: Editor): EditorFontType = EditorFontType.PLAIN
    override fun getColor(line: Int, editor: Editor): ColorKey? = null
    override fun getBgColor(line: Int, editor: Editor): Color? = null
    override fun getPopupActions(line: Int, editor: Editor): List<AnAction> = emptyList()
    override fun gutterClosed() {}

    fun paintTracks(g: Graphics2D, line: Int, xStart: Int, yTop: Int, lineHeight: Int) {
        val scheduleMap = scheduleProvider()
        val schedule = scheduleMap[line]
        // Draw tracks strictly on lines containing executable instructions
        if (schedule == null || !schedule.isInstruction) return

        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

        // 1. Draw IF (I-Cache Fetch) track
        paintIFetchTrack(g, schedule.fetchEvent, xStart, yTop, lineHeight)

        val slotsStartX = xStart + 14

        // 2. Draw a green grouping bracket when consecutive lines dual/quad-issue in the same cycle
        val prevSchedule = (line - 1 downTo 0).firstNotNullOfOrNull { idx ->
            scheduleMap[idx]?.takeIf { it.isInstruction }
        }
        if (prevSchedule != null && prevSchedule.cycle == schedule.cycle) {
            g.color = JBColor(Color(46, 160, 67, 180), Color(63, 185, 80, 180))
            g.stroke = BasicStroke(2.0f)
            g.drawLine(slotsStartX - 3, yTop - (lineHeight / 2), slotsStartX - 3, yTop + (lineHeight / 2))
        }

        // 3. Draw E0, E1, FA, FM execution slot tracks
        slots.forEachIndexed { idx, slot ->
            val trackX = slotsStartX + (idx * trackWidth)
            val centerX = trackX + (trackWidth / 2)

            val color = when {
                schedule.assignedSlot == slot && schedule.stallCyclesBefore > 0 -> JBColor.RED
                schedule.assignedSlot == slot -> JBColor(Color(46, 160, 67), Color(63, 185, 80))
                else -> JBColor.GRAY
            }

            g.color = color
            g.stroke = BasicStroke(3.0f)
            g.drawLine(centerX, yTop, centerX, yTop + lineHeight)

            // Draw dashed red stall connector and cycle count
            if (schedule.assignedSlot == slot && schedule.stallCyclesBefore > 0) {
                g.color = JBColor.RED
                g.stroke = BasicStroke(2.0f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_BEVEL, 0f, floatArrayOf(3f), 0f)
                g.drawLine(centerX - 4, yTop - 4, centerX - 4, yTop + (lineHeight / 2))

                g.font = Font("Monospaced", Font.BOLD, 9)
                g.drawString("-${schedule.stallCyclesBefore}c", trackX - 2, yTop + 10)
            }
        }

        // 4. Draw branch misprediction penalty
        if (schedule.branchPenalty != null) {
            val branchX = slotsStartX + (slots.size * trackWidth) + 2
            g.color = JBColor.ORANGE
            g.font = Font("Monospaced", Font.BOLD, 10)
            g.drawString("B:${schedule.branchPenalty}", branchX, yTop + lineHeight - 4)
        }
    }

    private fun paintIFetchTrack(g: Graphics2D, fetch: IFetchEvent?, xLeft: Int, yTop: Int, lineHeight: Int) {
        if (fetch == null) return

        // Solid blue divider for 32-byte (8-instruction) L1 I-cache line boundary
        if (fetch.is32ByteLineBoundary) {
            g.color = JBColor(Color(0, 120, 215), Color(75, 165, 255))
            g.stroke = BasicStroke(1.5f)
            g.drawLine(xLeft, yTop, xLeft + 70, yTop)
        }
        // Dashed gray tick for 16-byte (4-instruction) IDU octaword fetch boundary
        else if (fetch.is16ByteFetchBoundary) {
            g.color = JBColor(Color(150, 150, 150, 140), Color(110, 110, 110, 140))
            g.stroke = BasicStroke(1.0f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_BEVEL, 0f, floatArrayOf(2f), 0f)
            g.drawLine(xLeft, yTop, xLeft + 70, yTop)
        }

        val barX = xLeft + 4
        g.color = when {
            fetch.icacheMissStall > 0 -> JBColor.RED
            fetch.isUnalignedTarget -> JBColor.ORANGE
            else -> JBColor(Color(0, 120, 215), Color(75, 165, 255))
        }
        g.stroke = BasicStroke(2.5f)
        g.drawLine(barX, yTop + 1, barX, yTop + lineHeight - 1)

        if (fetch.icacheMissStall > 0) {
            g.font = Font("Monospaced", Font.BOLD, 9)
            g.drawString("IF-${fetch.icacheMissStall}c", xLeft - 22, yTop + 10)
        }
    }
}
