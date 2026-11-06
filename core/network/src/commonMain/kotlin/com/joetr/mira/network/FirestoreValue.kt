package com.joetr.mira.network

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

internal fun RemoteEntryRow.toFirestoreFields(): JsonObject = buildJsonObject {
    put("localDate", stringValue(localDate))
    put("createdAt", integerValue(createdAt))
    put("createdTz", stringValue(createdTz))
    put("body", stringValue(body))
    put("tags", arrayValue(tags.map { stringValue(it) }))
    put("deleted", booleanValue(deleted))
    put(
        "attachments",
        arrayValue(
            attachments.map { attachment ->
                buildJsonObject {
                    put(
                        "mapValue",
                        buildJsonObject {
                            put(
                                "fields",
                                buildJsonObject {
                                    put("id", stringValue(attachment.id))
                                    put("kind", stringValue(attachment.kind))
                                    put("storagePath", stringValue(attachment.storagePath))
                                    put("url", nullableString(attachment.url))
                                    put("mime", stringValue(attachment.mime))
                                    put("bytes", integerValue(attachment.bytes))
                                    put("durationMs", integerValue(attachment.durationMs))
                                    put("posterPath", nullableString(attachment.posterPath))
                                    put("posterUrl", nullableString(attachment.posterUrl))
                                    put("thumbnailPath", nullableString(attachment.thumbnailPath))
                                    put("thumbnailUrl", nullableString(attachment.thumbnailUrl))
                                    put("caption", stringValue(attachment.caption))
                                },
                            )
                        },
                    )
                }
            },
        ),
    )
}

internal fun JsonObject.toRemoteEntry(): RemoteEntryRow {
    val fields = this["fields"]?.jsonObject ?: this
    return RemoteEntryRow(
        localDate = fields.stringField("localDate").orEmpty(),
        createdAt = fields.longField("createdAt") ?: 0L,
        createdTz = fields.stringField("createdTz").orEmpty(),
        updatedAt = fields.timestampOrLongField("updatedAt") ?: 0L,
        body = fields.stringField("body").orEmpty(),
        attachments = fields.attachmentsField("attachments"),
        tags = fields.stringArrayField("tags"),
        deleted = fields.boolField("deleted") ?: false,
    )
}

private fun JsonObject.attachmentsField(name: String): List<RemoteAttachment> {
    val values = this[name]?.jsonObject?.get("arrayValue")?.jsonObject?.get("values")?.jsonArray
        ?: return emptyList()
    return values.mapNotNull { element ->
        val fields = element.jsonObject["mapValue"]?.jsonObject?.get("fields")?.jsonObject
            ?: return@mapNotNull null
        val id = fields.stringField("id") ?: return@mapNotNull null
        val storagePath = fields.stringField("storagePath") ?: return@mapNotNull null
        RemoteAttachment(
            id = id,
            kind = fields.stringField("kind") ?: "audio",
            storagePath = storagePath,
            url = fields.stringField("url"),
            mime = fields.stringField("mime") ?: "application/octet-stream",
            bytes = fields.longField("bytes") ?: 0L,
            durationMs = fields.longField("durationMs") ?: 0L,
            posterPath = fields.stringField("posterPath"),
            posterUrl = fields.stringField("posterUrl"),
            thumbnailPath = fields.stringField("thumbnailPath"),
            thumbnailUrl = fields.stringField("thumbnailUrl"),
            caption = fields.stringField("caption").orEmpty(),
        )
    }
}

private fun stringValue(value: String): JsonObject =
    buildJsonObject { put("stringValue", JsonPrimitive(value)) }

private fun integerValue(value: Long): JsonObject =
    buildJsonObject { put("integerValue", JsonPrimitive(value.toString())) }

private fun booleanValue(value: Boolean): JsonObject =
    buildJsonObject { put("booleanValue", JsonPrimitive(value)) }

private fun nullableString(value: String?): JsonObject =
    if (value == null) buildJsonObject { put("nullValue", JsonNull) } else stringValue(value)

private fun nullableInteger(value: Int?): JsonObject =
    if (value == null) buildJsonObject { put("nullValue", JsonNull) } else integerValue(value.toLong())

private fun arrayValue(values: List<JsonObject>): JsonObject =
    buildJsonObject {
        put(
            "arrayValue",
            buildJsonObject {
                put("values", JsonArray(values))
            },
        )
    }

private fun JsonObject.stringField(name: String): String? =
    this[name]?.jsonObject?.get("stringValue")?.jsonPrimitive?.contentOrNull

private fun JsonObject.intField(name: String): Int? =
    longField(name)?.toInt()

private fun JsonObject.longField(name: String): Long? =
    this[name]?.jsonObject?.get("integerValue")?.jsonPrimitive?.contentOrNull?.toLongOrNull()
        ?: this[name]?.jsonObject?.get("doubleValue")?.jsonPrimitive?.contentOrNull?.toDoubleOrNull()?.toLong()

private fun JsonObject.boolField(name: String): Boolean? =
    this[name]?.jsonObject?.get("booleanValue")?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull()

private fun JsonObject.timestampOrLongField(name: String): Long? {
    val field = this[name]?.jsonObject ?: return null
    field["integerValue"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()?.let { return it }
    val ts = field["timestampValue"]?.jsonPrimitive?.contentOrNull ?: return null
    return parseRfc3339ToEpochMs(ts)
}

private fun JsonObject.stringArrayField(name: String): List<String> {
    val values = this[name]?.jsonObject?.get("arrayValue")?.jsonObject?.get("values")?.jsonArray
        ?: return emptyList()
    return values.mapNotNull { it.jsonObject["stringValue"]?.jsonPrimitive?.contentOrNull }
}


/** Minimal RFC3339 parser (UTC / Z / ±HH:MM) → epoch millis. */
internal fun parseRfc3339ToEpochMs(value: String): Long? {
    val match = Regex(
        """(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2}):(\d{2})(?:\.(\d+))?(Z|[+-]\d{2}:\d{2})?""",
    ).matchEntire(value.trim()) ?: return null
    val year = match.groupValues[1].toInt()
    val month = match.groupValues[2].toInt()
    val day = match.groupValues[3].toInt()
    val hour = match.groupValues[4].toInt()
    val minute = match.groupValues[5].toInt()
    val second = match.groupValues[6].toInt()
    val frac = match.groupValues[7]
    val millis = when {
        frac.isEmpty() -> 0
        frac.length >= 3 -> frac.take(3).toInt()
        else -> frac.padEnd(3, '0').toInt()
    }
    val tz = match.groupValues[8].ifEmpty { "Z" }
    val days = toEpochDay(year, month, day)
    var total = days * 86_400_000L + hour * 3_600_000L + minute * 60_000L + second * 1_000L + millis
    if (tz != "Z") {
        val sign = if (tz[0] == '-') -1 else 1
        val th = tz.substring(1, 3).toInt()
        val tm = tz.substring(4, 6).toInt()
        total -= sign * (th * 3_600_000L + tm * 60_000L)
    }
    return total
}

private fun toEpochDay(year: Int, month: Int, day: Int): Long {
    val y = year.toLong()
    val m = month.toLong()
    val d = day.toLong()
    val y2 = if (m <= 2) y - 1 else y
    val era = (if (y2 >= 0) y2 else y2 - 399) / 400
    val yoe = y2 - era * 400
    val doy = (153 * (m + (if (m > 2) -3 else 9)) + 2) / 5 + d - 1
    val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
    return era * 146097 + doe - 719468
}
