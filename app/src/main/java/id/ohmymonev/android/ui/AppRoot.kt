package id.ohmymonev.android.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

private data class TabSpec(val label: String, val icon: ImageVector)

private val tabs = listOf(
    TabSpec("Beranda", Icons.Filled.Home),
    TabSpec("Jadwal", Icons.Filled.DateRange),
    TabSpec("Log", Icons.AutoMirrored.Filled.List),
    TabSpec("Sesi", Icons.Filled.Person),
)

private const val TAB_HOME = 0
private const val TAB_SCHEDULE = 1
private const val TAB_LOG = 2
private const val TAB_SESSION = 3

@Composable
fun AppRoot() {
    OhMyMonevTheme {
        var selectedTab by remember { mutableIntStateOf(TAB_HOME) }
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            containerColor = MaterialTheme.colorScheme.background,
            bottomBar = {
                NavigationBar(
                    containerColor = MaterialTheme.colorScheme.surface,
                    tonalElevation = 0.dp,
                ) {
                    tabs.forEachIndexed { index, tab ->
                        NavigationBarItem(
                            selected = selectedTab == index,
                            onClick = { selectedTab = index },
                            icon = { Icon(tab.icon, contentDescription = null) },
                            label = { Text(tab.label) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = MaterialTheme.colorScheme.primary,
                                selectedTextColor = MaterialTheme.colorScheme.primary,
                                indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                            ),
                        )
                    }
                }
            },
        ) { padding ->
            AnimatedContent(
                targetState = selectedTab,
                transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(120)) },
                modifier = Modifier.padding(padding),
                label = "tabs",
            ) { index ->
                when (index) {
                    TAB_HOME -> HomeTab(
                        onOpenSession = { selectedTab = TAB_SESSION },
                    )
                    TAB_SCHEDULE -> ScheduleTab()
                    TAB_LOG -> LogTab()
                    else -> SessionTab()
                }
            }
        }
    }
}
