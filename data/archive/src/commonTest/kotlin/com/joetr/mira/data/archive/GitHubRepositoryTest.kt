package com.joetr.mira.data.archive

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GitHubRepositoryTest {
    @Test
    fun existingRepositoryUsesCanonicalOwnerAndDefaultBranch() = runTest {
        val client = HttpClient(MockEngine { request ->
            when (request.url.encodedPath) {
                "/repos/me/journal" -> respond(repository())
                "/repos/Me/journal/git/ref/heads/archive" -> respond("""{"object":{"sha":"head"}}""")
                else -> error("Unexpected request: ${request.url}")
            }
        })
        try {
            assertEquals(ArchiveTarget("Me", "journal", "archive"), GitHubArchive(client, "token").existingRepository("me/journal"))
        } finally { client.close() }
    }

    @Test
    fun rejectsPublicReadOnlyArchivedAndEmptyRepositories() = runTest {
        for (body in listOf(repository(private = false), repository(push = false), repository(archived = true), repository())) {
            val client = HttpClient(MockEngine { request ->
                if (request.url.encodedPath.contains("/git/ref/")) respond("{}", HttpStatusCode.NotFound)
                else respond(body)
            })
            try {
                assertFailsWith<IllegalArgumentException> {
                    GitHubArchive(client, "token").existingRepository("me/journal")
                }
            } finally { client.close() }
        }
    }

    @Test
    fun invalidInputDoesNotMakeNetworkRequests() = runTest {
        val client = HttpClient(MockEngine { error("No request expected") })
        try {
            val archive = GitHubArchive(client, "token")
            for (slug in listOf("journal", "me/journal/extra", "me/..", "me/journal?x=1")) {
                assertFailsWith<IllegalArgumentException> { archive.existingRepository(slug) }
            }
            assertFailsWith<IllegalArgumentException> { archive.createRepository("bad/name") }
        } finally { client.close() }
    }

    @Test
    fun failedCreationDoesNotReturnAnAssumedTarget() = runTest {
        val client = HttpClient(MockEngine { respond(repository(), HttpStatusCode.UnprocessableEntity) })
        try {
            assertNull(GitHubArchive(client, "token").createRepository("journal"))
        } finally { client.close() }
    }

    @Test
    fun rejectedTokenIsNotReportedAsAMissingBranch() = runTest {
        val client = HttpClient(MockEngine { request ->
            if (request.url.encodedPath.contains("/git/ref/")) {
                respond("""{"message":"Bad credentials"}""", HttpStatusCode.Unauthorized)
            } else {
                respond(repository())
            }
        })
        try {
            val error = assertFailsWith<IllegalArgumentException> {
                GitHubArchive(client, "token").existingRepository("me/journal")
            }
            assertEquals(
                "GitHub rejected the saved access token. Reconnect the archive in Settings.",
                error.message,
            )
        } finally { client.close() }
    }

    @Test
    fun missingBranchNamesTheRepositoryAndBranch() = runTest {
        val client = HttpClient(MockEngine { request ->
            if (request.url.encodedPath.contains("/git/ref/")) {
                respond("""{"message":"Not Found"}""", HttpStatusCode.NotFound)
            } else {
                respond(repository())
            }
        })
        try {
            val error = assertFailsWith<IllegalArgumentException> {
                GitHubArchive(client, "token").existingRepository("me/journal")
            }
            assertEquals(
                "Could not find Me/journal or branch 'archive'. Check that the repository " +
                    "and branch still exist.",
                error.message,
            )
        } finally { client.close() }
    }

    @Test
    fun aTransientBlobFailureIsRetried() = runTest {
        var blobAttempts = 0
        val client = HttpClient(MockEngine { request ->
            when {
                request.url.encodedPath.contains("/git/ref/heads/") ->
                    respond("""{"object":{"sha":"head"}}""")
                request.url.encodedPath.endsWith("/git/commits/head") ->
                    respond("""{"tree":{"sha":"tree"}}""")
                request.url.encodedPath.endsWith("/git/blobs") -> {
                    blobAttempts++
                    if (blobAttempts == 1) {
                        respond("""{"message":"temporary"}""", HttpStatusCode.InternalServerError)
                    } else {
                        respond("""{"sha":"blob"}""")
                    }
                }
                request.url.encodedPath.endsWith("/git/trees") -> respond("""{"sha":"newtree"}""")
                request.url.encodedPath.endsWith("/git/commits") -> respond("""{"sha":"commit"}""")
                request.method == HttpMethod.Patch -> respond("""{}""")
                else -> respond("""{}""")
            }
        })
        try {
            val result = GitHubArchive(client, "token").push(
                target = ArchiveTarget("me", "journal", "main"),
                entries = emptyList(),
                mediaFiles = mapOf("media/video.mp4" to ArchiveMedia.Bytes(byteArrayOf(1, 2, 3))),
            )
            assertTrue(result is ArchiveResult.Pushed)
            assertEquals(2, blobAttempts)
        } finally { client.close() }
    }

    @Test
    fun aRejectedBlobReportsTheGitHubReason() = runTest {
        val client = HttpClient(MockEngine { request ->
            when {
                request.url.encodedPath.contains("/git/ref/heads/") ->
                    respond("""{"object":{"sha":"head"}}""")
                request.url.encodedPath.endsWith("/git/commits/head") ->
                    respond("""{"tree":{"sha":"tree"}}""")
                request.url.encodedPath.endsWith("/git/blobs") ->
                    respond("""{"message":"file is too large"}""", HttpStatusCode.UnprocessableEntity)
                request.url.encodedPath.endsWith("/git/trees") -> respond("""{"sha":"newtree"}""")
                request.url.encodedPath.endsWith("/git/commits") -> respond("""{"sha":"commit"}""")
                else -> respond("""{}""")
            }
        })
        try {
            val result = GitHubArchive(client, "token").push(
                target = ArchiveTarget("me", "journal", "main"),
                entries = emptyList(),
                mediaFiles = mapOf("media/video.mp4" to ArchiveMedia.Bytes(byteArrayOf(1, 2, 3))),
            )
            val failure = result as ArchiveResult.Failed
            assertTrue(failure.message.contains("media/video.mp4"))
            assertTrue(failure.message.contains("422"))
            assertTrue(failure.message.contains("file is too large"))
        } finally { client.close() }
    }

    private fun repository(private: Boolean = true, push: Boolean = true, archived: Boolean = false) =
        """{"owner":{"login":"Me"},"name":"journal","default_branch":"archive","private":$private,"permissions":{"push":$push},"archived":$archived}"""
}
