package com.thsvkd.curfew

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.thsvkd.curfew.collect.UsageAccess
import com.thsvkd.curfew.collect.collectNow
import com.thsvkd.curfew.ui.CurfewTheme
import com.thsvkd.curfew.ui.HomeScreen
import com.thsvkd.curfew.ui.HomeViewModel
import com.thsvkd.curfew.ui.SettingsScreen

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            CurfewTheme {
                val vm: HomeViewModel = viewModel()
                val state by vm.state.collectAsStateWithLifecycle()
                val context = LocalContext.current
                var showSettings by remember { mutableStateOf(false) }

                // 앱이 앞으로 올라올 때마다 권한 상태를 다시 보고, 밀린 구간을 바로 메운다.
                // 시스템 설정에서 권한을 켜고 돌아오는 경로도 여기로 들어온다.
                LifecycleResumeEffect(Unit) {
                    vm.refresh()
                    collectNow(context)
                    onPauseOrDispose { }
                }

                val openUsageAccess = { startActivity(UsageAccess.settingsIntent()) }

                Surface(
                    modifier = Modifier
                        .fillMaxSize()
                        .windowInsetsPadding(WindowInsets.systemBars)
                        .consumeWindowInsets(WindowInsets.systemBars),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    if (showSettings) {
                        SettingsScreen(
                            onBack = { showSettings = false },
                            onFixPermission = openUsageAccess,
                        )
                    } else {
                        HomeScreen(
                            state = state,
                            onCurfewChange = vm::setCurfew,
                            onChartMode = vm::setChartMode,
                            onShiftDate = vm::shiftDate,
                            onShowDate = vm::showDate,
                            onOpenSettings = { showSettings = true },
                            onFixPermission = openUsageAccess,
                        )
                    }
                }
            }
        }
    }
}
