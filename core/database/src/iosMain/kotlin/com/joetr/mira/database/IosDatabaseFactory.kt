package com.joetr.mira.database

import app.cash.sqldelight.async.coroutines.synchronous
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlSchema
import app.cash.sqldelight.driver.native.NativeSqliteDriver
import com.joetr.mira.platform.localDatabaseFileName

public actual suspend fun createSqlDriver(schema: SqlSchema<QueryResult.AsyncValue<Unit>>): SqlDriver {
    val driver = NativeSqliteDriver(
        schema = schema.synchronous(),
        name = localDatabaseFileName(),
    )
    driver.execPragma("PRAGMA journal_mode=WAL")
    driver.execPragma("PRAGMA busy_timeout=30000")
    return driver
}
