package de.pascal.pillarone.analysis

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class AccelerationStore(context: Context, databaseName: String = "acceleration.db") :
    SQLiteOpenHelper(context, databaseName, null, 2) {
    override fun onConfigure(db: SQLiteDatabase) {
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE measurements (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                interval TEXT NOT NULL,
                started_at INTEGER NOT NULL,
                zone_id TEXT NOT NULL,
                recording_id TEXT NOT NULL
            )
        """.trimIndent())
        db.execSQL("""
            CREATE TABLE results (
                measurement_key TEXT PRIMARY KEY,
                record_id INTEGER NOT NULL REFERENCES measurements(id) ON DELETE CASCADE,
                source TEXT NOT NULL,
                started_at INTEGER NOT NULL,
                ended_at INTEGER NOT NULL,
                duration_ms INTEGER NOT NULL CHECK(duration_ms > 0),
                latitude REAL,
                longitude REAL,
                accuracy REAL,
                end_latitude REAL,
                end_longitude REAL,
                end_accuracy REAL,
                device_id TEXT,
                UNIQUE(record_id, source)
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX results_start ON results(started_at)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion == 1 && newVersion == 2) {
            db.execSQL("ALTER TABLE results ADD COLUMN end_latitude REAL")
            db.execSQL("ALTER TABLE results ADD COLUMN end_longitude REAL")
            db.execSQL("ALTER TABLE results ADD COLUMN end_accuracy REAL")
        } else error("Unsupported acceleration database upgrade: $oldVersion to $newVersion")
    }

    @Synchronized
    fun save(result: CompletedMeasurement, epochOffsetMs: Long, zoneId: String, recordingId: String) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.rawQuery("SELECT 1 FROM results WHERE measurement_key = ?", arrayOf(result.key)).use {
                if (it.moveToFirst()) {
                    db.setTransactionSuccessful()
                    return
                }
            }
            val start = result.startElapsedMs + epochOffsetMs
            val end = result.endElapsedMs + epochOffsetMs
            var recordId: Long? = null
            db.rawQuery("""
                SELECT m.id, r.started_at, r.ended_at FROM measurements m
                JOIN results r ON r.record_id = m.id
                WHERE m.interval = ? AND m.recording_id = ? AND r.source != ?
                    AND r.started_at BETWEEN ? AND ?
                    AND r.ended_at BETWEEN ? AND ?
                    AND NOT EXISTS (SELECT 1 FROM results x WHERE x.record_id = m.id AND x.source = ?)
                ORDER BY ABS(r.started_at - ?) LIMIT 2
            """.trimIndent(), arrayOf(result.interval.name, recordingId, result.source.name,
                (start - 2_000).toString(), (start + 2_000).toString(),
                (end - 2_000).toString(), (end + 2_000).toString(), result.source.name, start.toString(),
            )).use { cursor ->
                if (cursor.moveToFirst()) {
                    val candidate = cursor.getLong(0)
                    // Ambiguous pairs stay separate rather than comparing different runs.
                    if (!cursor.moveToNext()) recordId = candidate
                }
            }
            val id = recordId ?: db.insertOrThrow("measurements", null, ContentValues().apply {
                put("interval", result.interval.name)
                put("started_at", start)
                put("zone_id", zoneId)
                put("recording_id", recordingId)
            })
            db.insertOrThrow("results", null, ContentValues().apply {
                put("measurement_key", result.key)
                put("record_id", id)
                put("source", result.source.name)
                put("started_at", start)
                put("ended_at", end)
                put("duration_ms", result.durationMs)
                result.location?.let {
                    put("latitude", it.latitude)
                    put("longitude", it.longitude)
                    put("accuracy", it.accuracyMetres)
                }
                result.endLocation?.let {
                    put("end_latitude", it.latitude)
                    put("end_longitude", it.longitude)
                    put("end_accuracy", it.accuracyMetres)
                }
                put("device_id", result.deviceId)
            })
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    @Synchronized
    fun records(): List<AccelerationRecord> {
        val records = mutableListOf<AccelerationRecord>()
        readableDatabase.rawQuery("""
            SELECT m.id, m.interval, m.started_at, m.zone_id,
                COALESCE(c.latitude, g.latitude), COALESCE(c.longitude, g.longitude),
                COALESCE(c.accuracy, g.accuracy), g.duration_ms, c.duration_ms, c.device_id,
                COALESCE(c.end_latitude, g.end_latitude), COALESCE(c.end_longitude, g.end_longitude),
                COALESCE(c.end_accuracy, g.end_accuracy)
            FROM measurements m
            LEFT JOIN results g ON g.record_id = m.id AND g.source = 'GPS'
            LEFT JOIN results c ON c.record_id = m.id AND c.source = 'CAR'
            ORDER BY m.started_at DESC, m.id DESC
        """.trimIndent(), null).use { cursor ->
            while (cursor.moveToNext()) {
                records += AccelerationRecord(
                    cursor.getLong(0), AccelerationInterval.valueOf(cursor.getString(1)),
                    cursor.getLong(2), cursor.getString(3),
                    if (cursor.isNull(4)) null else MeasurementLocation(
                        cursor.getDouble(4), cursor.getDouble(5), cursor.getFloat(6),
                    ),
                    if (cursor.isNull(7)) null else cursor.getLong(7),
                    if (cursor.isNull(8)) null else cursor.getLong(8),
                    if (cursor.isNull(9)) null else cursor.getString(9),
                    if (cursor.isNull(10)) null else MeasurementLocation(
                        cursor.getDouble(10), cursor.getDouble(11), cursor.getFloat(12),
                    ),
                )
            }
        }
        return records
    }
}
