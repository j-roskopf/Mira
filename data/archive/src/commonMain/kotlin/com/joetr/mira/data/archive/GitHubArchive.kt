package com.joetr.mira.data.archive

import com.joetr.mira.domain.Entry
import io.ktor.client.plugins.expectSuccess
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

public data class ArchiveTarget(
    val owner: String,
    val repo: String,
    val branch: String = "main",
) {
    public val slug: String get() = "$owner/$repo"
}

public sealed interface ArchiveResult {
    public data class Pushed(val commitSha: String, val fileCount: Int) : ArchiveResult
    public data object NothingToDo : ArchiveResult
    public data class Failed(val message: String) : ArchiveResult
}

/**
 * One-way push of entries to a private GitHub repository.
 *
 * **Nothing is ever read back.** Firestore is the source of truth; git is a durable, human-readable
 * archive. Two-way sync would introduce a third independently-writable replica needing three-way
 * merge against git's own semantics, for the sake of editing a `.md` on github.com — not a trade
 * worth making.
 *
 * Writes go through the **Git Data API** rather than a git binary, because there is no git on iOS
 * or in a browser: blobs → tree → commit → ref update, from commonMain, one commit per push.
 *
 * Every edit is a commit, so `git log` on an entry file is that entry's revision history — which
 * also recovers anything lost to last-write-wins in the Firestore sync.
 */
