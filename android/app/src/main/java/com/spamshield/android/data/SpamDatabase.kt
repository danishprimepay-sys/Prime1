package com.spamshield.android.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/** App-private storage: block/allow lists, community cache, call log and SMS quarantine. */
class SpamDatabase(context: Context) : SQLiteOpenHelper(context, NAME, null, VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE $T_LIST (
                _id INTEGER PRIMARY KEY AUTOINCREMENT,
                kind TEXT NOT NULL,
                pattern TEXT NOT NULL,
                added_at INTEGER NOT NULL,
                UNIQUE(kind, pattern))"""
        )
        db.execSQL("CREATE TABLE $T_COMMUNITY (number TEXT PRIMARY KEY, score REAL NOT NULL) WITHOUT ROWID")
        db.execSQL(
            """CREATE TABLE $T_CALL_LOG (
                _id INTEGER PRIMARY KEY AUTOINCREMENT,
                number TEXT,
                at INTEGER NOT NULL,
                action TEXT NOT NULL,
                score REAL NOT NULL,
                reasons TEXT NOT NULL)"""
        )
        db.execSQL(
            """CREATE TABLE $T_QUARANTINE (
                _id INTEGER PRIMARY KEY AUTOINCREMENT,
                address TEXT,
                body TEXT NOT NULL,
                sent_at INTEGER NOT NULL,
                received_at INTEGER NOT NULL,
                sub_id INTEGER NOT NULL,
                score REAL NOT NULL,
                reasons TEXT NOT NULL)"""
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Version 1 is the only schema so far.
    }

    companion object {
        const val NAME = "spamshield.db"
        const val VERSION = 1
        const val T_LIST = "list_entry"
        const val T_COMMUNITY = "community"
        const val T_CALL_LOG = "call_log"
        const val T_QUARANTINE = "quarantine"
    }
}
