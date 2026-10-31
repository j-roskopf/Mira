package com.joetr.mira.database

import app.cash.sqldelight.async.coroutines.synchronous
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlSchema
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.joetr.mira.platform.desktopDataDirectoryName
import com.joetr.mira.platform.localDatabaseFileName
import java.io.File
import java.util.Properties

public actual suspend fun createSqlDriver(schema: SqlSchema<QueryResult.AsyncValue<Unit>>): SqlDriver {
    val root = File(System.getProperty("user.home"), desktopDataDirectoryName()).also { it.mkdirs() }
    val dbFile = File(root, localDatabaseFileName())
    val properties = Properties().apply {
        setProperty("busy_timeout", "10000")
        setProperty("journal_mode", "WAL")
        setProperty("synchronous", "NORMAL")
    }
    return JdbcSqliteDriver(
        url = "jdbc:sqlite:${dbFile.absolutePath}",
        properties = properties,
        schema = schema.synchronous(),
    )
}
