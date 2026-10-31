package com.joetr.mira.network

import io.ktor.client.HttpClient

public expect fun createPlatformHttpClient(): HttpClient

/** API client for JSON endpoints; non-2xx responses throw. */
public fun createMiraHttpClient(): HttpClient = createPlatformHttpClient()

/** Binary/image client for Coil and image staging; failures return to the caller. */
public expect fun createMiraImageHttpClient(): HttpClient
