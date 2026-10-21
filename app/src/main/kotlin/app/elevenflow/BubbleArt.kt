package app.elevenflow

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import java.io.File

/**
 * The bubble's two images: idle and recording. Either the built-in robot, or a picture you chose,
 * cropped to a square, with its "recording" version made in the app by mapping it onto the same
 * purple ramp the robot uses (see art/make_bubble.py).
 */
object BubbleArt {
    private const val SIZE = 384

    private fun offFile(ctx: Context) = File(ctx.filesDir, "bubble_custom_off.png")
    private fun onFile(ctx: Context) = File(ctx.filesDir, "bubble_custom_on.png")

    fun hasCustom(ctx: Context) = offFile(ctx).exists() && onFile(ctx).exists()

    /** (idle, recording) bitmaps: your image if you chose one, else the robot. */
    fun load(ctx: Context): Pair<Bitmap, Bitmap> {
        if (hasCustom(ctx)) {
            val off = BitmapFactory.decodeFile(offFile(ctx).path)
            val on = BitmapFactory.decodeFile(onFile(ctx).path)
            if (off != null && on != null) return off to on
        }
        return BitmapFactory.decodeResource(ctx.resources, R.drawable.bubble_off) to
            BitmapFactory.decodeResource(ctx.resources, R.drawable.bubble_on)
    }

    /** Use the picture at [uri]. Blocking (decodes and writes files): call off the main thread. */
    fun setFrom(ctx: Context, uri: Uri): Boolean {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return false
        var sample = 1
        while (minOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= SIZE) sample *= 2
        val src = ctx.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: return false

        val side = minOf(src.width, src.height)   // centre square crop
        val square = Bitmap.createBitmap(src, (src.width - side) / 2, (src.height - side) / 2, side, side)
        val off = Bitmap.createScaledBitmap(square, SIZE, SIZE, true)
        val on = purple(off)
        offFile(ctx).outputStream().use { off.compress(Bitmap.CompressFormat.PNG, 100, it) }
        onFile(ctx).outputStream().use { on.compress(Bitmap.CompressFormat.PNG, 100, it) }
        changed(ctx)
        return true
    }

    fun reset(ctx: Context) {
        offFile(ctx).delete(); onFile(ctx).delete()
        changed(ctx)
    }

    /** The bubble listens for this preference to reload its images. */
    private fun changed(ctx: Context) = Settings.put(ctx, "bubbleArt", System.currentTimeMillis().toString())

    /** Brightness mapped onto dark violet → vivid purple → pale lilac; transparency kept. */
    fun purple(src: Bitmap): Bitmap {
        val w = src.width; val h = src.height
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)
        val dark = intArrayOf(0x1E, 0x05, 0x50); val mid = intArrayOf(0x7C, 0x2D, 0xF0); val light = intArrayOf(0xF3, 0xE8, 0xFF)
        val midpoint = 110
        for (i in px.indices) {
            val c = px[i]
            val l = (Color.red(c) * 299 + Color.green(c) * 587 + Color.blue(c) * 114) / 1000
            val (a, b, t) = if (l <= midpoint) Triple(dark, mid, l / midpoint.toFloat())
                            else Triple(mid, light, (l - midpoint) / (255f - midpoint))
            px[i] = Color.argb(Color.alpha(c),
                (a[0] + (b[0] - a[0]) * t).toInt(), (a[1] + (b[1] - a[1]) * t).toInt(), (a[2] + (b[2] - a[2]) * t).toInt())
        }
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
    }
}
