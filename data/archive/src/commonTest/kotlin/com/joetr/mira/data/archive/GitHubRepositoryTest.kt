package com.joetr.mira.data.archive

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

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

    private fun repository(private: Boolean = true, push: Boolean = true, archived: Boolean = false) =
        """{"owner":{"login":"Me"},"name":"journal","default_branch":"archive","private":$private,"permissions":{"push":$push},"archived":$archived}"""
}
