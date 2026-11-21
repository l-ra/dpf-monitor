package cz.dpf.monitor

import java.util.Locale

enum class RegenFlag { INACTIVE, PASSIVE, ACTIVE, UNKNOWN }

data class DiagnosticReply(
    val command: String,
    val bytes: List<Int> = emptyList(),
    val complete: Boolean = false,
    val negativeCode: Int? = null,
    val raw: String = ""
) {
    val valid get() = bytes.isNotEmpty() && negativeCode == null
}

data class SensorValue(
    val command: String,
    val timestamp: Long,
    val number: Double? = null,
    val flag: RegenFlag? = null,
    val triggerPercent: Double? = null,
    val partial: Boolean = false,
    val rawHex: String = ""
)

object ObdProtocol {
    // Parses ELM327 text with headers enabled (ATH1), auto CAN formatting (ATCAF1).
    // Handles raw ISO-TP single, first and consecutive frames. A first frame without
    // subsequent data is intentionally marked incomplete, rather than fabricated.
    fun parse(command: String, raw: String): DiagnosticReply {
        val cmd = command.uppercase(Locale.ROOT).trim()
        val prefix = when {
            cmd.startsWith("22") && cmd.length == 6 -> "62" + cmd.substring(2)
            cmd.startsWith("01") && cmd.length == 4 -> "41" + cmd.substring(2)
            cmd.startsWith("09") && cmd.length == 4 -> "49" + cmd.substring(2)
            else -> return DiagnosticReply(cmd, raw = raw)
        }
        var candidate: List<Int>? = null
        var complete = false
        var assembly = mutableListOf<Int>()
        var total = 0
        var expectedSn = 1
        var negative: Int? = null

        fun add(bytes: List<Int>, finished: Boolean) {
            val hex = bytes.joinToString("") { "%02X".format(it) }
            if (hex.startsWith(prefix) && (candidate == null || finished && !complete)) {
                candidate = bytes
                complete = finished
            } else if (bytes.size >= 3 && bytes[0] == 0x7F) {
                negative = bytes[2]
            }
        }
        for (line in raw.split('\r', '\n')) {
            val compact = line.substringBefore('>').filterNot { it.isWhitespace() }.uppercase(Locale.ROOT)
            if (compact.isEmpty() || compact == cmd || !compact.all { it in "0123456789ABCDEF" }) continue
            // ELM CAN header is exactly 3 hex digits for 11 bit addressing.
            val frame = if (compact.length >= 5 && compact.substring(0, 3) == "7E8") compact.substring(3)
                        else if (compact.startsWith(prefix) || compact.startsWith("7F")) {
                            add(hexBytes(compact), true)
                            continue
                        } else continue
            val b = hexBytes(frame)
            if (b.isEmpty()) continue
            when (b[0] shr 4) {
                0 -> {
                    val len = b[0] and 0x0F
                    if (len > 0 && b.size >= len + 1) add(b.subList(1, 1 + len), true)
                }
                1 -> if (b.size >= 3) {
                    if (total > 0) add(assembly.take(total), false)
                    total = ((b[0] and 0x0F) shl 8) or b[1]
                    assembly = b.drop(2).toMutableList()
                    expectedSn = 1
                    if (assembly.size >= total) {
                        add(assembly.take(total), true)
                        total = 0
                    }
                }
                2 -> if (total > 0 && (b[0] and 0x0F) == expectedSn) {
                    assembly.addAll(b.drop(1))
                    expectedSn = (expectedSn + 1) and 0x0F
                    if (assembly.size >= total) {
                        add(assembly.take(total), true)
                        total = 0
                    }
                }
            }
        }
        if (total > 0) add(assembly, false)
        return DiagnosticReply(cmd, candidate ?: emptyList(), complete, if (candidate == null) negative else null, raw)
    }

    fun decode(command: String, raw: String, at: Long): SensorValue? {
        val p = parse(command, raw)
        if (!p.valid) return null
        val b = p.bytes
        val data = when {
            command.startsWith("22") && b.size >= 3 -> b.drop(3)
            command == "018B" && b.size >= 4 -> b.drop(2)
            command == "010C" && b.size >= 4 -> b.drop(2)
            else -> return null
        }
        val hex = data.joinToString("") { "%02X".format(it) }
        if (command == "018B") {
            if (data.size < 3 || data[0] and 1 == 0) return null
            val flag = when (data[1] and 3) {
                3 -> RegenFlag.ACTIVE
                1 -> RegenFlag.PASSIVE
                0 -> RegenFlag.INACTIVE
                else -> RegenFlag.UNKNOWN
            }
            val trigger = if (data[0] and 0x10 != 0) data[2] * 100.0 / 255.0 else null
            return SensorValue(command, at, flag = flag, triggerPercent = trigger,
                partial = !p.complete, rawHex = hex)
        }
        if (data.size < 2) return null
        val u = data[0] * 256 + data[1]
        val number: Double? = when (command) {
            "22208D" -> u / 10.0                       // confirmed against real trip
            "22207D" -> u * 0.64                       // seconds since active regen began
            "222089" -> u * 7.0                        // approx seconds since last regen
            "22208B" -> u / 100.0                      // calculated soot grams
            "22208C" -> (if (u >= 32768) u - 65536 else u) / 100.0
            "22449A", "22449B" -> u / 10.0 - 273.15
            "220809" -> u.toDouble()                    // raw 0x0287 while active
            "010C" -> u / 4.0                          // RPM
            else -> null
        }
        return SensorValue(command, at, number = number, partial = !p.complete, rawHex = hex)
    }

    fun vin(raw: String, command: String): String? {
        val p = parse(command, raw)
        if (!p.valid || !p.complete) return null // fail closed if clone truncates ISO-TP
        val b = p.bytes
        val payload = when {
            command == "0902" && b.size >= 3 && b.take(3) == listOf(0x49, 0x02, 0x01) -> b.drop(3)
            command == "22F190" && b.size >= 3 && b.take(3) == listOf(0x62, 0xF1, 0x90) -> b.drop(3)
            else -> return null
        }
        val vin = payload.take(17).map { it.toChar() }.joinToString("")
        return vin.takeIf { it.length == 17 && it.all { c -> c in '0'..'9' || c in 'A'..'Z' && c !in "IOQ" } }
    }

    private fun hexBytes(hex: String): List<Int> =
        if (hex.length % 2 != 0) emptyList()
        else hex.chunked(2).mapNotNull { it.toIntOrNull(16) }
}