public class GitHubArchive(
    private val httpClient: HttpClient,
    private val token: String,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val api = "https://api.github.com"

    public suspend fun currentLogin(): String? {
        val body = runCatching { githubGet("$api/user") }.getOrNull() ?: return null
        return runCatching {
            json.parseToJsonElement(body).jsonObject["login"]?.jsonPrimitive?.content
        }.getOrNull()
    }

    /** Resolves a private, writable repository and its actual default branch. */
    public suspend fun existingRepository(slug: String): ArchiveTarget {
        require(Regex("[A-Za-z0-9-]+/[A-Za-z0-9_.-]+").matches(slug) &&
            slug.substringAfter('/') !in listOf(".", "..")) {
            "Enter the repository as owner/name."
        }
        val response = httpClient.get("$api/repos/$slug") {
            githubHeaders()
            expectSuccess = false
        }
        require(response.status.value == 200) {
            "Could not access that repository. Check the owner/name and your GitHub access."
        }
        val fields = json.parseToJsonElement(response.bodyAsText()).jsonObject
        require(fields["private"]?.jsonPrimitive?.booleanOrNull == true) {
            "Choose a private repository to keep your journal private."
        }
        require(fields["archived"]?.jsonPrimitive?.booleanOrNull != true &&
            fields["disabled"]?.jsonPrimitive?.booleanOrNull != true &&
            fields["permissions"]?.jsonObject?.get("push")?.jsonPrimitive?.booleanOrNull == true) {
            "Choose an active repository you have write access to."
        }
        val target = ArchiveTarget(
            fields.getValue("owner").jsonObject.getValue("login").jsonPrimitive.content,
            fields.getValue("name").jsonPrimitive.content,
            fields.getValue("default_branch").jsonPrimitive.content,
        )
        require(refSha(target) != null) {
            "The repository needs an initial commit. Add a README on GitHub, then try again."
        }
        return target
    }

    /** Creates the archive repository, private by default. Journal data is not public. */
    public suspend fun createRepository(name: String, private: Boolean = true): ArchiveTarget? {
        require(name.length in 1..100 && Regex("[A-Za-z0-9_.-]+").matches(name) && name !in listOf(".", "..")) {
            "Use 1–100 letters, numbers, periods, hyphens, or underscores for the repository name."
        }
        val body = try {
            val response = httpClient.post("$api/user/repos") {
                githubHeaders()
                setBody(
                    buildJsonObject {
                        put("name", JsonPrimitive(name))
                        put("private", JsonPrimitive(private))
                        put("description", JsonPrimitive("Mira journal archive"))
                        put("auto_init", JsonPrimitive(true))
                    }.toString(),
                )
            }
            if (response.status.value != 201) return null
            response.bodyAsText()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return null
        }

        val fields = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
            ?: return null
        val owner = fields["owner"]?.jsonObject?.get("login")?.jsonPrimitive?.content
            ?: return null
        val repo = fields["name"]?.jsonPrimitive?.content ?: return null
        val branch = fields["default_branch"]?.jsonPrimitive?.content ?: "main"
        return ArchiveTarget(owner, repo, branch)
    }

    /**
     * Pushes [entries] as one commit.
     *
     * [mediaFiles] is populated only when "Include media in backup" is on, which commits the actual
     * audio and video as files rather than only the links in frontmatter.
     */
    public suspend fun push(
        target: ArchiveTarget,
        entries: List<Entry>,
        mediaFiles: Map<String, ByteArray> = emptyMap(),
        message: String? = null,
        archivedMediaPaths: Set<String> = mediaFiles.keys,
    ): ArchiveResult {
        if (entries.isEmpty() && mediaFiles.isEmpty()) return ArchiveResult.NothingToDo

        return runCatching {
            val headSha = refSha(target) ?: return ArchiveResult.Failed("Branch not found")
            val baseTree = commitTreeSha(target, headSha)
                ?: return ArchiveResult.Failed("Could not read the current tree")

            val treeEntries = buildJsonArray {
                for (entry in entries) {
                    val blobSha = createBlob(target, entry.toMarkdown(archivedMediaPaths))
                        ?: return ArchiveResult.Failed("Could not write ${entry.id}")
                    add(treeEntry(entry.archivePath(), blobSha))
                }
                for ((path, bytes) in mediaFiles) {
                    val blobSha = createBinaryBlob(target, bytes)
                        ?: return ArchiveResult.Failed("Could not write $path")
                    add(treeEntry(path, blobSha))
                }
            }

            val treeSha = createTree(target, baseTree, treeEntries)
                ?: return ArchiveResult.Failed("Could not build the commit tree")

            val commitMessage = message ?: defaultMessage(entries)
            val commitSha = createCommit(target, commitMessage, treeSha, headSha)
                ?: return ArchiveResult.Failed("Could not create the commit")

            updateRef(target, commitSha)
            ArchiveResult.Pushed(commitSha, entries.size + mediaFiles.size)
        }.getOrElse { error ->
            ArchiveResult.Failed(error.message ?: "Backup failed")
        }
    }

    private fun defaultMessage(entries: List<Entry>): String {
        val count = entries.size
        val noun = if (count == 1) "entry" else "entries"
        val dates = entries.map { it.localDate.value }.distinct().sorted()
        val range = when {
            dates.isEmpty() -> ""
            dates.size == 1 -> " (${dates.first()})"
            else -> " (${dates.first()}–${dates.last()})"
        }
        return "Sync $count $noun$range"
    }

    private suspend fun refSha(target: ArchiveTarget): String? {
        val body = runCatching {
            githubGet("$api/repos/${target.slug}/git/ref/heads/${target.branch}")
        }.getOrNull() ?: return null
        return runCatching {
            json.parseToJsonElement(body).jsonObject["object"]
                ?.jsonObject?.get("sha")?.jsonPrimitive?.content
        }.getOrNull()
    }

    private suspend fun commitTreeSha(target: ArchiveTarget, commitSha: String): String? {
        val body = runCatching {
            githubGet("$api/repos/${target.slug}/git/commits/$commitSha")
        }.getOrNull() ?: return null
        return runCatching {
            json.parseToJsonElement(body).jsonObject["tree"]
                ?.jsonObject?.get("sha")?.jsonPrimitive?.content
        }.getOrNull()
    }

    private suspend fun createBlob(target: ArchiveTarget, content: String): String? =
        postBlob(target, content, encoding = "utf-8")

    private suspend fun createBinaryBlob(target: ArchiveTarget, bytes: ByteArray): String? =
        postBlob(target, bytes.encodeBase64(), encoding = "base64")

    private suspend fun postBlob(
        target: ArchiveTarget,
        content: String,
        encoding: String,
    ): String? {
        val body = runCatching {
            httpClient.post("$api/repos/${target.slug}/git/blobs") {
                githubHeaders()
                setBody(
                    buildJsonObject {
                        put("content", JsonPrimitive(content))
                        put("encoding", JsonPrimitive(encoding))
                    }.toString(),
                )
            }.bodyAsText()
        }.getOrNull() ?: return null
        return runCatching {
            json.parseToJsonElement(body).jsonObject["sha"]?.jsonPrimitive?.content
        }.getOrNull()
    }

    private fun treeEntry(path: String, blobSha: String) = buildJsonObject {
        put("path", JsonPrimitive(path))
        put("mode", JsonPrimitive("100644"))
        put("type", JsonPrimitive("blob"))
        put("sha", JsonPrimitive(blobSha))
    }

    private suspend fun createTree(
        target: ArchiveTarget,
        baseTree: String,
        entries: JsonArray,
    ): String? {
        val body = runCatching {
            httpClient.post("$api/repos/${target.slug}/git/trees") {
                githubHeaders()
                setBody(
                    buildJsonObject {
                        put("base_tree", JsonPrimitive(baseTree))
                        put("tree", entries)
                    }.toString(),
                )
            }.bodyAsText()
        }.getOrNull() ?: return null
        return runCatching {
            json.parseToJsonElement(body).jsonObject["sha"]?.jsonPrimitive?.content
        }.getOrNull()
    }

    private suspend fun createCommit(
        target: ArchiveTarget,
        message: String,
        treeSha: String,
        parentSha: String,
    ): String? {
        val body = runCatching {
            httpClient.post("$api/repos/${target.slug}/git/commits") {
                githubHeaders()
                setBody(
                    buildJsonObject {
                        put("message", JsonPrimitive(message))
                        put("tree", JsonPrimitive(treeSha))
                        put("parents", buildJsonArray { add(JsonPrimitive(parentSha)) })
                    }.toString(),
                )
            }.bodyAsText()
        }.getOrNull() ?: return null
        return runCatching {
            json.parseToJsonElement(body).jsonObject["sha"]?.jsonPrimitive?.content
        }.getOrNull()
    }

    private suspend fun updateRef(target: ArchiveTarget, commitSha: String) {
        httpClient.patch("$api/repos/${target.slug}/git/refs/heads/${target.branch}") {
            githubHeaders()
            setBody(
                buildJsonObject {
                    put("sha", JsonPrimitive(commitSha))
                    put("force", JsonPrimitive(false))
                }.toString(),
            )
        }
    }

    private suspend fun githubGet(url: String): String =
        httpClient.get(url) { githubHeaders() }.bodyAsText()

    private fun io.ktor.client.request.HttpRequestBuilder.githubHeaders() {
        contentType(ContentType.Application.Json)
        header("Authorization", "Bearer $token")
        header("Accept", "application/vnd.github+json")
        header("X-GitHub-Api-Version", "2022-11-28")
        header("User-Agent", "Mira")
    }
}

