package com.thsvkd.curfew.ui

import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.thsvkd.curfew.data.ChartMode
import com.thsvkd.curfew.score.MINUTES_PER_DAY

private const val MAX_SECONDS_PER_SLOT = 600f

/**
 * 하루치 사용량을 그린다. 세로축은 한 칸(10분) 안에서 화면을 켜 둔 분으로 0에서 10까지 고정이고,
 * 커퓨 시간대는 강조색 음영으로 겹쳐 그린다. 음영 안이 비어 있으면 성공이라는 것을 그래프만
 * 보고 알 수 있게 하려는 것이 이 그림의 목적이다.
 */
@Composable
fun UsageChart(
    slots: List<Int>,
    gapSlots: Set<Int>,
    curfewStartMinutes: Int,
    curfewEndMinutes: Int,
    mode: ChartMode,
    modifier: Modifier = Modifier,
) {
    val extras = LocalCurfewColors.current
    val accent = MaterialTheme.colorScheme.primary
    val measurer = rememberTextMeasurer()
    val labelStyle = TextStyle(fontSize = 9.sp, color = extras.muted)

    Canvas(modifier) {
        if (slots.isEmpty()) return@Canvas

        val top = 4.dp.toPx()
        val plotLeft = 22.dp.toPx()
        val plotRight = size.width
        val plotBottom = size.height - 18.dp.toPx()
        val plotWidth = plotRight - plotLeft
        val plotHeight = plotBottom - top
        if (plotWidth <= 0f || plotHeight <= 0f) return@Canvas

        val step = plotWidth / slots.size
        fun yOf(seconds: Int) = plotBottom - (seconds / MAX_SECONDS_PER_SLOT) * plotHeight

        drawCurfewBands(curfewStartMinutes, curfewEndMinutes, accent, plotLeft, plotWidth, top, plotHeight)

        // 기록이 없는 칸. 사용 0분과 눈으로 구분되도록 중립색으로 덮는다. 커퓨 음영은
        // 강조색이라 색상만으로도 갈린다.
        gapSlots.forEach { i ->
            drawRect(
                color = extras.muted,
                topLeft = Offset(plotLeft + i * step, top),
                size = Size(step, plotHeight),
                alpha = 0.28f,
            )
        }

        listOf(0, 5, 10).forEach { minutes ->
            val y = yOf(minutes * 60)
            drawLine(extras.chartGrid, Offset(plotLeft, y), Offset(plotRight, y), 1f)
            val label = measurer.measure(minutes.toString(), labelStyle)
            drawText(
                label,
                topLeft = Offset(plotLeft - 4.dp.toPx() - label.size.width, y - label.size.height / 2f),
            )
        }

        when (mode) {
            ChartMode.BAR -> slots.forEachIndexed { i, seconds ->
                if (seconds <= 0) return@forEachIndexed
                val y = yOf(seconds)
                drawRect(
                    color = accent,
                    topLeft = Offset(plotLeft + i * step + step * 0.15f, y),
                    size = Size(step * 0.7f, plotBottom - y),
                )
            }

            ChartMode.LINE -> {
                val points = slots.mapIndexed { i, seconds ->
                    Offset(plotLeft + i * step + step / 2f, yOf(seconds))
                }
                val area = Path().apply {
                    moveTo(points.first().x, plotBottom)
                    points.forEach { lineTo(it.x, it.y) }
                    lineTo(points.last().x, plotBottom)
                    close()
                }
                drawPath(area, accent, alpha = 0.15f)
                val line = Path().apply {
                    moveTo(points.first().x, points.first().y)
                    points.drop(1).forEach { lineTo(it.x, it.y) }
                }
                drawPath(line, accent, style = Stroke(width = 2.dp.toPx()))
            }
        }

        listOf(0, 6, 12, 18, 24).forEach { hour ->
            val label = measurer.measure(
                if (hour == 24) "24:00" else "%02d:00".format(hour),
                labelStyle,
            )
            val center = plotLeft + plotWidth * (hour / 24f)
            val x = when (hour) {
                0 -> plotLeft
                24 -> plotRight - label.size.width
                else -> center - label.size.width / 2f
            }
            drawText(label, topLeft = Offset(x, plotBottom + 4.dp.toPx()))
        }
    }
}

/** 자정을 넘는 커퓨는 좌우 두 덩어리로 나뉜다. */
private fun DrawScope.drawCurfewBands(
    startMinutes: Int,
    endMinutes: Int,
    accent: Color,
    plotLeft: Float,
    plotWidth: Float,
    top: Float,
    plotHeight: Float,
) {
    val bands = if (startMinutes <= endMinutes) {
        listOf(startMinutes to endMinutes)
    } else {
        listOf(0 to endMinutes, startMinutes to MINUTES_PER_DAY)
    }
    for ((from, to) in bands) {
        if (to <= from) continue
        drawRect(
            color = accent,
            topLeft = Offset(plotLeft + plotWidth * (from.toFloat() / MINUTES_PER_DAY), top),
            size = Size(plotWidth * ((to - from).toFloat() / MINUTES_PER_DAY), plotHeight),
            alpha = 0.10f,
        )
    }
}
