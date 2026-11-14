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

        val simulator = Alpha21164ASimulator()
        var scheduleMap = simulator.analyze(editor.document.text)

        val visualizer = AlphaGutterVisualizer(editor) { scheduleMap }
        editor.gutter.registerTextAnnotation(visualizer)

        val gutterEx = editor.gutter as? EditorGutterComponentEx ?: return

        val editorDisposable: Disposable = Disposer.newDisposable("AlphaPipelineVisualizer")
        EditorUtil.disposeWithEditor(editor, editorDisposable)

        val overlay = object : JComponent() {
            override fun paintComponent(g: Graphics) {
                super.paintComponent(g)
                val g2 = g as Graphics2D

                val annotationX = gutterEx.annotationsAreaOffset
                val annotationWidth = gutterEx.annotationsAreaWidth
                if (annotationX < 0 || annotationWidth <= 0) return

                val oldClip = g2.clip
                g2.clipRect(annotationX, 0, annotationWidth, height)

                val lineHeight = editor.lineHeight
                val visibleArea = editor.scrollingModel.visibleArea
                val startLine = editor.xyToLogicalPosition(Point(0, visibleArea.y)).line
                val endLine = editor.xyToLogicalPosition(Point(0, visibleArea.y + visibleArea.height)).line

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

    override fun getLineText(line: Int, editor: Editor): String = "\u2007".repeat(15)

    override fun getToolTip(line: Int, editor: Editor): String? {
        val info = scheduleProvider()[line] ?: return null
        if (!info.isInstruction) return null
        return buildString {
            append("Slot: ${info.assignedSlot}")
            if (info.stallCyclesBefore > 0) {
                append(" | Stalled: ${info.stallCyclesBefore}c")
                if (info.stallReason != null) append(" (${info.stallReason})")
            }
            if (info.branchPenalty != null) {
                append(" | Branch Mispredict: ${info.branchPenalty}c")
            }
            info.fetchEvent?.let {
                append(" | PC: 0x${it.pc.toString(16)}")
                if (it.isUnalignedTarget) append(" [Unaligned Target]")
            }
        }
    }

    override fun getStyle(line: Int, editor: Editor): EditorFontType = EditorFontType.PLAIN
    override fun getColor(line: Int, editor: Editor): ColorKey? = null
    override fun getBgColor(line: Int, editor: Editor): Color? = null
    override fun getPopupActions(line: Int, editor: Editor): List<AnAction> = emptyList()
    override fun gutterClosed() {}

    fun paintTracks(g: Graphics2D, line: Int, xStart: Int, yTop: Int, lineHeight: Int) {
        val schedule = scheduleProvider()[line]
        if (schedule == null || !schedule.isInstruction) return

        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

        paintIFetchTrack(g, schedule.fetchEvent, xStart, yTop, lineHeight)

        val slotsStartX = xStart + trackWidth
        slots.forEachIndexed { idx, slot ->
            val trackX = slotsStartX + (idx * trackWidth)

            val color = when {
                schedule.assignedSlot == slot && schedule.stallCyclesBefore > 0 -> JBColor.RED
                schedule.assignedSlot == slot -> JBColor(Color(46, 160, 67), Color(63, 185, 80))
                else -> JBColor.GRAY
            }

            g.color = color
            g.stroke = BasicStroke(3.0f)
            g.drawLine(trackX, yTop, trackX, yTop + lineHeight)

            if (schedule.assignedSlot == slot && schedule.stallCyclesBefore > 0) {
                g.color = JBColor.RED
                g.stroke = BasicStroke(2.0f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_BEVEL, 0f, floatArrayOf(3f), 0f)
                g.drawLine(trackX - 4, yTop - 4, trackX - 4, yTop + (lineHeight / 2))

                g.font = Font("Monospaced", Font.BOLD, 9)
                g.drawString("-${schedule.stallCyclesBefore}c", trackX, yTop + 10)
            }
        }

        if (schedule.branchPenalty != null) {
            val branchX = slotsStartX + (slots.size * trackWidth) + 6
            g.color = JBColor.ORANGE
            g.font = Font("Monospaced", Font.BOLD, 10)
            g.drawString("B:${schedule.branchPenalty}", branchX, yTop + lineHeight - 4)
        }
    }

    private fun paintIFetchTrack(g: Graphics2D, fetch: IFetchEvent?, xLeft: Int, yTop: Int, lineHeight: Int) {
        if (fetch == null) return

        if (fetch.is32ByteLineBoundary) {
            g.color = JBColor(Color(0, 120, 215), Color(75, 165, 255))
            g.stroke = BasicStroke(1.5f)
            g.drawLine(xLeft, yTop, xLeft + 74, yTop)
        } else if (fetch.is16ByteFetchBoundary) {
            g.color = JBColor(Color(150, 150, 150, 140), Color(110, 110, 110, 140))
            g.stroke = BasicStroke(1.0f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_BEVEL, 0f, floatArrayOf(2f), 0f)
            g.drawLine(xLeft, yTop, xLeft + 74, yTop)
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
            g.drawString("IF-${fetch.icacheMissStall}c", xLeft - 24, yTop + 10)
        }
    }
}
