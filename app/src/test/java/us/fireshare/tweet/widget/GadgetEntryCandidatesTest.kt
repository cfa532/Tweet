package us.fireshare.tweet.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The entry candidate list decides how a cold deep link finds a node to talk
 * to. Entry discovery races two preferred addresses per node together and
 * retains the other public interfaces for failover.
 *
 * Values are built the way Gson hands them over from the boot page: nested
 * ArrayLists, response times as Double.
 */
class GadgetEntryCandidatesTest {

    private fun addrs(vararg nodes: List<Pair<String, Double>>): List<*> =
        nodes.map { node -> node.map { arrayListOf(it.first, it.second) }.toCollection(ArrayList()) }
            .toCollection(ArrayList())

    @Test
    fun `takes one address per node per round`() {
        val candidates = Gadget.entryIpCandidates(
            addrs(
                listOf("125.229.161.122:8080" to 1.85E7, "23.5.5.5:8080" to 4.4E8),
                listOf("115.196.228.69:8002" to 1.88E8, "24.6.6.6:8002" to 4.5E8),
            )
        )

        assertEquals(
            listOf(
                // every node's fastest first, quickest of them leading
                "125.229.161.122:8080",
                "115.196.228.69:8002",
                // then every node's second fastest
                "23.5.5.5:8080",
                "24.6.6.6:8002",
            ),
            candidates
        )
    }

    @Test
    fun `does not put two addresses of one node in a row while another node has one left`() {
        // Both of node A's addresses beat node B's best, which is exactly the
        // case a global sort by response time got wrong: it probed the same
        // machine twice before trying a different one.
        val candidates = Gadget.entryIpCandidates(
            addrs(
                listOf("1.1.1.1:8080" to 10.0, "1.1.1.2:8080" to 20.0),
                listOf("2.2.2.2:8080" to 50.0),
            )
        )

        assertEquals(listOf("1.1.1.1:8080", "2.2.2.2:8080", "1.1.1.2:8080"), candidates)
    }

    @Test
    fun `ranks each node by response time before interleaving`() {
        val candidates = Gadget.entryIpCandidates(
            addrs(
                listOf("1.1.1.1:8080" to 300.0, "1.1.1.2:8080" to 100.0),
                listOf("2.2.2.1:8080" to 200.0),
            )
        )

        assertEquals(listOf("1.1.1.2:8080", "2.2.2.1:8080", "1.1.1.1:8080"), candidates)
    }

    @Test
    fun `orders each round by response time, so the quickest node leads`() {
        val candidates = Gadget.entryIpCandidates(
            addrs(
                listOf("1.1.1.1:8080" to 900.0),
                listOf("2.2.2.2:8080" to 100.0),
            )
        )

        assertEquals(listOf("2.2.2.2:8080", "1.1.1.1:8080"), candidates)
    }

    @Test
    fun `keeps private and unusable addresses out while preserving the rest`() {
        val candidates = Gadget.entryIpCandidates(
            addrs(
                listOf(
                    "125.229.161.122:8080" to 1.85E7,
                    "192.168.5.4:8080" to 2.81478208946270E14,
                    "10.8.0.2:8080" to 2.81476939120639E14,
                ),
                listOf("115.196.228.69:8002" to 1.88E8),
            )
        )

        assertEquals(listOf("125.229.161.122:8080", "115.196.228.69:8002"), candidates)
    }

    @Test
    fun `keeps a public IPv6 address and its port`() {
        val candidates = Gadget.entryIpCandidates(
            addrs(listOf("[240e:391:e00:169:1458:aa58:c381:5c85]:8081" to 3.96))
        )

        assertEquals(listOf("[240e:391:e00:169:1458:aa58:c381:5c85]:8081"), candidates)
    }

    @Test
    fun `lists an address claimed by two nodes once, at its first position`() {
        val candidates = Gadget.entryIpCandidates(
            addrs(
                listOf("1.1.1.1:8080" to 10.0, "9.9.9.9:8080" to 20.0),
                listOf("9.9.9.9:8080" to 10.0),
            )
        )

        assertEquals(listOf("1.1.1.1:8080", "9.9.9.9:8080"), candidates)
    }

    @Test
    fun `returns nothing rather than throwing on malformed input`() {
        assertTrue(Gadget.entryIpCandidates(emptyList<Any>()).isEmpty())
        assertTrue(Gadget.entryIpCandidates(listOf("not a node", 42, null)).isEmpty())
        assertEquals(listOf("1.1.1.1:8080"), Gadget.entryIpCandidates(listOf(listOf(listOf("1.1.1.1:8080")))))
        assertEquals(listOf("1.1.1.1:8080"),
            Gadget.entryIpCandidates(listOf(listOf(listOf("1.1.1.1:8080", "not a number")))))
    }

    @Test
    fun `filters before taking two public IPs per node`() {
        val nodes = addrs(listOf(
            "100.64.1.1:8080" to 1.0,
            "198.18.0.1:8080" to 2.0,
            "26.26.26.1:8080" to 3.0,
            "[2001::ffff:ffff:ffff:fff1]:8080" to 4.0,
            "1.1.1.1:8080" to 5.0,
            "[2606:4700:4700::1111]:8080" to 6.0,
            "8.8.8.8:8080" to 7.0,
        ))
        assertEquals(listOf("1.1.1.1:8080", "[2606:4700:4700::1111]:8080"),
            Gadget.entryIpCandidates(nodes, addressesPerNode = 2))
        assertEquals(3, Gadget.entryIpCandidates(nodes).size)
    }

    @Test
    fun `retains two ranked fallback IPs for each private-only node`() {
        val nodes = addrs(
            listOf("192.168.1.1:8080" to 30.0, "100.64.1.1:8080" to 10.0, "[fd00::1]:8080" to 20.0),
            listOf("10.1.1.1:8080" to 1.0, "1.1.1.1:8080" to 40.0),
        )
        val expected = listOf("100.64.1.1:8080", "1.1.1.1:8080", "[fd00::1]:8080")
        assertEquals(expected, Gadget.entryIpCandidates(nodes, addressesPerNode = 2))
        assertEquals(expected, Gadget.entryIpCandidates(nodes))
    }

    @Test
    fun `never fills a public node's second slot with a private IP`() {
        assertEquals(listOf("1.1.1.1:8080"), Gadget.entryIpCandidates(addrs(
            listOf("100.64.1.1:8080" to 1.0, "1.1.1.1:8080" to 2.0)
        ), addressesPerNode = 2))
    }

    @Test
    fun `classifies expanded VPN IPv6 and mapped private IPv4 as non-public`() {
        assertTrue(!Gadget.isValidPublicIpAddress("[2001:0000:0000:0000:ffff:ffff:ffff:fff3]:8080"))
        assertTrue(!Gadget.isValidPublicIpAddress("[::ffff:c0a8:101]:8080"))
        assertTrue(!Gadget.isValidPublicIpAddress("198.51.100.1:8080"))
        assertTrue(Gadget.isValidPublicIpAddress("[240e:391:edd:26d0::1]:8002"))
    }
}
