package dev.securemesh.commander.domain.model

import org.junit.Assert.*
import org.junit.Test

class RouteModelTest {
    @Test fun `v05 static route can honestly omit unsupported metrics`() {
        val route = MeshRoute(destination = "SM-B442", nextHop = "SM-19AF", type = RouteType.STATIC)
        assertEquals("SM-B442", route.destination)
        assertEquals("SM-19AF", route.nextHop)
        assertEquals(RouteType.STATIC, route.type)
        assertNull(route.hopCount)
        assertNull(route.quality)
        assertNull(route.updatedAtEpochMs)
    }
}
