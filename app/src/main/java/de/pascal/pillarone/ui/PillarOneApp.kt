package de.pascal.pillarone.ui

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import de.pascal.pillarone.R

private enum class AppPage(
    @StringRes val label: Int,
    @DrawableRes val icon: Int,
    val tag: String,
) {
    DISPLAY(R.string.navigation_display, R.drawable.ic_display, "navigation-display"),
    CONFIGURATION(R.string.navigation_configuration, R.drawable.ic_configuration, "navigation-configuration"),
    ANALYSIS(R.string.navigation_analysis, R.drawable.ic_analysis, "navigation-analysis"),
    SETTINGS(R.string.navigation_settings, R.drawable.ic_settings, "navigation-settings"),
}

@Composable
fun PillarOneApp(
    configurationContent: @Composable () -> Unit = { ConfigurationScreen() },
    analysisContent: @Composable () -> Unit = { AnalysisScreen() },
    settingsContent: @Composable () -> Unit = { SettingsScreen() },
    displayContent: @Composable () -> Unit,
) {
    var selectedPage by rememberSaveable { mutableStateOf(AppPage.DISPLAY) }
    val pageState = rememberSaveableStateHolder()

    Scaffold(
        bottomBar = {
            NavigationBar(
                modifier = Modifier.navigationBarsPadding().height(64.dp),
                windowInsets = WindowInsets(0, 0, 0, 0),
            ) {
                AppPage.entries.forEach { page ->
                    NavigationBarItem(
                        selected = selectedPage == page,
                        onClick = { selectedPage = page },
                        icon = {
                            Icon(
                                painterResource(page.icon),
                                contentDescription = stringResource(page.label),
                            )
                        },
                        label = null,
                        modifier = Modifier.testTag(page.tag),
                    )
                }
            }
        },
    ) { insets ->
        Box(
            Modifier.fillMaxSize().padding(insets).consumeWindowInsets(insets),
        ) {
            pageState.SaveableStateProvider(selectedPage) {
                when (selectedPage) {
                    AppPage.DISPLAY -> displayContent()
                    AppPage.CONFIGURATION -> configurationContent()
                    AppPage.ANALYSIS -> analysisContent()
                    AppPage.SETTINGS -> settingsContent()
                }
            }
        }
    }
}
