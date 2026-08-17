package com.uhmk.pos.core.image

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest

/**
 * Product photos that travel between devices.
 *
 * Cloud Storage is not part of the plan this store runs on, so a photo taken on the owner's phone
 * used to stay there and every staff device showed an initials tile instead. A product photo is
 * small by nature — it is a thumbnail on a tile — so a downscaled JPEG rides along inside the item
 * document itself. At this size a full catalogue of photos is a couple of megabytes, comfortably
 * inside Firestore's free tier and nowhere near its 1 MiB per-document ceiling.
 *
 * The original picked image stays on the device that picked it; only the thumbnail is shared.
 */
object ItemImages {

    /** Longest edge of a shared thumbnail. Product tiles never render larger than this. */
    private const val MAX_EDGE = 320
    private const val QUALITY = 78

    /** Refuses to publish anything that would threaten the document limit. */
    private const val MAX_ENCODED_BYTES = 180_000

    /** Reads a stored photo and returns it as a base64 JPEG thumbnail, or null if unreadable. */
    fun encodeThumbnail(path: String): String? = runCatching {
        val file = File(path)
        if (!file.exists()) return null

        // Measure first so a large camera photo is never fully decoded into memory.
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        val longest = maxOf(bounds.outWidth, bounds.outHeight)
        if (longest <= 0) return null

        var sample = 1
        while (longest / sample > MAX_EDGE * 2) sample *= 2

        val decoded = BitmapFactory.decodeFile(
            path,
            BitmapFactory.Options().apply { inSampleSize = sample },
        ) ?: return null

        val scale = MAX_EDGE.toFloat() / maxOf(decoded.width, decoded.height)
        val thumb = if (scale >= 1f) decoded else Bitmap.createScaledBitmap(
            decoded,
            (decoded.width * scale).toInt().coerceAtLeast(1),
            (decoded.height * scale).toInt().coerceAtLeast(1),
            true,
        )

        val bytes = ByteArrayOutputStream().use { out ->
            thumb.compress(Bitmap.CompressFormat.JPEG, QUALITY, out)
            out.toByteArray()
        }
        if (thumb !== decoded) thumb.recycle()
        decoded.recycle()

        val encoded = Base64.encodeToString(bytes, Base64.NO_WRAP)
        encoded.takeIf { it.length <= MAX_ENCODED_BYTES }
    }.getOrNull()

    /**
     * Short fingerprint of an encoded thumbnail.
     *
     * This is what actually syncs. Comparing hashes tells a device whether the photo it holds is
     * the current one without downloading the image to find out.
     */
    fun hash(encoded: String): String = runCatching {
        MessageDigest.getInstance("SHA-256")
            .digest(encoded.toByteArray())
            .take(8)
            .joinToString("") { "%02x".format(it) }
    }.getOrDefault("")

    /** Writes a received thumbnail into app-private storage and returns its path. */
    fun writeThumbnail(context: Context, itemId: String, encoded: String): String? = runCatching {
        val bytes = Base64.decode(encoded, Base64.NO_WRAP)
        val dir = File(context.filesDir, "item_images").apply { mkdirs() }
        val target = File(dir, "$itemId-shared.jpg")
        target.outputStream().use { it.write(bytes) }
        target.absolutePath
    }.getOrNull()

    /** Deletes a stored photo, ignoring one that has already gone. */
    fun delete(path: String?) {
        path ?: return
        runCatching { File(path).delete() }
    }
}
