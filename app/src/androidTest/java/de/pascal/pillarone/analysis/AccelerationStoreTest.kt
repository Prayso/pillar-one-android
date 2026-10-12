package de.pascal.pillarone.analysis

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class AccelerationStoreTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var store: AccelerationStore

    @Before fun setUp() {
        context.deleteDatabase("acceleration-test.db")
        store = AccelerationStore(context, "acceleration-test.db")
    }

    @After fun tearDown() {
        store.close()
        context.deleteDatabase("acceleration-test.db")
    }

    private fun result(key: String, source: MeasurementSource, start: Long = 1_000, end: Long = 6_000) =
        CompletedMeasurement(key, AccelerationInterval.ZERO_TO_HUNDRED, source, start, end, end - start,
            MeasurementLocation(50.0, 8.0, 4f), if (source == MeasurementSource.CAR) "display" else null,
            endLocation = MeasurementLocation(50.01, 8.01, 7f))

    @Test fun savesBothSourcesAndSurvivesReopeningWithoutDuplicates() {
        store.save(result("gps", MeasurementSource.GPS), 1_000_000, "Europe/Berlin", "recording")
        store.save(result("car", MeasurementSource.CAR, 1_100, 6_500), 1_000_000, "Europe/Berlin", "recording")
        store.close()
        store = AccelerationStore(context, "acceleration-test.db")
        store.save(result("car", MeasurementSource.CAR, 1_100, 6_500), 1_000_000, "Europe/Berlin", "recording")
        val record = store.records().single()
        assertEquals(1_001_000L, record.startedAt)
        assertEquals("Europe/Berlin", record.zoneId)
        assertEquals(5_000L, record.gpsDurationMs)
        assertEquals(5_400L, record.carDurationMs)
        assertEquals(50.0, record.location!!.latitude, 0.0)
        assertEquals("display", record.deviceId)
        assertEquals(50.01, record.endLocation!!.latitude, 0.0)
    }

    @Test fun unrelatedOrAmbiguousRunsStaySeparateAndNewestIsFirst() {
        store.save(result("gps1", MeasurementSource.GPS), 0, "UTC", "recording")
        store.save(result("gps2", MeasurementSource.GPS, 1_200, 6_200), 0, "UTC", "recording")
        store.save(result("car", MeasurementSource.CAR, 1_100, 6_100), 0, "UTC", "recording")
        store.save(result("later", MeasurementSource.CAR, 20_000, 25_000), 0, "UTC", "recording")
        assertEquals(4, store.records().size)
        assertEquals(20_000L, store.records().first().startedAt)
        assertNull(store.records().first().gpsDurationMs)
    }

    @Test fun failedWriteDoesNotLeaveAnEmptyMeasurement() {
        val invalid = result("invalid", MeasurementSource.GPS).copy(durationMs = 0)
        assertThrows(android.database.sqlite.SQLiteException::class.java) { store.save(invalid, 0, "UTC", "recording") }
        assertTrue(store.records().isEmpty())
    }

    @Test fun distinctRecordingSessionsAreNeverPaired() {
        store.save(result("gps", MeasurementSource.GPS), 0, "UTC", "first")
        store.save(result("car", MeasurementSource.CAR), 0, "UTC", "second")
        assertEquals(2, store.records().size)
    }

    @Test fun deleteRemovesBothSourcesAndLocationsPermanentlyButKeepsOtherRuns() {
        store.save(result("gps", MeasurementSource.GPS), 0, "UTC", "first")
        store.save(result("car", MeasurementSource.CAR), 0, "UTC", "first")
        store.save(result("other", MeasurementSource.CAR, 20_000, 25_000), 0, "UTC", "second")
        val id = store.records().last().id
        store.delete(id)
        store.close()
        store = AccelerationStore(context, "acceleration-test.db")
        assertEquals(20_000L, store.records().single().startedAt)
        store.readableDatabase.rawQuery("SELECT COUNT(*) FROM results WHERE record_id = ?",
            arrayOf(id.toString())).use {
            assertTrue(it.moveToFirst())
            assertEquals(0, it.getInt(0))
        }
    }

    @Test fun detailsKeepSourceSpecificTimestampsAndLocations() {
        store.save(result("gps", MeasurementSource.GPS), 1_000_000, "UTC", "recording")
        store.save(result("car", MeasurementSource.CAR, 1_100, 6_500).copy(
            location = MeasurementLocation(51.0, 9.0, 8f),
            endLocation = MeasurementLocation(51.1, 9.1, 9f)),
            1_000_000, "UTC", "recording")
        val results = store.records().single().results
        assertEquals(2, results.size)
        val gps = results.single { it.source == MeasurementSource.GPS }
        val car = results.single { it.source == MeasurementSource.CAR }
        assertEquals(1_001_000L, gps.startedAt)
        assertEquals(1_006_000L, gps.endedAt)
        assertEquals(50.0, gps.location!!.latitude, 0.0)
        assertEquals(1_001_100L, car.startedAt)
        assertEquals(1_006_500L, car.endedAt)
        assertEquals(51.0, car.location!!.latitude, 0.0)
        assertEquals(51.1, car.endLocation!!.latitude, 0.0)
        assertEquals("display", car.deviceId)
    }

    @Test fun upgradeKeepsExistingMeasurementsWithoutInventingAnEndpoint() {
        store.save(result("old", MeasurementSource.CAR), 0, "UTC", "old-recording")
        val id = store.records().single().id
        store.close()
        val db = context.openOrCreateDatabase("acceleration-test.db", 0, null)
        // Rebuild the results table with the schema used by the first release.
        db.execSQL("ALTER TABLE results RENAME TO results_v2")
        db.execSQL("""
            CREATE TABLE results (
                measurement_key TEXT PRIMARY KEY, record_id INTEGER NOT NULL,
                source TEXT NOT NULL, started_at INTEGER NOT NULL, ended_at INTEGER NOT NULL,
                duration_ms INTEGER NOT NULL, latitude REAL, longitude REAL, accuracy REAL,
                device_id TEXT, UNIQUE(record_id, source)
            )
        """.trimIndent())
        db.execSQL("""
            INSERT INTO results SELECT measurement_key, record_id, source, started_at,
                ended_at, duration_ms, latitude, longitude, accuracy, device_id FROM results_v2
        """.trimIndent())
        db.execSQL("DROP TABLE results_v2")
        db.version = 1
        db.close()
        store = AccelerationStore(context, "acceleration-test.db")
        val old = store.records().single()
        assertEquals(id, old.id)
        assertEquals(5_000L, old.carDurationMs)
        assertNotNull(old.location)
        assertNull(old.endLocation)
        store.save(result("new", MeasurementSource.CAR, 20_000, 25_000), 0, "UTC", "new-recording")
        assertNotNull(store.records().first().endLocation)
    }
}
