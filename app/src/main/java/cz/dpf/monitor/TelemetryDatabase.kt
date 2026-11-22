package cz.dpf.monitor

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.database.Cursor
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

data class ChartPoint(val timeMs: Long, val value: Double)

/**
 * Local, private telemetry store. No network calls and no storage permission.
 * Every successful and unsuccessful ECU reading is retained for 30 days.
 * Full ELM exchanges and events are retained for the same period.
 */
class TelemetryDatabase private constructor(context: Context) :
    SQLiteOpenHelper(context.applicationContext, "dpf-telemetry.db", null, 1) {

    companion object {
        const val RETENTION_DAYS = 30L
        const val RETENTION_MS = RETENTION_DAYS * 24 * 60 * 60 * 1000L
        @Volatile private var instance: TelemetryDatabase? = null
        fun get(context: Context): TelemetryDatabase =
            instance ?: synchronized(this) {
                instance ?: TelemetryDatabase(context).also { instance = it }
            }
    }

    private var lastPurgeMs = 0L

    override fun onConfigure(db: SQLiteDatabase) {
        db.setForeignKeyConstraintsEnabled(true)
        db.enableWriteAheadLogging()
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE samples (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            ts INTEGER NOT NULL,
            vin TEXT,
            command TEXT NOT NULL,
            value REAL,
            unit TEXT,
            quality TEXT NOT NULL,
            flag TEXT,
            trigger_pct REAL,
            payload_hex TEXT,
            reply TEXT
        )""")
        db.execSQL("CREATE INDEX sample_time ON samples(ts)")
        db.execSQL("CREATE INDEX sample_command_time ON samples(command, ts)")
        db.execSQL("""CREATE TABLE communications (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            ts INTEGER NOT NULL,
            message TEXT NOT NULL
        )""")
        db.execSQL("CREATE INDEX comm_time ON communications(ts)")
        db.execSQL("""CREATE TABLE events (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            ts INTEGER NOT NULL,
            kind TEXT NOT NULL,
            message TEXT NOT NULL
        )""")
        db.execSQL("CREATE INDEX event_time ON events(ts)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    @Synchronized
    fun recordSample(vin: String?, cmd: String, raw: String, sample: SensorValue?, timestamp: Long = System.currentTimeMillis()) {
        val db = writableDatabase
        val quality = when {
            sample == null -> "error"
            sample.partial -> "partial"
            else -> "complete"
        }
        db.compileStatement(
            "INSERT INTO samples(ts,vin,command,value,unit,quality,flag,trigger_pct,payload_hex,reply) VALUES(?,?,?,?,?,?,?,?,?,?)"
        ).use { stmt ->
            stmt.bindLong(1, timestamp)
            bindStringOrNull(stmt, 2, vin)
            stmt.bindString(3, cmd)
            if (sample?.number == null) stmt.bindNull(4) else stmt.bindDouble(4, sample.number)
            bindStringOrNull(stmt, 5, unitFor(cmd))
            stmt.bindString(6, quality)
            bindStringOrNull(stmt, 7, sample?.flag?.name)
            if (sample?.triggerPercent == null) stmt.bindNull(8) else stmt.bindDouble(8, sample.triggerPercent)
            bindStringOrNull(stmt, 9, sample?.rawHex)
            stmt.bindString(10, raw)
            stmt.executeInsert()
        }
        purgeIfDue(timestamp)
    }

    @Synchronized
    fun recordCommunication(message: String, timestamp: Long = System.currentTimeMillis()) {
        writableDatabase.compileStatement("INSERT INTO communications(ts,message) VALUES(?,?)").use {
            it.bindLong(1, timestamp)
            it.bindString(2, message)
            it.executeInsert()
        }
        purgeIfDue(timestamp)
    }

    @Synchronized
    fun recordEvent(kind: String, message: String, timestamp: Long = System.currentTimeMillis()) {
        writableDatabase.compileStatement("INSERT INTO events(ts,kind,message) VALUES(?,?,?)").use {
            it.bindLong(1, timestamp)
            it.bindString(2, kind)
            it.bindString(3, message)
            it.executeInsert()
        }
        purgeIfDue(timestamp)
    }

    @Synchronized
    fun purgeExpired(now: Long = System.currentTimeMillis()) {
        val cutoff = now - RETENTION_MS
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("samples", "ts < ?", arrayOf(cutoff.toString()))
            db.delete("communications", "ts < ?", arrayOf(cutoff.toString()))
            db.delete("events", "ts < ?", arrayOf(cutoff.toString()))
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        lastPurgeMs = now
    }

    private fun purgeIfDue(now: Long) {
        if (now - lastPurgeMs >= 3_600_000L) purgeExpired(now)
    }

    @Synchronized
    fun latestEvents(limit: Int = 15): JSONArray {
        val array = JSONArray()
        readableDatabase.rawQuery(
            "SELECT ts,kind,message FROM events ORDER BY ts DESC LIMIT ?",
            arrayOf(limit.coerceIn(1, 100).toString())
        ).use { c ->
            while (c.moveToNext()) {
                array.put(JSONObject().put("at", c.getLong(0)).put("type", c.getString(1)).put("message", c.getString(2)))
            }
        }
        return array
    }

    @Synchronized
    fun points(command: String, days: Int, maxPoints: Int = 220): List<ChartPoint> {
        val start = System.currentTimeMillis() - days.coerceIn(1, 30) * 86_400_000L
        val bucket = (days.coerceIn(1, 30) * 86_400_000L / maxPoints.coerceAtLeast(40)).coerceAtLeast(30_000L)
        val data = mutableListOf<ChartPoint>()
        val expression = if (command == "018B") "trigger_pct" else "value"
        readableDatabase.rawQuery(
            "SELECT CAST(ts / ? AS INTEGER) * ?, AVG($expression) FROM samples " +
                "WHERE command=? AND ts>=? AND $expression IS NOT NULL " +
                "GROUP BY CAST(ts / ? AS INTEGER) ORDER BY 1 ASC",
            arrayOf(bucket.toString(), bucket.toString(), command, start.toString(), bucket.toString())
        ).use { c ->
            while (c.moveToNext()) data.add(ChartPoint(c.getLong(0), c.getDouble(1)))
        }
        return data
    }

    /**
     * Streams all retained data to one ZIP using Android Storage Access Framework.
     * No temporary world-readable files. CSV records include raw ECU replies;
     * VIN and exact trip times may appear in an explicitly exported archive.
     */
    @Synchronized
    fun exportZip(stream: OutputStream, now: Long = System.currentTimeMillis()) {
        purgeExpired(now)
        val zip = ZipOutputStream(stream, StandardCharsets.UTF_8)
        try {
            writeCsv(zip, "telemetry.csv",
                "ts_utc,vin,command,value,unit,quality,flag,trigger_percent,payload_hex,reply",
                "SELECT ts,vin,command,value,unit,quality,flag,trigger_pct,payload_hex,reply FROM samples ORDER BY ts")
            writeCsv(zip, "events.csv", "ts_utc,kind,message",
                "SELECT ts,kind,message FROM events ORDER BY ts")
            zip.putNextEntry(ZipEntry("communication.txt"))
            readableDatabase.rawQuery("SELECT ts,message FROM communications ORDER BY ts", null).use { c ->
                while (c.moveToNext()) {
                    val line = iso(c.getLong(0)) + " " + c.getString(1) + "\n"
                    zip.write(line.toByteArray(StandardCharsets.UTF_8))
                }
            }
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("README.txt"))
            val note = "DPF Monitor diagnostic export\n" +
                "Retention: last 30 days, cut-off UTC " + iso(now - RETENTION_MS) + "\n" +
                "Sensitive: exported data may include VIN, vehicle telemetry and exact times.\n" +
                "CSV fields are UTF-8, RFC4180-style quoting. All times UTC.\n"
            zip.write(note.toByteArray(StandardCharsets.UTF_8))
            zip.closeEntry()
        } finally { zip.finish(); zip.flush() }
    }

    private fun writeCsv(zip: ZipOutputStream, entry: String, header: String, sql: String) {
        zip.putNextEntry(ZipEntry(entry))
        zip.write((header + "\r\n").toByteArray(StandardCharsets.UTF_8))
        readableDatabase.rawQuery(sql, null).use { c ->
            while (c.moveToNext()) {
                val columns = (0 until c.columnCount).map { index ->
                    if (c.isNull(index)) "" else if (index == 0) iso(c.getLong(index))
                    else c.getString(index)
                }
                val line = columns.joinToString(",") { csv(it) } + "\r\n"
                zip.write(line.toByteArray(StandardCharsets.UTF_8))
            }
        }
        zip.closeEntry()
    }

    private fun iso(time: Long): String = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ROOT)
        .apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(time))

    private fun csv(v: String) = "\"" + v.replace("\"", "\"\"") + "\""

    private fun bindStringOrNull(stmt: android.database.sqlite.SQLiteStatement, column: Int, value: String?) =
        if (value == null) stmt.bindNull(column) else stmt.bindString(column, value)

    private fun unitFor(command: String): String? = when (command) {
        "018B" -> "%"
        "22208D" -> "km"
        "222089", "22207D" -> "s"
        "22208B", "22208C" -> "g"
        "22449A", "22449B" -> "C"
        "010C" -> "rpm"
        else -> null
    }
}
