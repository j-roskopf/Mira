package com.joetr.mira.platform

import java.awt.Graphics2D
import java.awt.Image
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

public actual fun currentTimeMillis(): Long = System.currentTimeMillis()

public actual fun isNetworkAvailable(): Boolean = true

public actual fun openUrl(url: String) {
    java.awt.Desktop.getDesktop().browse(java.net.URI(url))
}

public actual fun copyText(text: String) {
    val clipboard = java.awt.Toolkit.getDefaultToolkit().systemClipboard
    clipboard.setContents(java.awt.datatransfer.StringSelection(text), null)
}

public actual fun shareText(text: String) = copyText(text)

public actual fun keepScreenOn(enabled: Boolean) = Unit

public actual fun hapticSuccess() = Unit

public actual fun playTimerCompleteSound() = Unit

public actual class ImageCapture {
    public actual companion object {
        public actual val isAvailable: Boolean = false
    }
}

public actual suspend fun resizeImage(bytes: ByteArray, maxLongEdge: Int, quality: Int): ByteArray {
    return runCatching {
        val source = decodeDesktopImage(bytes) ?: return@runCatching bytes
        val longEdge = maxOf(source.width, source.height).coerceAtLeast(1)
        val scale = minOf(1f, maxLongEdge.toFloat() / longEdge)
        val targetWidth = (source.width * scale).toInt().coerceAtLeast(1)
        val targetHeight = (source.height * scale).toInt().coerceAtLeast(1)
        val scaled = source.getScaledInstance(targetWidth, targetHeight, Image.SCALE_SMOOTH)
        val output = java.awt.image.BufferedImage(targetWidth, targetHeight, java.awt.image.BufferedImage.TYPE_INT_RGB)
        val graphics = output.createGraphics() as Graphics2D
        graphics.drawImage(scaled, 0, 0, null)
        graphics.dispose()
        encodeJpeg(output, quality)
    }.getOrElse { bytes }
}

private fun decodeDesktopImage(bytes: ByteArray): java.awt.image.BufferedImage? {
    ImageIO.read(ByteArrayInputStream(bytes))?.let { return it }
    if (isMacOs() && isHeicImage(bytes)) {
        return decodeHeicWithSips(bytes)
    }
    return null
}

private fun isMacOs(): Boolean =
    System.getProperty("os.name").orEmpty().lowercase().contains("mac")

private fun decodeHeicWithSips(bytes: ByteArray): java.awt.image.BufferedImage? {
    val tempDir = java.nio.file.Files.createTempDirectory("mira-heic")
    return try {
        val input = tempDir.resolve("input.heic")
        val output = tempDir.resolve("output.jpg")
        java.nio.file.Files.write(input, bytes)
        val process = ProcessBuilder(
            "sips",
            "-s",
            "format",
            "jpeg",
            input.toString(),
            "--out",
            output.toString(),
        ).redirectErrorStream(true).start()
        if (process.waitFor() != 0 || !output.toFile().exists()) return null
        ImageIO.read(output.toFile())
    } finally {
        tempDir.toFile().deleteRecursively()
    }
}

private fun encodeJpeg(image: java.awt.image.BufferedImage, quality: Int): ByteArray {
    val out = ByteArrayOutputStream()
    val writers = ImageIO.getImageWritersByFormatName("jpeg")
    if (!writers.hasNext()) error("No JPEG writer")
    val writer = writers.next()
    val params = writer.defaultWriteParam
    if (params.canWriteCompressed()) {
        params.compressionMode = javax.imageio.ImageWriteParam.MODE_EXPLICIT
        params.compressionQuality = quality.coerceIn(0, 100).toFloat() / 100f
    }
    writer.output = ImageIO.createImageOutputStream(out)
    writer.write(null, javax.imageio.IIOImage(image, null, null), params)
    writer.dispose()
    return out.toByteArray()
}

public actual suspend fun readClipboardText(): String? {
    val clipboard = java.awt.Toolkit.getDefaultToolkit().systemClipboard
    val data = clipboard.getData(java.awt.datatransfer.DataFlavor.stringFlavor) as? String
    return data?.trim()?.takeIf { it.isNotEmpty() }
}

public actual fun isDebugBuild(): Boolean = false

public actual fun platformName(): String = "desktop"
