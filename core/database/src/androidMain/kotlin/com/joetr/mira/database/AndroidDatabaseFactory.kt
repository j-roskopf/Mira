package com.joetr.mira.database

import app.cash.sqldelight.async.coroutines.synchronous
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlSchema
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import com.joetr.mira.platform.AndroidContextHolder
import com.joetr.mira.platform.localDatabaseFileName

public actual suspend fun createSqlDriver(schema: SqlSchema<QueryResult.AsyncValue<Unit>>): SqlDriver {
    val context = AndroidContextHolder.application
    return AndroidSqliteDriver(
        schema = schema.synchronous(),
        context = context,
        name = localDatabaseFileName(),
    )
}
