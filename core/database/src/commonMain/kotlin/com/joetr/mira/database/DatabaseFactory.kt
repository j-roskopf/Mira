package com.joetr.mira.database

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlSchema
import com.joetr.mira.db.MiraDatabase

public data class MiraDataLayer(
    val driver: SqlDriver,
    val database: MiraDatabase,
)

public expect suspend fun createSqlDriver(schema: SqlSchema<QueryResult.AsyncValue<Unit>>): SqlDriver

public suspend fun createMiraDataLayer(): MiraDataLayer {
    val driver = createSqlDriver(MiraDatabase.Schema)
    return MiraDataLayer(driver, MiraDatabase(driver))
}

public suspend fun createMiraDatabase(): MiraDatabase = createMiraDataLayer().database

public fun miraDatabaseFromDriver(driver: SqlDriver): MiraDatabase = MiraDatabase(driver)

internal fun SqlDriver.execPragma(sql: String) {
    executeQuery(
        identifier = null,
        sql = sql,
        mapper = { cursor ->
            while (cursor.next().value) { }
            QueryResult.Unit
        },
        parameters = 0,
    )
}
