package com.joetr.mira.database

import app.cash.sqldelight.async.coroutines.awaitCreate
import app.cash.sqldelight.async.coroutines.awaitMigrate
import app.cash.sqldelight.async.coroutines.awaitQuery
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlSchema
import app.cash.sqldelight.driver.worker.WebWorkerDriver
import com.joetr.mira.platform.localDatabaseRevisionKey
import kotlinx.browser.window
import org.w3c.dom.Worker

public actual suspend fun createSqlDriver(schema: SqlSchema<QueryResult.AsyncValue<Unit>>): SqlDriver {
    val schemaVersionKey = "${localDatabaseRevisionKey()}.web.schema"
    val storedSchemaVersion = window.localStorage.getItem(schemaVersionKey)?.toLongOrNull()
    val driver = WebWorkerDriver(createSqlJsWorker())
    val hasSchema = driver.hasUserSchema()

    if (!hasSchema) {
        schema.awaitCreate(driver)
    } else if (storedSchemaVersion != null && storedSchemaVersion < schema.version) {
        schema.awaitMigrate(driver, storedSchemaVersion, schema.version)
    }
    if (storedSchemaVersion == null || storedSchemaVersion <= schema.version) {
        window.localStorage.setItem(schemaVersionKey, schema.version.toString())
    }
    return driver
}

private suspend fun SqlDriver.hasUserSchema(): Boolean =
    awaitQuery(
        identifier = null,
        sql = "SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%' LIMIT 1",
        mapper = { cursor -> cursor.next().await() },
        parameters = 0,
    )

private fun createSqlJsWorker(): Worker =
    js("new Worker(new URL('mira-persistent-sqljs.worker.js', import.meta.url))")
