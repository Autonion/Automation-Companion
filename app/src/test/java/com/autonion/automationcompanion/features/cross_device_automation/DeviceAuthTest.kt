package com.autonion.automationcompanion.features.cross_device_automation

import com.autonion.automationcompanion.features.cross_device_automation.domain.Device
import com.autonion.automationcompanion.features.cross_device_automation.domain.DeviceRole
import com.autonion.automationcompanion.features.cross_device_automation.domain.DeviceStatus
import com.google.gson.Gson
import org.junit.Assert.*
import org.junit.Test

class DeviceAuthTest {

    @Test
    fun `device domain model correctly holds pairing state and agentId`() {
        val device = Device(
            id = "dev-1",
            name = "My Desktop",
            role = DeviceRole.WORK_DEVICE,
            ipAddress = "192.168.1.100",
            port = 4545,
            status = DeviceStatus.ONLINE,
            agentId = "agent-guid-5555",
            isPaired = true,
            isPairingRequired = false
        )

        assertEquals("dev-1", device.id)
        assertEquals("agent-guid-5555", device.agentId)
        assertTrue(device.isPaired)
        assertFalse(device.isPairingRequired)
    }

    @Test
    fun `client_info handshake JSON serialization contains required fields`() {
        val gson = Gson()
        val clientInfo = mapOf(
            "type" to "client_info",
            "app" to "AutomationCompanion",
            "version" to "1.0.0",
            "deviceId" to "comp-uuid-1234",
            "deviceName" to "Google Pixel 8",
            "deviceSecret" to "sec_token_xyz"
        )
        val json = gson.toJson(clientInfo)

        val parsed = gson.fromJson(json, Map::class.java)
        assertEquals("client_info", parsed["type"])
        assertEquals("comp-uuid-1234", parsed["deviceId"])
        assertEquals("sec_token_xyz", parsed["deviceSecret"])
        assertEquals("Google Pixel 8", parsed["deviceName"])
    }

    @Test
    fun `pairing_submit payload JSON contains pin and credentials`() {
        val gson = Gson()
        val payload = mapOf(
            "type" to "pairing_submit",
            "pin" to "482195",
            "deviceId" to "comp-uuid-1234",
            "deviceName" to "Google Pixel 8",
            "deviceSecret" to "sec_token_xyz"
        )
        val json = gson.toJson(payload)
        val parsed = gson.fromJson(json, Map::class.java)

        assertEquals("pairing_submit", parsed["type"])
        assertEquals("482195", parsed["pin"])
        assertEquals("comp-uuid-1234", parsed["deviceId"])
    }

    @Test
    fun `unpair_device payload JSON contains correct type`() {
        val gson = Gson()
        val payload = mapOf("type" to "unpair_device")
        val json = gson.toJson(payload)
        val parsed = gson.fromJson(json, Map::class.java)

        assertEquals("unpair_device", parsed["type"])
    }

    @Test
    fun `in memory device repository updateDevice explicitly mutates state while addOrUpdate preserves isSelected`() = kotlinx.coroutines.runBlocking {
        val repo = com.autonion.automationcompanion.features.cross_device_automation.data.InMemoryDeviceRepository()
        val dev1 = Device(
            id = "test-1",
            name = "Desktop PC",
            ipAddress = "192.168.1.10",
            port = 4545,
            isSelected = true,
            agentId = "agent-1",
            isPaired = true,
            isPairingRequired = false
        )
        repo.addOrUpdateDevice(dev1)

        // 1. Routine mDNS re-resolve (comes in with isSelected=false by default)
        val mDnsResolved = Device(
            id = "test-1",
            name = "Desktop PC",
            ipAddress = "192.168.1.10",
            port = 4545,
            isSelected = false // mDNS default
        )
        repo.addOrUpdateDevice(mDnsResolved)
        val afterMdns = repo.getDeviceById("test-1")
        assertNotNull(afterMdns)
        assertTrue("mDNS re-resolve must preserve isSelected=true", afterMdns!!.isSelected)
        assertTrue("mDNS re-resolve must preserve isPaired=true", afterMdns.isPaired)
        assertFalse("mDNS re-resolve must preserve isPairingRequired=false", afterMdns.isPairingRequired)
        assertEquals("agent-1", afterMdns.agentId)

        // 2. Explicit updateDevice (e.g. unpair/deselect)
        val unpaired = afterMdns.copy(
            isPaired = false,
            isSelected = false,
            isPairingRequired = true,
            agentId = null
        )
        repo.updateDevice(unpaired)
        val afterUnpair = repo.getDeviceById("test-1")
        assertNotNull(afterUnpair)
        assertFalse(afterUnpair!!.isPaired)
        assertFalse(afterUnpair.isSelected)
        assertTrue(afterUnpair.isPairingRequired)
        assertNull(afterUnpair.agentId)
    }

    @Test
    fun `version comparison correctly evaluates semver compatibility`() {
        assertEquals(0, CrossDeviceAutomationManager.compareVersions("2.0.5", "2.0.5"))
        assertTrue(CrossDeviceAutomationManager.compareVersions("2.0.6", "2.0.5") > 0)
        assertTrue(CrossDeviceAutomationManager.compareVersions("2.0.4", "2.0.5") < 0)
        assertTrue(CrossDeviceAutomationManager.compareVersions("3.0.0", "2.10.9") > 0)
        assertTrue(CrossDeviceAutomationManager.compareVersions("1.9.9", "2.0.0") < 0)
    }
}
