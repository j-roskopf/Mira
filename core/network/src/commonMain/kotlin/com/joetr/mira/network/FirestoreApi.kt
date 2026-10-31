package com.joetr.mira.network

import com.joetr.mira.platform.MiraConfig
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

public class FirestoreApi(
    private val httpClient: HttpClient,
) {
    private val projectId: String get() = MiraConfig.FIREBASE_PROJECT_ID
    private val json = Json { ignoreUnknownKeys = true }
    private val base: String
        get() = "https://firestore.googleapis.com/v1/projects/$projectId/databases/(default)/documents"

    public suspend fun upsertEntries(
        idToken: String,
        ownerId: String,
        documents: List<FirestoreEntryDocument>,
    ) {
        if (documents.isEmpty()) return
        val writes = buildList {
            for (doc in documents) {
                add(upsertSetWrite(ownerId, doc))
                add(updatedAtTransformWrite(ownerId, doc.id))
            }
        }
        commit(idToken, writes)
    }

    /** Tombstones cloud entries without re-uploading the whole document body. */
    public suspend fun markEntriesDeleted(
        idToken: String,
        ownerId: String,
        entryIds: List<String>,
    ) {
        if (entryIds.isEmpty()) return
        val writes = buildList {
            for (id in entryIds) {
                add(
                    buildJsonObject {
                        put(
                            "update",
                            buildJsonObject {
                                put("name", JsonPrimitive(documentName(ownerId, id)))
                                put(
                                    "fields",
                                    buildJsonObject {
                                        put(
                                            "deleted",
                                            buildJsonObject {
                                                put("booleanValue", JsonPrimitive(true))
                                            },
                                        )
                                    },
                                )
                            },
                        )
                        put(
                            "updateMask",
                            buildJsonObject {
                                put(
                                    "fieldPaths",
                                    buildJsonArray {
                                        add(JsonPrimitive("deleted"))
                                    },
                                )
                            },
                        )
                    },
                )
                add(updatedAtTransformWrite(ownerId, id))
            }
        }
        commit(idToken, writes)
    }

    private suspend fun commit(idToken: String, writes: List<JsonObject>) {
        httpClient.post("$base:commit") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $idToken")
            setBody(buildJsonObject { put("writes", JsonArray(writes)) }.toString())
        }
    }

    private fun upsertSetWrite(ownerId: String, doc: FirestoreEntryDocument): JsonObject =
        buildJsonObject {
            put(
                "update",
                buildJsonObject {
                    put("name", JsonPrimitive(documentName(ownerId, doc.id)))
                    put("fields", doc.entry.toFirestoreFields())
                },
            )
        }

    private fun updatedAtTransformWrite(ownerId: String, entryId: String): JsonObject =
        buildJsonObject {
            put(
                "transform",
                buildJsonObject {
                    put("document", JsonPrimitive(documentName(ownerId, entryId)))
                    put(
                        "fieldTransforms",
                        buildJsonArray {
                            add(
                                buildJsonObject {
                                    put("fieldPath", JsonPrimitive("updatedAt"))
                                    put("setToServerValue", JsonPrimitive("REQUEST_TIME"))
                                },
                            )
                        },
                    )
                },
            )
        }

    public suspend fun pullEntries(
        idToken: String,
        ownerId: String,
        cursorUpdatedAt: Long?,
        cursorId: String?,
        pageSize: Int = 100,
    ): List<FirestoreEntryDocument> {
        val structuredQuery = buildJsonObject {
            put(
                "from",
                buildJsonArray {
                    add(buildJsonObject { put("collectionId", JsonPrimitive("entries")) })
                },
            )
            put(
                "orderBy",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("field", buildJsonObject { put("fieldPath", JsonPrimitive("updatedAt")) })
                            put("direction", JsonPrimitive("ASCENDING"))
                        },
                    )
                    add(
                        buildJsonObject {
                            put("field", buildJsonObject { put("fieldPath", JsonPrimitive("__name__")) })
                            put("direction", JsonPrimitive("ASCENDING"))
                        },
                    )
                },
            )
            if (cursorUpdatedAt != null && cursorId != null) {
                put(
                    "startAt",
                    buildJsonObject {
                        put(
                            "values",
                            buildJsonArray {
                                add(
                                    buildJsonObject {
                                        put("integerValue", JsonPrimitive(cursorUpdatedAt.toString()))
                                    },
                                )
                                add(
                                    buildJsonObject {
                                        put(
                                            "referenceValue",
                                            JsonPrimitive(documentName(ownerId, cursorId)),
                                        )
                                    },
                                )
                            },
                        )
                        put("before", JsonPrimitive(false))
                    },
                )
            }
            put("limit", JsonPrimitive(pageSize))
        }
        val parent = "$base/users/$ownerId"
        val responseText = httpClient.post("$parent:runQuery") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $idToken")
            setBody(buildJsonObject { put("structuredQuery", structuredQuery) }.toString())
        }.bodyAsText()
        val rows = json.parseToJsonElement(responseText).jsonArray
        return rows.mapNotNull { row ->
            val document = row.jsonObject["document"]?.jsonObject ?: return@mapNotNull null
            val name = document["name"]?.jsonPrimitive?.content ?: return@mapNotNull null
            val id = name.substringAfterLast('/')
            FirestoreEntryDocument(id = id, entry = document.toRemoteEntry())
        }
    }

    private fun documentName(ownerId: String, entryId: String): String =
        "projects/$projectId/databases/(default)/documents/users/$ownerId/entries/$entryId"
}
