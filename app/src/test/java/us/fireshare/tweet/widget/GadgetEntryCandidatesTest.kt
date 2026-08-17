package us.fireshare.tweet.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The entry candidate list decides how a cold deep link finds a node to talk
 * to, and findEntryIP probes the list one address at a time. These pin the
 * ordering that keeps consecutive probes on different machines.
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
        assertTrue(Gadget.entryIpCandidates(listOf(listOf(listOf("1.1.1.1:8080")))).isEmpty())
        assertTrue(
            Gadget.entryIpCandidates(listOf(listOf(listOf("1.1.1.1:8080", "not a number")))).isEmpty()
        )
    }
}
