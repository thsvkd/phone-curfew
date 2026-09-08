package com.thsvkd.curfew.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.thsvkd.curfew.data.ChartMode
import com.thsvkd.curfew.score.DayResult
import com.thsvkd.curfew.score.DayStatus
import java.time.LocalDate
import java.time.format.TextStyle as JavaTextStyle
import java.util.Locale

private val CardShape = RoundedCornerShape(20.dp)

@Composable
fun HomeScreen(
    state: HomeState,
    onCurfewChange: (Int, Int) -> Unit,
    onChartMode: (ChartMode) -> Unit,
    onShiftDate: (Long) -> Unit,
    onShowDate: (LocalDate) -> Unit,
    onOpenSettings: () -> Unit,
    onFixPermission: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Header(state, onOpenSettings)

        if (!state.permissionGranted) {
            Banner(
                text = "사용량 접근 권한이 꺼져 있어 기록을 모을 수 없습니다. 눌러서 켜 주세요.",
                onClick = onFixPermission,
            )
        } else if (state.stale) {
            Banner(
                text = "하루 넘게 기록이 쌓이지 않았습니다. 절전 설정에서 이 앱을 제외해 주세요.",
                onClick = onFixPermission,
            )
        }

        CurfewCard(state, onCurfewChange)
        WeekCard(state, onShowDate)
        ChartCard(state, onChartMode, onShiftDate)
    }
}

@Composable
private fun Header(state: HomeState, onOpenSettings: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("잘 자고 있나요", style = MaterialTheme.typography.titleLarge)
            Text(
                text = state.latest.summary(),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(onClick = onOpenSettings) { Text("설정") }
    }
}

private fun DayResult?.summary(): String {
    if (this == null) return "아직 끝난 커퓨가 없습니다"
    return when (status) {
        DayStatus.SUCCESS -> "최근 결과는 성공이었습니다 · ${date.short()}"
        DayStatus.FAIL -> "최근 결과는 실패였습니다 · ${date.short()}"
        DayStatus.NO_DATA -> "최근 결과는 기록이 없어 판정하지 못했습니다"
        DayStatus.IN_PROGRESS -> "아직 끝난 커퓨가 없습니다"
    }
}

private fun LocalDate.short(): String = "${monthValue}/${dayOfMonth}"

private fun LocalDate.weekdayLabel(): String =
    dayOfWeek.getDisplayName(JavaTextStyle.NARROW, Locale.KOREAN)

@Composable
private fun Banner(text: String, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = CardShape,
        color = MaterialTheme.colorScheme.error.copy(alpha = 0.14f),
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(16.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
        )
    }
}

@Composable
private fun SectionCard(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = CardShape,
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(modifier = Modifier.padding(18.dp), content = content)
    }
}

