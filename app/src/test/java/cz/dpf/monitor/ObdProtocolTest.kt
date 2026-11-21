package cz.dpf.monitor

import org.junit.Assert.*
import org.junit.Test

class ObdProtocolTest {
    @Test fun realActiveFirstFrame() {
        val r = ObdProtocol.parse("018B", "7E81009418B5303FF00\r\r>")
        assertTrue(r.valid)
        assertFalse(r.complete)
        val v = ObdProtocol.decode("018B", "7E81009418B5303FF00\r\r>", 1000L)!!
        assertEquals(RegenFlag.ACTIVE, v.flag)
        assertEquals(100.0, v.triggerPercent!!, 0.0001)
        assertTrue(v.partial)
    }
    @Test fun measuredScaleFromUserLog() {
        assertEquals(132.0, ObdProtocol.decode("22208D", "7E80562208D0528", 0L)!!.number!!, 0.00001)
        assertEquals(768.0 * .64, ObdProtocol.decode("22207D", "7E80562207D0300", 0L)!!.number!!, 0.00001)
        assertEquals(140.15, ObdProtocol.decode("22449B", "7E80562449B1025", 0L)!!.number!!, 0.0001)
    }
    @Test fun nrcPendingThenPositive() {
        val r = ObdProtocol.parse("22208B", "7E8037F2278\r7E80562208B0960")
        assertTrue(r.valid)
        assertEquals(24.0, ObdProtocol.decode("22208B", r.raw, 1)!!.number!!, .00001)
    }
    @Test fun incompleteReplyNeverGeneratesVin() {
        val part = "7E8101462F190544D42"
        assertNull(ObdProtocol.vin(part, "22F190"))
    }
    @Test fun vinRequiresExact17Characters() {
        val vin = "TMBJK7NE0K0123456"
        assertEquals(17, vin.length)
        val hex = vin.toByteArray(Charsets.US_ASCII).joinToString("") { "%02X".format(it) }
        assertEquals(vin, ObdProtocol.vin("62F190" + hex, "22F190"))
    }
    @Test fun completeIsoTp() {
        val frames = "7E81009418B5303FF00\r7E82100000000000000"
        val result = ObdProtocol.parse("018B", frames)
        assertTrue(result.complete)
        assertEquals(RegenFlag.ACTIVE, ObdProtocol.decode("018B", frames, 1L)!!.flag)
    }
}

class RegenEngineTest {
    private fun sample(cmd: String, at: Long, n: Double? = null, flag: RegenFlag? = null) =
        SensorValue(cmd, at, number = n, flag = flag, triggerPercent = if (cmd == "018B") 100.0 else null)

    @Test fun confirmsCompletionAfterBothCountersReset() {
        val e = RegenEngine()
        val t = 1000000L
        e.consume(sample("22208D", t, 187.3))
        e.consume(sample("222089", t, 23980.0))
        e.consume(sample("018B", t, flag = RegenFlag.INACTIVE))
        e.consume(sample("018B", t + 1500, flag = RegenFlag.ACTIVE))
        assertEquals(1, e.consume(sample("018B", t + 3000, flag = RegenFlag.ACTIVE)).size)
        assertEquals(RegenPhase.ACTIVE, e.phase)
        e.consume(sample("018B", t + 1035000, flag = RegenFlag.PASSIVE))
        e.consume(sample("018B", t + 1036500, flag = RegenFlag.PASSIVE))
        assertEquals(RegenPhase.COOL_DOWN, e.phase)
        assertTrue(e.consume(sample("22208D", t + 1040000, 0.1)).isEmpty())
        val done = e.consume(sample("222089", t + 1041000, 14.0))
        assertEquals(EventKind.COMPLETE, done.single().kind)
        assertEquals(RegenPhase.COMPLETED, e.phase)
    }
    @Test fun noCompletionWithoutResets() {
        val e = RegenEngine()
        val t = 1000000L
        e.consume(sample("22208D", t, 180.0))
        e.consume(sample("222089", t, 20000.0))
        e.consume(sample("018B", t, flag = RegenFlag.ACTIVE))
        e.consume(sample("018B", t + 1500, flag = RegenFlag.ACTIVE))
        e.consume(sample("018B", t + 6000, flag = RegenFlag.INACTIVE))
        e.consume(sample("018B", t + 7500, flag = RegenFlag.INACTIVE))
        e.consume(sample("22208D", t + 100000, 181.0))
        val result = e.consume(sample("222089", t + 101000, 20100.0))
        assertEquals(EventKind.INTERRUPT, result.single().kind)
    }
    @Test fun disconnectionIsUnresolvedNotInterrupted() {
        val e = RegenEngine()
        e.consume(sample("018B", 1_000_000, flag = RegenFlag.ACTIVE))
        e.consume(sample("018B", 1_001_500, flag = RegenFlag.ACTIVE))
        assertEquals(EventKind.UNRESOLVED, e.onConnectionLost()!!.kind)
        assertEquals(RegenPhase.UNRESOLVED, e.phase)
        assertEquals(EventKind.NEXT_TRIP_WARNING, e.onTripVerified()!!.kind)
        assertNull(e.onTripVerified())
    }
    @Test fun estimateFinishUsesKnownReference() {
        val e = RegenEngine()
        val t = 1_000_000L
        e.consume(sample("018B", t, flag = RegenFlag.ACTIVE))
        e.consume(sample("018B", t + 1500, flag = RegenFlag.ACTIVE))
        e.consume(sample("22207D", t + 100000, 100.0))
        assertEquals(936L, e.snapshot(t + 100100).remainingSeconds)
    }
}
