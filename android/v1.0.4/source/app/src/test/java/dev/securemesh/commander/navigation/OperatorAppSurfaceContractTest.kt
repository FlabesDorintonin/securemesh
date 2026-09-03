package dev.securemesh.commander.navigation

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OperatorAppSurfaceContractTest {
    private fun projectRoot(): File {
        val cwd = File(System.getProperty("user.dir")).absoluteFile
        return generateSequence(cwd) { it.parentFile }
            .flatMap { root -> sequenceOf(root, File(root, "android/v1.0.4/source")) }
            .firstOrNull { File(it, "app/src/main/java/dev/securemesh/commander/navigation/SecureMeshRoot.kt").isFile }
            ?: error("SecureMesh Android source root not found from ${cwd.absolutePath}")
    }

    private fun source(relative: String): String = File(projectRoot(), relative).readText()

    @Test
    fun everyRegisteredOperatorSectionHasItsExpectedScreen() {
        val root = source("app/src/main/java/dev/securemesh/commander/navigation/SecureMeshRoot.kt")
        val expected = mapOf(
            "home" to "DashboardScreen(",
            "nodes" to "NodesScreen(",
            "node/{id}" to "NodeDetailsScreen(",
            "messages" to "MessagesScreen(",
            "messages/{peer}" to "MessagesScreen(",
            "map" to "MapScreen(",
            "more" to "MoreScreen(",
            "devicecontrol" to "DeviceControlScreen(",
            "topology" to "TopologyScreen(",
            "routes" to "RoutesScreen(",
            "fieldtest" to "FieldTestScreen(",
            "events" to "EventsScreen(",
            "diagnostics" to "DiagnosticsScreen(",
            "bleradar" to "BleRadarScreen(",
            "security" to "SecurityCenterScreen(",
            "settings" to "SettingsScreen(",
            "search" to "SearchScreen(",
        )
        expected.forEach { (route, screen) ->
            assertTrue("Missing operator route $route", root.contains("composable(\"$route\")"))
            assertTrue("Route $route is not wired to $screen", root.contains(screen))
        }
        assertFalse("Engineering VANGUARD panel must stay outside normal operator navigation", root.contains("composable(\"vanguard\")"))
    }

    @Test
    fun allOperatorScreensKeepBluetoothTransportBehindRepositoryBoundary() {
        val featureRoot = File(projectRoot(), "app/src/main/java/dev/securemesh/commander/feature")
        val files = featureRoot.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        assertTrue("Expected feature Kotlin sources", files.isNotEmpty())
        val forbidden = listOf("BluetoothGatt", "BluetoothLeScanner", ".bluetoothLeScanner")
        files.forEach { file ->
            val text = file.readText()
            forbidden.forEach { token ->
                assertFalse("Direct BLE transport token '$token' in ${file.relativeTo(featureRoot)}", text.contains(token))
            }
        }
    }

    @Test
    fun operatorCopyDoesNotRegressToObsoleteProtocolOrFalsePermissionClaims() {
        val featureRoot = File(projectRoot(), "app/src/main/java/dev/securemesh/commander/feature")
        val operatorFiles = featureRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" && "feature/vanguard" !in it.invariantSeparatorsPath }
            .toList()
        val forbidden = listOf(
            "BLE protocol v0.2",
            "PROTOCOL_READY",
            "authenticated INFO/nodeId",
            "SecureMesh Service UUID",
            "Облачный backend",
            "non-exportable",
            "GPS history",
            "У приложения нет INTERNET permission",
            "application overlays",
        )
        operatorFiles.forEach { file ->
            val text = file.readText()
            forbidden.forEach { token ->
                assertFalse("Stale operator token '$token' in ${file.name}", text.contains(token))
            }
        }
        val security = source("app/src/main/java/dev/securemesh/commander/feature/security/SecurityCenterScreen.kt")
        assertTrue(security.contains("загрузки офлайн-карты по HTTPS"))
        assertTrue(security.contains("mesh/BLE-работа не требует облачного сервера"))
    }

    @Test
    fun productionKotlinSourcesContainNoForceUnwraps() {
        val main = File(projectRoot(), "app/src/main/java")
        val offenders = main.walkTopDown()
            .filter { it.isFile && it.extension == "kt" && it.readText().contains("!!") }
            .map { it.relativeTo(main).invariantSeparatorsPath }
            .toList()
        assertTrue("Force unwraps remain in production Kotlin sources: $offenders", offenders.isEmpty())
    }

    @Test
    fun build21IdentityAndArm64PackagingAreVersioned() {
        val build = source("app/build.gradle.kts")
        assertTrue(build.contains("versionCode = 21"))
        assertTrue(build.contains("secureMeshArm64Only"))
        assertTrue(build.contains("abiFilters += \"arm64-v8a\""))
    }
}