private const val BASE64_ALPHABET =
    "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

internal fun ByteArray.encodeBase64(): String {
    val output = StringBuilder((size + 2) / 3 * 4)
    var index = 0
    while (index + 2 < size) {
        val chunk = ((this[index].toInt() and 0xFF) shl 16) or
            ((this[index + 1].toInt() and 0xFF) shl 8) or
            (this[index + 2].toInt() and 0xFF)
        output.append(BASE64_ALPHABET[(chunk shr 18) and 0x3F])
        output.append(BASE64_ALPHABET[(chunk shr 12) and 0x3F])
        output.append(BASE64_ALPHABET[(chunk shr 6) and 0x3F])
        output.append(BASE64_ALPHABET[chunk and 0x3F])
        index += 3
    }
    when (size - index) {
        1 -> {
            val chunk = (this[index].toInt() and 0xFF) shl 16
            output.append(BASE64_ALPHABET[(chunk shr 18) and 0x3F])
            output.append(BASE64_ALPHABET[(chunk shr 12) and 0x3F])
            output.append("==")
        }
        2 -> {
            val chunk = ((this[index].toInt() and 0xFF) shl 16) or
                ((this[index + 1].toInt() and 0xFF) shl 8)
            output.append(BASE64_ALPHABET[(chunk shr 18) and 0x3F])
            output.append(BASE64_ALPHABET[(chunk shr 12) and 0x3F])
            output.append(BASE64_ALPHABET[(chunk shr 6) and 0x3F])
            output.append('=')
        }
    }
    return output.toString()
}
