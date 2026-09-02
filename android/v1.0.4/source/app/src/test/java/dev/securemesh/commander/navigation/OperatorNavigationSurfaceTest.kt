package dev.securemesh.commander.navigation

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OperatorNavigationSurfaceTest {
    private fun source(relative: String): String {
        val file = File(relative)
        assertTrue("Missing canonical source file: ${file.absolutePath}", file.isFile)
        return file.readText()
    }

    @Test
    fun operatorNavigationDoesNotExposeEngineeringVanguardPanel() {
        val root = source("app/src/main/java/dev/securemesh/commander/navigation/SecureMeshRoot.kt")
        val more = source("app/src/main/java/dev/securemesh/commander/feature/more/MoreScreen.kt")

        assertFalse(root.contains("NavItem(\"vanguard\""))
        assertFalse(root.contains("composable(\"vanguard\")"))
        assertTrue(root.contains("NavItem(\"map\", \"Карта\""))
        assertFalse(more.contains("\"vanguard\""))
        assertTrue(more.contains("\"devicecontrol\""))
        assertTrue(more.contains("Экран и кнопки"))
    }

    @Test
    fun currentNodeScreenUsesRepositoryQueueAndNoDirectBluetoothControl() {
        val screen = source("app/src/main/java/dev/securemesh/commander/feature/deviceui/DeviceControlScreen.kt")
        val viewModel = source("app/src/main/java/dev/securemesh/commander/feature/deviceui/DeviceControlViewModel.kt")

        assertTrue(viewModel.contains("Channel<DeviceUiAction>(capacity = 16)"))
        assertTrue(viewModel.contains("repository.sendDeviceUiAction(action)"))
        assertFalse(screen.contains("BluetoothGatt"))
        assertFalse(viewModel.contains("BluetoothGatt"))
    }

    @Test
    fun currentNodeScreenDoesNotExposeProtocolJargon() {
        val screen = source("app/src/main/java/dev/securemesh/commander/feature/deviceui/DeviceControlScreen.kt")
        val forbiddenVisibleTerms = listOf(
            "OLED CONTROL",
            "OLED MIRROR",
            "GET_UI_STATE",
            "PIXEL MIRROR",
            "STATE SYNC",
            "BLE Protocol",
            "UI_ACTION",
            "framebuffer",
            "UI OS",
        )

        forbiddenVisibleTerms.forEach { token ->
            assertFalse("Legacy/engineering term remains on operator screen: $token", screen.contains(token))
        }
    }
}