@Composable
private fun CardLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun CurfewCard(state: HomeState, onCurfewChange: (Int, Int) -> Unit) {
    var editing by remember { mutableStateOf<Boolean?>(null) }

    SectionCard {
        CardLabel("사용 제한 시간대")
        Spacer(Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            TimeChip("시작", state.settings.startMinutes, Modifier.weight(1f)) { editing = true }
            Text(
                "–",
                modifier = Modifier.padding(horizontal = 10.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.Bold,
            )
            TimeChip("해제", state.settings.endMinutes, Modifier.weight(1f)) { editing = false }
        }
        Spacer(Modifier.height(12.dp))
        Text(
            text = "이 시간대의 사용량으로 그날의 성공 여부를 판정합니다. 앱이 차단하지는 않습니다.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    val target = editing
    if (target != null) {
        val initial = if (target) state.settings.startMinutes else state.settings.endMinutes
        TimePickerDialog(
            initialMinutes = initial,
            onDismiss = { editing = null },
            onConfirm = { minutes ->
                if (target) {
                    onCurfewChange(minutes, state.settings.endMinutes)
                } else {
                    onCurfewChange(state.settings.startMinutes, minutes)
                }
                editing = null
            },
        )
    }
}

@Composable
private fun TimeChip(label: String, minutes: Int, modifier: Modifier, onClick: () -> Unit) {
    Surface(
        modifier = modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // 로케일이 붙이는 오전·오후 표기 없이 24시간으로 직접 그린다.
            Text(
                text = formatHhMm(minutes),
                fontSize = 22.sp,
                fontWeight = FontWeight.ExtraBold,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimePickerDialog(
    initialMinutes: Int,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit,
) {
    val picker = rememberTimePickerState(
        initialHour = initialMinutes / 60,
        initialMinute = initialMinutes % 60,
        is24Hour = true,
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = { onConfirm(picker.hour * 60 + picker.minute) }) { Text("확인") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
        text = { TimePicker(state = picker) },
    )
}

@Composable
private fun WeekCard(state: HomeState, onShowDate: (LocalDate) -> Unit) {
    val extras = LocalCurfewColors.current
    SectionCard {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = "${state.weekSuccess}",
                style = MaterialTheme.typography.titleLarge,
            )
            Text(
                text = " / 7일 성공",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Surface(
                shape = RoundedCornerShape(999.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
            ) {
                Text(
                    text = "${state.streak}일 연속",
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        Spacer(Modifier.height(16.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            state.week.forEach { day ->
                Column(
                    modifier = Modifier.clickable { onShowDate(day.date) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    StatusDot(day.status, selected = day.date == state.date, extras = extras)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = day.date.weekdayLabel(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun StatusDot(status: DayStatus, selected: Boolean, extras: CurfewColors) {
    val ring = if (selected) MaterialTheme.colorScheme.primary else Color.Transparent
    Box(
        modifier = Modifier
            .size(30.dp)
            .border(2.dp, ring, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        val dot = Modifier.size(22.dp)
        when (status) {
            DayStatus.SUCCESS -> Box(dot.background(extras.success, CircleShape))
            DayStatus.FAIL -> Box(dot.background(extras.failure, CircleShape))
            DayStatus.IN_PROGRESS -> Box(dot.border(2.dp, extras.muted, CircleShape))
            DayStatus.NO_DATA -> Box(dot.border(2.dp, extras.muted.copy(alpha = 0.4f), CircleShape))
        }
    }
}

@Composable
private fun ChartCard(
    state: HomeState,
    onChartMode: (ChartMode) -> Unit,
    onShiftDate: (Long) -> Unit,
) {
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { onShiftDate(-1) }) { Text("‹") }
            Text(
                text = "${state.date.short()} (${state.date.weekdayLabel()})",
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.titleSmall,
            )
            TextButton(
                onClick = { onShiftDate(1) },
                enabled = state.date.isBefore(LocalDate.now()),
            ) { Text("›") }
            Spacer(Modifier.width(4.dp))
            ModeToggle(state.settings.chartMode, onChartMode)
        }
        Spacer(Modifier.height(12.dp))
        UsageChart(
            slots = state.slots,
            gapSlots = state.gapSlots,
            curfewStartMinutes = state.settings.startMinutes,
            curfewEndMinutes = state.settings.endMinutes,
            mode = state.settings.chartMode,
            modifier = Modifier.fillMaxWidth().height(170.dp),
        )
        Spacer(Modifier.height(10.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Legend("총 사용", formatDuration(state.dayTotalSeconds))
            Legend("제한 시간대", formatDuration(state.curfewSeconds))
            Legend("표본 간격", "10분")
        }
    }
}

@Composable
private fun ModeToggle(mode: ChartMode, onChange: (ChartMode) -> Unit) {
    Surface(shape = RoundedCornerShape(999.dp), color = MaterialTheme.colorScheme.background) {
        Row(Modifier.padding(3.dp)) {
            ModeButton("선", mode == ChartMode.LINE) { onChange(ChartMode.LINE) }
            ModeButton("막대", mode == ChartMode.BAR) { onChange(ChartMode.BAR) }
        }
    }
}

@Composable
private fun ModeButton(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(999.dp),
        color = if (selected) MaterialTheme.colorScheme.surface else Color.Transparent,
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 5.dp),
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}

@Composable
private fun Legend(label: String, value: String) {
    Row {
        Text(
            text = "$label ",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(text = value, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
    }
}

fun formatHhMm(minutes: Int): String = "%02d:%02d".format(minutes / 60, minutes % 60)

fun formatDuration(seconds: Int): String {
    val totalMinutes = (seconds + 30) / 60
    val hours = totalMinutes / 60
    val rest = totalMinutes % 60
    return if (hours > 0) "${hours}시간 ${rest}분" else "${rest}분"
}
