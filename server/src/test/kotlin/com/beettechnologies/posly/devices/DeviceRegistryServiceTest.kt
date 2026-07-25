package com.beettechnologies.posly.devices

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DeviceRegistryServiceTest {

    private fun enroll(
        service: DeviceRegistryService,
        storeId: String = "store-1",
        terminalType: String? = "Verifone P400"
    ): DeviceRecord {
        val pairingCode = service.createPairCode(storeId, createdBy = "admin-1", terminalType = terminalType)
        val result = service.enrollDevice(pairingCode.code, requestedStoreId = null, name = "Front Register")
        return (result as EnrollDeviceResult.Success).device
    }

    @Test
    fun `enrolled device carries the terminal type from its pairing code`() {
        val service = DeviceRegistryService()
        val device = enroll(service, terminalType = "Verifone P400")

        assertEquals("Verifone P400", device.terminalType)
        assertEquals(DeviceStatus.ACTIVE, device.status)
        assertNull(device.lastSeenAt)
    }

    @Test
    fun `listDevices filters by store and getDevice returns a single record`() {
        val service = DeviceRegistryService()
        val deviceA = enroll(service, storeId = "store-a")
        val deviceB = enroll(service, storeId = "store-b")

        val storeADevices = service.listDevices(storeId = "store-a")
        assertEquals(listOf(deviceA.id), storeADevices.map { it.id })

        val allDevices = service.listDevices()
        assertEquals(setOf(deviceA.id, deviceB.id), allDevices.map { it.id }.toSet())

        assertEquals(deviceB.id, service.getDevice(deviceB.id)?.id)
        assertNull(service.getDevice("does-not-exist"))
    }

    @Test
    fun `heartbeat with valid credentials updates lastSeenAt`() {
        var now = Instant.parse("2026-01-01T00:00:00Z")
        val service = DeviceRegistryService(nowProvider = { now })
        val device = enroll(service)

        now = now.plusSeconds(30)
        val result = service.recordHeartbeat(device.clientId, device.clientSecret)

        val success = assertIs<HeartbeatResult.Success>(result)
        assertEquals(now, success.device.lastSeenAt)
    }

    @Test
    fun `heartbeat with wrong secret or unknown client id is rejected`() {
        val service = DeviceRegistryService()
        val device = enroll(service)

        assertEquals(HeartbeatResult.InvalidCredentials, service.recordHeartbeat(device.clientId, "wrong-secret"))
        assertEquals(HeartbeatResult.InvalidCredentials, service.recordHeartbeat("unknown-client", device.clientSecret))
    }

    @Test
    fun `heartbeat for a deprovisioned device is rejected`() {
        val service = DeviceRegistryService()
        val device = enroll(service)
        service.deprovisionDevice(device.id, actorId = "admin-1")

        assertEquals(HeartbeatResult.Deprovisioned, service.recordHeartbeat(device.clientId, device.clientSecret))
    }

    @Test
    fun `authenticateDevice succeeds for valid credentials and does not touch lastSeenAt`() {
        val service = DeviceRegistryService()
        val device = enroll(service)

        val result = service.authenticateDevice(device.clientId, device.clientSecret)

        val success = assertIs<DeviceAuthResult.Success>(result)
        assertEquals(device.id, success.device.id)
        assertNull(success.device.lastSeenAt)
    }

    @Test
    fun `authenticateDevice rejects wrong secret, unknown client id, and a deprovisioned device`() {
        val service = DeviceRegistryService()
        val device = enroll(service)
        val other = enroll(service)
        service.deprovisionDevice(other.id, actorId = "admin-1")

        assertEquals(DeviceAuthResult.InvalidCredentials, service.authenticateDevice(device.clientId, "wrong-secret"))
        assertEquals(DeviceAuthResult.InvalidCredentials, service.authenticateDevice("unknown-client", device.clientSecret))
        assertEquals(DeviceAuthResult.Deprovisioned, service.authenticateDevice(other.clientId, other.clientSecret))
    }

    @Test
    fun `deprovisioning a device transitions its status and rejects a second deprovision`() {
        val service = DeviceRegistryService()
        val device = enroll(service)

        val result = service.deprovisionDevice(device.id, actorId = "admin-1")
        val success = assertIs<DeprovisionResult.Success>(result)
        assertEquals(DeviceStatus.DEPROVISIONED, success.device.status)
        assertTrue(success.device.deprovisionedAt != null)

        assertEquals(DeprovisionResult.AlreadyDeprovisioned, service.deprovisionDevice(device.id, actorId = "admin-1"))
        assertEquals(DeprovisionResult.NotFound, service.deprovisionDevice("does-not-exist", actorId = "admin-1"))
    }

    @Test
    fun `health status is never-seen, online, then offline as time advances past the threshold`() {
        var now = Instant.parse("2026-01-01T00:00:00Z")
        val service = DeviceRegistryService(nowProvider = { now })
        val device = enroll(service)

        assertEquals(DeviceHealthStatus.NEVER_SEEN, device.healthStatus(now))

        val afterHeartbeat = (service.recordHeartbeat(device.clientId, device.clientSecret) as HeartbeatResult.Success).device
        assertEquals(DeviceHealthStatus.ONLINE, afterHeartbeat.healthStatus(now))

        now = now.plusSeconds(HEARTBEAT_OFFLINE_THRESHOLD_SECONDS + 1)
        assertEquals(DeviceHealthStatus.OFFLINE, afterHeartbeat.healthStatus(now))
    }
}
