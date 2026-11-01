package com.joetr.mira.data.archive

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class GitHubDeviceFlowTest {
    @Test
    fun disabledDeviceFlowOnHttp400HasActionableMessage() = runTest {
        val client = HttpClient(MockEngine {
            respond(
                """{"error":"device_flow_disabled","error_description":"Device Flow must be explicitly enabled for this App"}""",
                HttpStatusCode.BadRequest,
            )
        }) { expectSuccess = true }
        try {
            val error = assertFailsWith<GitHubAuthorizationException> {
                GitHubDeviceFlow(client, "test-client").requestCode()
            }
            assertTrue(error.message.orEmpty().contains("Enable Device Flow"))
        } finally {
            client.close()
        }
    }

    @Test
    fun pollingHandlesPendingOnHttp400BeforeSuccess() = runTest {
        var calls = 0
        val client = HttpClient(MockEngine {
            calls++
            if (calls == 1) {
                respond("""{"error":"authorization_pending"}""", HttpStatusCode.BadRequest)
            } else {
                respond("""{"access_token":"test-token"}""")
            }
        }) { expectSuccess = true }
        try {
            val request = DeviceCodeRequest("ABCD", "https://github.com/login/device", "device", 1, 10)
            assertEquals(
                DeviceFlowResult.Success("test-token"),
                GitHubDeviceFlow(client, "test-client").awaitToken(request),
            )
            assertEquals(2, calls)
        } finally {
            client.close()
        }
    }

    @Test
    fun unexpectedHttpErrorDoesNotExposeResponseBody() = runTest {
        val client = HttpClient(MockEngine {
            respond("sensitive upstream diagnostics", HttpStatusCode.ServiceUnavailable)
        }) { expectSuccess = true }
        try {
            val error = assertFailsWith<GitHubAuthorizationException> {
                GitHubDeviceFlow(client, "test-client").requestCode()
            }
            assertTrue(error.message.orEmpty().contains("HTTP 503"))
            assertTrue(!error.message.orEmpty().contains("sensitive"))
        } finally {
            client.close()
        }
    }
}
