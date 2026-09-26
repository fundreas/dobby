package io.dobby.android.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * The panel's database.
 *
 * Dobby's two older stores are both key-value files, and both are right for what they hold:
 * [io.dobby.android.Settings] is the panel's own handful of enums, and `SockConfigStore` is a
 * namespace a Sock writes its own strings into. Neither can answer the question the Socks tab
 * asks — *which Socks are switched off, and when did that happen* — without the caller already
 * knowing every Sock id to look up, because a preferences file has keys and no rows.
 *
 * This has rows. One per Sock that has ever been switched, which is the table the Socks screen
 * and the Commands screen both read, and the reason a Sock added next month is on by default
 * without anybody writing a migration: **absence means enabled**. Nothing is ever written for a
 * Sock nobody has touched.
 *
 * Plain `SQLiteOpenHelper` rather than Room: Room is a KSP processor, a compiler plugin and two
 * artifacts, bought here for two tables with four columns between them and no relations. The
 * schema below is the whole of what Room would have generated.
 */
class DobbyDatabase(context: Context) :
    SQLiteOpenHelper(context.applicationContext, NAME, null, VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE $TABLE_SOCK (
                $COLUMN_SOCK_ID TEXT NOT NULL PRIMARY KEY,
                $COLUMN_ENABLED INTEGER NOT NULL,
                $COLUMN_CHANGED_AT INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE $TABLE_PREFERENCE (
                $COLUMN_KEY TEXT NOT NULL PRIMARY KEY,
                $COLUMN_VALUE TEXT NOT NULL
            )
            """.trimIndent(),
        )
    }

    /**
     * There is no version 2 yet, and this is what happens when there is.
     *
     * Dropping is safe for exactly one reason and it is worth writing down: everything in here
     * is a preference somebody can set again in ten seconds, and nothing else in the panel
     * reads it. The day a table holds something that is not recoverable — a memo, a recording —
     * this method has to become a real migration.
     */
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS $TABLE_SOCK")
        db.execSQL("DROP TABLE IF EXISTS $TABLE_PREFERENCE")
        onCreate(db)
    }

    /** Every Sock that has ever been switched, and which way. Absent ids are enabled. */
    fun switchedSocks(): Map<String, Boolean> {
        val rows = mutableMapOf<String, Boolean>()
        readableDatabase.query(
            TABLE_SOCK,
            arrayOf(COLUMN_SOCK_ID, COLUMN_ENABLED),
            null,
            null,
            null,
            null,
            null,
        ).use { cursor ->
            while (cursor.moveToNext()) {
                rows[cursor.getString(0)] = cursor.getInt(1) != 0
            }
        }
        return rows
    }

    fun putSock(sockId: String, enabled: Boolean) {
        val values = ContentValues().apply {
            put(COLUMN_SOCK_ID, sockId)
            put(COLUMN_ENABLED, if (enabled) 1 else 0)
            put(COLUMN_CHANGED_AT, System.currentTimeMillis())
        }
        writableDatabase.insertWithOnConflict(
            TABLE_SOCK,
            null,
            values,
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    fun preference(key: String): String? =
        readableDatabase.query(
            TABLE_PREFERENCE,
            arrayOf(COLUMN_VALUE),
            "$COLUMN_KEY = ?",
            arrayOf(key),
            null,
            null,
            null,
        ).use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }

    fun putPreference(key: String, value: String) {
        val values = ContentValues().apply {
            put(COLUMN_KEY, key)
            put(COLUMN_VALUE, value)
        }
        writableDatabase.insertWithOnConflict(
            TABLE_PREFERENCE,
            null,
            values,
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    companion object {
        const val NAME: String = "dobby.db"
        const val VERSION: Int = 1

        private const val TABLE_SOCK = "sock"
        private const val COLUMN_SOCK_ID = "sock_id"
        private const val COLUMN_ENABLED = "enabled"
        private const val COLUMN_CHANGED_AT = "changed_at"

        private const val TABLE_PREFERENCE = "preference"
        private const val COLUMN_KEY = "key"
        private const val COLUMN_VALUE = "value"
    }
}
