package com.servermon.monitor.data

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Parses a fixture that mirrors the real agent output (agent/agent.py),
 * including the numeric loadavg keys and null fields.
 */
class AgentJsonParsingTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun loadFixture(): String =
        javaClass.getResourceAsStream("/agent_metrics.json")!!
            .readBytes().decodeToString()

    @Test
    fun `parses full agent payload`() {
        val m = json.decodeFromString(Metrics.serializer(), loadFixture())

        assertEquals("matrix", m.hostname)
        assertEquals(512345L, m.uptimeSec)
        assertEquals(4, m.cpu.cores)
        assertEquals(37.4, m.cpu.usagePct, 0.001)
        assertEquals(54.25, m.cpu.tempC!!, 0.001)
    }

    @Test
    fun `parses numeric loadavg keys`() {
        val m = json.decodeFromString(Metrics.serializer(), loadFixture())
        assertEquals(0.42, m.cpu.loadavg.one, 0.001)
        assertEquals(0.37, m.cpu.loadavg.five, 0.001)
        assertEquals(0.31, m.cpu.loadavg.fifteen, 0.001)
    }

    @Test
    fun `parses memory, disks and network`() {
        val m = json.decodeFromString(Metrics.serializer(), loadFixture())
        assertEquals(62.2, m.memory!!.usedPct, 0.001)
        assertEquals(2, m.disks.size)
        assertEquals(41.2, m.disks[0].usedPct, 0.001)
        assertEquals(184320L, m.network.rxBps)
        assertEquals(63, m.network.established)
    }

    @Test
    fun `parses services and peer checks`() {
        val m = json.decodeFromString(Metrics.serializer(), loadFixture())
        assertEquals("active", m.services["nginx"])
        assertEquals("inactive", m.services["wireguard"])

        val okPeer = m.peers.first { it.name == "vpn" }
        assertTrue(okPeer.ok)
        assertEquals(8.4, okPeer.latencyMs!!, 0.001)
        assertNull(okPeer.error)

        val badPeer = m.peers.first { it.name == "relay" }
        assertFalse(badPeer.ok)
        assertNull(badPeer.latencyMs)
        assertEquals("Connection refused", badPeer.error)
    }

    @Test
    fun `parses optional ssh counter and tolerates missing fields`() {
        val m = json.decodeFromString(Metrics.serializer(), loadFixture())
        assertEquals(12, m.failedSshLogins24h)

        // Minimal payload (e.g. agent degraded): everything absent -> defaults
        val minimal = json.decodeFromString(Metrics.serializer(), "{}")
        assertNull(minimal.failedSshLogins24h)
        assertNull(minimal.memory)
        assertTrue(minimal.services.isEmpty())
    }
}
