package dev.securemesh.commander.feature.welcome

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import dev.securemesh.commander.core.ui.SecureMeshTheme
import org.junit.Rule
import org.junit.Test

class WelcomeScreenTest {
    @get:Rule val compose = createComposeRule()
    @Test fun primaryActionsAreVisible() {
        compose.setContent {
            SecureMeshTheme {
                WelcomeContent(onConnect = {}, onCurrentDemo = {}, onFutureDemo = {})
            }
        }
        compose.onNodeWithText("CONNECT DEVICE").assertIsDisplayed()
        compose.onNodeWithText("DEMO · CURRENT FIRMWARE v0.5").assertIsDisplayed()
        compose.onNodeWithText("DEMO · FUTURE SECUREMESH").assertIsDisplayed()
    }
}
