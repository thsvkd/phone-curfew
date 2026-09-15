package com.thsvkd.curfew.ui

import android.app.Application
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.thsvkd.curfew.collect.UsageAccess
import com.thsvkd.curfew.data.CurfewDb
import com.thsvkd.curfew.data.CurfewSettings
import com.thsvkd.curfew.data.SettingsStore
import com.thsvkd.curfew.score.windowFor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

class SettingsViewModel(app: Application) : AndroidViewModel(app) {

    private val dao = CurfewDb.get(app).dao()
    private val store = SettingsStore(app)

    val settings: StateFlow<CurfewSettings> =
        store.flow.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CurfewSettings())

    val message = MutableStateFlow<String?>(null)

    fun setToleranceSeconds(seconds: Int) {
        viewModelScope.launch { store.setToleranceSeconds(seconds) }
    }

    fun setNightWork(startMinutes: Int, endMinutes: Int) {
        viewModelScope.launch { store.setNightWork(startMinutes, endMinutes) }
    }

    /** 오늘 밤 야근 구간에 이미 쌓인 사용 기록을 0으로 지운다. */
    fun clearNightWorkUsage(startMinutes: Int, endMinutes: Int) {
        viewModelScope.launch {
            val zone = ZoneId.systemDefault()
            val window = windowFor(LocalDate.now(zone), zone, startMinutes, endMinutes)
            if (window != null) {
                dao.zeroUsage(window.startMs, window.endMs)
                message.value = "야근 시간대 사용 기록을 지웠습니다"
            }
        }
    }

    fun clearAll() {
        viewModelScope.launch {
            dao.clearAll(System.currentTimeMillis())
            message.value = "기록을 모두 지웠습니다"
        }
    }

    fun exportCsv(uri: Uri) {
        viewModelScope.launch {
            val rows = withContext(Dispatchers.IO) { dao.allBuckets() }
            val zone = ZoneId.systemDefault()
            val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(zone)
            withContext(Dispatchers.IO) {
                getApplication<Application>().contentResolver.openOutputStream(uri)?.use { out ->
                    out.bufferedWriter().use { writer ->
                        writer.appendLine("bucketStartMs,localTime,usedSeconds")
                        rows.forEach {
                            writer.appendLine(
                                "${it.bucketStartMs}," +
                                    "${formatter.format(Instant.ofEpochMilli(it.bucketStartMs))}," +
                                    "${it.usedSeconds}"
                            )
                        }
                    }
                }
            }
            message.value = "${rows.size}칸을 내보냈습니다"
        }
    }
}

@Composable
fun SettingsScreen(onBack: () -> Unit, onFixPermission: () -> Unit) {
    val vm: SettingsViewModel = viewModel()
    val settings by vm.settings.collectAsState()
    val message by vm.message.collectAsState()
    val context = LocalContext.current
    var confirmClear by remember { mutableStateOf(false) }

    val exporter = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv")
    ) { uri -> if (uri != null) vm.exportCsv(uri) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("설정", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            TextButton(onClick = onBack) { Text("닫기") }
        }

        ToleranceCard(settings.toleranceSeconds, vm::setToleranceSeconds)

        if (settings.toleranceSeconds == 30 * 60) {
            NightWorkCard(
                startMinutes = settings.nightWorkStartMinutes,
                endMinutes = settings.nightWorkEndMinutes,
                onChange = vm::setNightWork,
                onClear = vm::clearNightWorkUsage,
            )
        }

        ActionCard(
            title = "사용량 접근 권한",
            body = if (UsageAccess.isGranted(context)) {
                "켜져 있습니다. 이 권한이 없으면 기록을 모을 수 없습니다."
            } else {
                "꺼져 있습니다. 눌러서 시스템 설정에서 켜 주세요."
            },
            onClick = onFixPermission,
        )

        ActionCard(
            title = "CSV로 내보내기",
            body = "칸의 시작 시각과 사용 초를 한 줄씩 저장합니다.",
            onClick = { exporter.launch("curfew-usage.csv") },
        )

        ActionCard(
            title = "기록 전체 삭제",
            body = "되돌릴 수 없습니다. 지운 뒤에는 지금 이전이 모두 공백으로 표시됩니다.",
            danger = true,
            onClick = { confirmClear = true },
        )

        if (message != null) {
            Text(
                text = message!!,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            LaunchedEffect(message) {
                kotlinx.coroutines.delay(3_000)
                vm.message.value = null
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("기록을 모두 지울까요") },
            text = { Text("지금까지 모은 사용량과 판정이 사라집니다. 되돌릴 수 없습니다.") },
            confirmButton = {
                TextButton(onClick = { vm.clearAll(); confirmClear = false }) { Text("지웁니다") }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text("취소") }
            },
        )
    }
}

@Composable
private fun ToleranceCard(toleranceSeconds: Int, onChange: (Int) -> Unit) {
    // 슬라이더를 끄는 동안에는 화면 값만 움직이고, 손을 뗄 때 한 번 저장한다.
    var minutes by remember(toleranceSeconds) {
        mutableFloatStateOf(toleranceSeconds / 60f)
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(Modifier.padding(18.dp)) {
            Row {
                Text(
                    "허용 오차",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "${minutes.roundToInt()}분",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Text(
                text = "제한 시간대 누적 사용이 이 값 이하이면 성공으로 봅니다. " +
                    "알람 해제 같은 짧은 조작까지 실패로 세지 않기 위한 여유입니다.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))
            Slider(
                value = minutes,
                onValueChange = { minutes = it },
                onValueChangeFinished = { onChange(minutes.roundToInt() * 60) },
                valueRange = 0f..30f,
                steps = 29,
            )
        }
    }
}

@Composable
private fun NightWorkCard(
    startMinutes: Int,
    endMinutes: Int,
    onChange: (Int, Int) -> Unit,
    onClear: (Int, Int) -> Unit,
) {
    // 슬라이더를 끄는 동안에는 화면 값만 움직이고, 손을 뗄 때 한 번 저장한다. 실제로 지우는
    // 것은 되돌릴 수 없는 동작이라 아래 버튼을 따로 눌러야 한다.
    var range by remember(startMinutes, endMinutes) {
        mutableStateOf(startMinutes.toFloat()..endMinutes.toFloat())
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(Modifier.padding(18.dp)) {
            Row {
                Text(
                    "야근 시간대",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "${formatHhMm(range.start.roundToInt())}–${formatHhMm(range.endInclusive.roundToInt())}",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Text(
                text = "이 구간에 이미 쌓인 사용 기록을 0으로 지웁니다. " +
                    "야근으로 어쩔 수 없이 쓴 시간을 실패로 세지 않기 위한 예외입니다.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))
            RangeSlider(
                value = range,
                onValueChange = { range = it },
                onValueChangeFinished = {
                    onChange(range.start.roundToInt(), range.endInclusive.roundToInt())
                },
                valueRange = 0f..1440f,
                steps = 47,
            )
            Spacer(Modifier.height(6.dp))
            TextButton(
                onClick = { onClear(range.start.roundToInt(), range.endInclusive.roundToInt()) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("이 구간 사용 기록 지우기") }
        }
    }
}

@Composable
private fun ActionCard(
    title: String,
    body: String,
    danger: Boolean = false,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(Modifier.padding(18.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = if (danger) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            )
            Text(
                text = body,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
