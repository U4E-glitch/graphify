package com.dheyab.qiyas.integration

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.dheyab.qiyas.data.db.MIGRATION_1_2
import com.dheyab.qiyas.data.db.QiyasDatabase
import com.google.common.truth.Truth.assertThat
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Destructive migrations are forbidden (spec §2): builds a REAL v1 database
 * from the exported schemas/1.json DDL, then opens it through Room with
 * MIGRATION_1_2 — Room validates the migrated schema and the data must survive.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = PlainTestApp::class)
class MigrationTest {

    private fun schemaFile(version: Int): File = listOf(
        File("schemas/com.dheyab.qiyas.data.db.QiyasDatabase/$version.json"),
        File("app/schemas/com.dheyab.qiyas.data.db.QiyasDatabase/$version.json"),
    ).firstOrNull { it.exists() } ?: error("Exported schema $version.json not found")

    /** All DDL from an exported Room schema: tables, indices, and the identity-hash master row. */
    private fun ddlFromSchema(version: Int): List<String> {
        val database = Json.parseToJsonElement(schemaFile(version).readText())
            .jsonObject.getValue("database").jsonObject
        val statements = mutableListOf<String>()
        for (entity in database.getValue("entities").jsonArray) {
            val obj = entity.jsonObject
            val tableName = obj.getValue("tableName").jsonPrimitive.content
            statements += obj.getValue("createSql").jsonPrimitive.content
                .replace("\${TABLE_NAME}", tableName)
            obj["indices"]?.jsonArray?.forEach { index ->
                statements += index.jsonObject.getValue("createSql").jsonPrimitive.content
                    .replace("\${TABLE_NAME}", tableName)
            }
        }
        for (query in database.getValue("setupQueries").jsonArray) {
            statements += query.jsonPrimitive.content
        }
        return statements
    }

    @Test
    fun migrate1To2_preservesReadingsAndAddsPhotoColumn() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dbName = "migration-test.db"
        val dbFile = context.getDatabasePath(dbName).apply { parentFile?.mkdirs(); delete() }

        // Create a genuine v1 database with a reading in it.
        SQLiteDatabase.openOrCreateDatabase(dbFile, null).use { raw ->
            ddlFromSchema(1).forEach(raw::execSQL)
            raw.version = 1
            raw.execSQL("INSERT INTO profiles(id, name, created_at) VALUES(1, 'Default', 0)")
            raw.execSQL(
                "INSERT INTO readings(profile_id, type, measured_at, created_at, glucose_mgdl, " +
                    "glucose_context, systolic, diastolic, pulse, bp_context, note, zone) " +
                    "VALUES(1, 'GLUCOSE', 1000, 1000, 100.0, 'FASTING', NULL, NULL, NULL, NULL, NULL, 'IN_RANGE')"
            )
        }

        // Open through Room v2 — the migration must run and schema validation must pass.
        val db = Room.databaseBuilder(context, QiyasDatabase::class.java, dbName)
            .addMigrations(MIGRATION_1_2)
            .allowMainThreadQueries()
            .build()
        try {
            runBlocking {
                val reading = db.readingDao().getById(1)
                assertThat(reading).isNotNull()
                assertThat(reading!!.glucoseMgdl).isEqualTo(100f)
                assertThat(reading.zone).isEqualTo("IN_RANGE")
                assertThat(reading.photoPath).isNull() // new v2 column, null for old rows
            }
        } finally {
            db.close()
        }
    }
}
