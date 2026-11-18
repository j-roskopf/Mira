package com.joetr.mira.data.archive

import com.joetr.mira.domain.Entry
import com.joetr.mira.platform.currentTimeMillis
import io.ktor.client.plugins.expectSuccess
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.content.TextContent
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
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
 * A media file to commit.
 *
 * [LocalFile] is streamed through base64 as the request body is written, so archiving a long
 * recording never allocates the whole file. [Bytes] covers platforms and payloads that already
 * hold the media in memory.
 */
public sealed interface ArchiveMedia {
    public data class LocalFile(val path: String) : ArchiveMedia
    public data class Bytes(val bytes: ByteArray) : ArchiveMedia
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
    private var lastContentRequestAt = 0L

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
        val ref = refSha(target)
        if (ref is RefLookup.Failed) throw IllegalArgumentException(ref.message)
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
        mediaFiles: Map<String, ArchiveMedia> = emptyMap(),
        message: String? = null,
        archivedMediaPaths: Set<String> = mediaFiles.keys,
        archivedMedia: Map<String, ArchivedMedia> = emptyMap(),
    ): ArchiveResult {
        if (entries.isEmpty() && mediaFiles.isEmpty()) return ArchiveResult.NothingToDo

        return runCatching {
            val head = refSha(target)
            if (head is RefLookup.Failed) return ArchiveResult.Failed(head.message)
            val headSha = (head as RefLookup.Found).sha
            val baseTree = commitTreeSha(target, headSha)
                ?: return ArchiveResult.Failed("Could not read the current tree")

            val treeEntries = buildJsonArray {
                for (entry in entries) {
                    val markdown = if (archivedMedia.isEmpty()) {
                        entry.toMarkdown(archivedMediaPaths)
                    } else {
                        entry.toMarkdown(archivedMedia)
                    }
                    when (val blob = createBlob(target, markdown)) {
                        is BlobResult.Created -> add(treeEntry(entry.archivePath(), blob.sha))
                        is BlobResult.Failed -> return ArchiveResult.Failed(
                            "Could not write ${entry.archivePath()}: ${blob.reason}",
                        )
                    }
                }
                for ((path, media) in mediaFiles) {
                    when (val blob = createBinaryBlob(target, media)) {
                        is BlobResult.Created -> add(treeEntry(path, blob.sha))
                        is BlobResult.Failed -> return ArchiveResult.Failed(
                            "Could not write $path: ${blob.reason}",
                        )
                    }
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

    private suspend fun refSha(target: ArchiveTarget): RefLookup {
        val response = try {
            httpClient.get("$api/repos/${target.slug}/git/ref/heads/${target.branch}") {
                githubHeaders()
                // Read the status ourselves so a revoked token or a renamed repository is not
                // reported as a missing branch.
                expectSuccess = false
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return RefLookup.Failed(
                "Could not reach GitHub to check ${target.slug}. Check your connection and try again.",
            )
        }
        val body = response.bodyAsText()
        if (!response.status.isSuccess()) {
            return RefLookup.Failed(refErrorMessage(response.status, target, body))
        }
        val sha = runCatching {
            json.parseToJsonElement(body).jsonObject["object"]
                ?.jsonObject?.get("sha")?.jsonPrimitive?.content
        }.getOrNull()
        return if (sha != null) {
            RefLookup.Found(sha)
        } else {
            RefLookup.Failed("GitHub returned an unexpected response for ${target.slug}.")
        }
    }

    private fun refErrorMessage(
        status: HttpStatusCode,
        target: ArchiveTarget,
        body: String,
    ): String = when (status) {
        HttpStatusCode.Unauthorized ->
            "GitHub rejected the saved access token. Reconnect the archive in Settings."
        HttpStatusCode.Forbidden ->
            if (body.contains("rate limit", ignoreCase = true)) {
                "GitHub's rate limit was reached. Try again later."
            } else {
                "GitHub denied access to ${target.slug}. Reconnect the archive or check your permissions."
            }
        HttpStatusCode.NotFound ->
            "Could not find ${target.slug} or branch '${target.branch}'. Check that the repository " +
                "and branch still exist."
        else ->
            "GitHub returned HTTP ${status.value} while checking ${target.slug}."
    }

    private sealed interface RefLookup {
        data class Found(val sha: String) : RefLookup
        data class Failed(val message: String) : RefLookup
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

    private sealed interface BlobResult {
        data class Created(val sha: String) : BlobResult
        data class Failed(val reason: String) : BlobResult
    }

    private suspend fun createBlob(target: ArchiveTarget, content: String): BlobResult =
        postBlobWithRetry(target) {
            TextContent(
                text = buildJsonObject {
                    put("content", JsonPrimitive(content))
                    put("encoding", JsonPrimitive("utf-8"))
                }.toString(),
                contentType = ContentType.Application.Json,
            )
        }

    private suspend fun createBinaryBlob(target: ArchiveTarget, media: ArchiveMedia): BlobResult =
        when (media) {
            is ArchiveMedia.Bytes -> postBlobWithRetry(target) {
                base64JsonBytesContent(media.bytes)
            }
            is ArchiveMedia.LocalFile -> postBlobWithRetry(target) {
                base64JsonFileContent(media.path)
            }
        }

    /**
     * Uploads one blob, retrying transient failures and surfacing the real GitHub response.
     *
     * Media is uploaded as a burst of blob requests, so a single 5xx, timeout, or secondary rate
     * limit used to abort the whole push with only the path in the message. The content is rebuilt
     * per attempt because a streamed file body cannot be replayed.
     */
    private suspend fun postBlobWithRetry(
        target: ArchiveTarget,
        makeContent: () -> OutgoingContent?,
    ): BlobResult {
        var lastReason = "GitHub did not accept the upload"
        repeat(MAX_BLOB_ATTEMPTS) { attempt ->
            val content = makeContent()
                ?: return BlobResult.Failed("the local copy is missing or unreadable")
            paceContentRequests()
            val response = runCatching {
                httpClient.post("$api/repos/${target.slug}/git/blobs") {
                    githubHeaders()
                    expectSuccess = false
                    setBody(content)
                }
            }.getOrNull()
            if (response == null) {
                lastReason = "network error while uploading"
                if (attempt < MAX_BLOB_ATTEMPTS - 1) {
                    delay(blobRetryDelay(null, attempt))
                    return@repeat
                }
                return BlobResult.Failed(lastReason)
            }
            val body = runCatching { response.bodyAsText() }.getOrDefault("")
            if (response.status.isSuccess()) {
                val sha = runCatching {
                    json.parseToJsonElement(body).jsonObject["sha"]?.jsonPrimitive?.content
                }.getOrNull()
                return if (sha != null) {
                    BlobResult.Created(sha)
                } else {
                    BlobResult.Failed("GitHub returned no blob sha")
                }
            }
            lastReason = describeFailure(response.status, body)
            if (!isRetryable(response.status, body) || attempt == MAX_BLOB_ATTEMPTS - 1) {
                return BlobResult.Failed(lastReason)
            }
            delay(blobRetryDelay(response, attempt))
        }
        return BlobResult.Failed(lastReason)
    }

    private suspend fun paceContentRequests() {
        val elapsed = currentTimeMillis() - lastContentRequestAt
        if (elapsed in 0 until CONTENT_REQUEST_INTERVAL_MS) {
            delay(CONTENT_REQUEST_INTERVAL_MS - elapsed)
        }
        lastContentRequestAt = currentTimeMillis()
    }

    private fun describeFailure(status: HttpStatusCode, body: String): String {
        val detail = body.replace(Regex("\\s+"), " ").trim().take(240)
        return if (detail.isEmpty()) {
            "GitHub returned HTTP ${status.value}"
        } else {
            "GitHub returned HTTP ${status.value}: $detail"
        }
    }

    private fun isRetryable(status: HttpStatusCode, body: String): Boolean =
        status == HttpStatusCode.TooManyRequests ||
            status.value in 500..599 ||
            (status == HttpStatusCode.Forbidden &&
                body.contains("rate limit", ignoreCase = true))

    private fun blobRetryDelay(response: io.ktor.client.statement.HttpResponse?, attempt: Int): Long {
        val retryAfter = response?.headers?.get("Retry-After")?.toLongOrNull()?.times(1000)
        if (retryAfter != null && retryAfter > 0) return retryAfter.coerceAtMost(120_000)
        val isRateLimit = response?.status == HttpStatusCode.Forbidden ||
            response?.status == HttpStatusCode.TooManyRequests
        // GitHub's secondary content-creation limit is a rolling minute; waiting a full window is
        // what actually clears it.
        return if (isRateLimit) 60_000L else (attempt + 1) * 2_000L
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
        val response = httpClient.patch("$api/repos/${target.slug}/git/refs/heads/${target.branch}") {
            githubHeaders()
            expectSuccess = false
            setBody(
                buildJsonObject {
                    put("sha", JsonPrimitive(commitSha))
                    put("force", JsonPrimitive(false))
                }.toString(),
            )
        }
        if (!response.status.isSuccess()) {
            throw IllegalStateException(
                when (response.status) {
                    HttpStatusCode.Unauthorized ->
                        "GitHub rejected the saved access token. Reconnect the archive in Settings."
                    HttpStatusCode.NotFound ->
                        "Branch '${target.branch}' no longer exists in ${target.slug}."
                    HttpStatusCode.Conflict, HttpStatusCode.UnprocessableEntity ->
                        "${target.slug} changed on GitHub while backing up. Try again."
                    else -> "GitHub returned HTTP ${response.status.value} while updating ${target.slug}."
                },
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

private const val MAX_BLOB_ATTEMPTS: Int = 4

/**
 * GitHub's secondary limit allows roughly 80 content-generating requests per minute. A first backup
 * can create dozens of note and media blobs, so requests are paced below that rather than relying
 * on the 403 retry, which would otherwise restart the whole push after every burst.
 */
private const val CONTENT_REQUEST_INTERVAL_MS: Long = 800
