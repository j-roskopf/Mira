package com.joetr.mira.media

/**
 * Whether an upload can stream straight from the preserved local file.
 *
 * Desktop and Android hand the file to the HTTP engine, so a recording never has to fit in the
 * app's heap. iOS and web upload from bytes.
 */
public expect val supportsStreamingFileUpload: Boolean
