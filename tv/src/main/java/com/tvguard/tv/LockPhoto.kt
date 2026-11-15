package com.tvguard.tv

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Shader
import android.util.Base64
import java.io.File

/**
 * The photos a parent picks on the phone for the weekday limit and bedtime lock screens.
 * It is kept only in this app's private storage on the TV: the phone sends it through the
 * family's Firebase, and the TV deletes it from there as soon as it is saved here.
 */
object LockPhoto {
    private const val FILE = "lock_photo.jpg"
    private const val BEDTIME_FILE = "bedtime_photo.jpg"
    /** Big enough for a 4K TV's circle; the phone sends 480 px. */
    private const val MAX_BYTES = 1_000_000

    private fun name(bedtime: Boolean) = if (bedtime) BEDTIME_FILE else FILE

    private fun file(context: Context, bedtime: Boolean) = File(context.filesDir, name(bedtime))

    fun exists(context: Context, bedtime: Boolean = false) = file(context, bedtime).isFile

    /** Saves a photo sent by the phone. Returns false if it isn't a readable image. */
    fun save(context: Context, base64: String, bedtime: Boolean = false): Boolean {
        val bytes = runCatching { Base64.decode(base64, Base64.DEFAULT) }.getOrNull() ?: return false
        if (bytes.isEmpty() || bytes.size > MAX_BYTES) return false
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return false
        val target = file(context, bedtime)
        val temp = File(context.filesDir, "${name(bedtime)}.tmp")
        return runCatching {
            temp.writeBytes(bytes)
            temp.renameTo(target) || run { temp.copyTo(target, overwrite = true); temp.delete() }
        }.getOrDefault(false)
    }

    fun delete(context: Context, bedtime: Boolean = false) {
        file(context, bedtime).delete()
    }

    /**
     * The photo cropped to a circle [sizePx] wide, with a white ring like the rest of the
     * lock screen, or null when there is no photo.
     */
    fun circle(context: Context, sizePx: Int, ringPx: Int, bedtime: Boolean = false): Bitmap? {
        val f = file(context, bedtime)
        if (!f.isFile) return null
        val photo = BitmapFactory.decodeFile(f.path) ?: return null
        val out = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val center = sizePx / 2f
        val inner = center - ringPx

        // Centre-crop the photo into the inner circle.
        val side = minOf(photo.width, photo.height).toFloat()
        val scale = (inner * 2) / side
        val matrix = Matrix().apply {
            postTranslate(-(photo.width - side) / 2f, -(photo.height - side) / 2f)
            postScale(scale, scale)
            postTranslate(ringPx.toFloat(), ringPx.toFloat())
        }
        val shader = BitmapShader(photo, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply { setLocalMatrix(matrix) }
        canvas.drawCircle(center, center, center, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE })
        canvas.drawCircle(center, center, inner, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply { this.shader = shader })
        photo.recycle()
        return out
    }
}
