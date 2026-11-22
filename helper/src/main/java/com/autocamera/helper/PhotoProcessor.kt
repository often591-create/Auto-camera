package com.autocamera.helper

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max

/** 读取照片 → 滤镜 → 水印 → 另存新照片（保留拍摄信息，原片不动）。 */
object PhotoProcessor {

    const val FOLDER = "DCIM/AutoCamera"

    private val COPY_TAGS = arrayOf(
        ExifInterface.TAG_MAKE, ExifInterface.TAG_MODEL, ExifInterface.TAG_DATETIME, ExifInterface.TAG_DATETIME_ORIGINAL,
        ExifInterface.TAG_DATETIME_DIGITIZED, ExifInterface.TAG_OFFSET_TIME_ORIGINAL, ExifInterface.TAG_F_NUMBER,
        ExifInterface.TAG_EXPOSURE_TIME, ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY, ExifInterface.TAG_FOCAL_LENGTH,
        ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM, ExifInterface.TAG_EXPOSURE_BIAS_VALUE, ExifInterface.TAG_WHITE_BALANCE,
        ExifInterface.TAG_FLASH, ExifInterface.TAG_LENS_MAKE, ExifInterface.TAG_LENS_MODEL,
        ExifInterface.TAG_GPS_LATITUDE, ExifInterface.TAG_GPS_LATITUDE_REF, ExifInterface.TAG_GPS_LONGITUDE,
        ExifInterface.TAG_GPS_LONGITUDE_REF, ExifInterface.TAG_GPS_ALTITUDE, ExifInterface.TAG_GPS_ALTITUDE_REF
    )

    fun process(ctx: Context, uri: Uri, prefs: HelperPrefs, fallbackModel: String): Uri? {
        val cr = ctx.contentResolver
        val exif = try { cr.openInputStream(uri)?.use { ExifInterface(it) } } catch (e: Exception) { null }

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0) return null
        val maxSide = if (prefs.fullRes) 8192 else 4096
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / sample > maxSide) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample; inMutable = true; inPreferredConfig = Bitmap.Config.ARGB_8888 }
        var bmp = cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) } ?: return null

        val rot = when (exif?.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
        if (rot != 0f) {
            val r = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(rot) }, true)
            if (r != bmp) bmp.recycle()
            bmp = if (r.isMutable) r else r.copy(Bitmap.Config.ARGB_8888, true)
        }

        Looks.apply(bmp, Looks.byId(prefs.look))
        val info = Watermark.info(exif, prefs, fallbackModel)
        val out = Watermark.apply(bmp, info, prefs)

        val tmp = File(ctx.cacheDir, "out_${System.nanoTime()}.jpg")
        FileOutputStream(tmp).use { out.compress(Bitmap.CompressFormat.JPEG, 95, it) }
        out.recycle()
        try {
            val e = ExifInterface(tmp.absolutePath)
            if (exif != null) for (t in COPY_TAGS) exif.getAttribute(t)?.let { e.setAttribute(t, it) }
            e.setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())
            e.setAttribute(ExifInterface.TAG_SOFTWARE, "Auto Camera")
            e.saveAttributes()
        } catch (_: Exception) { }

        val srcName = queryName(ctx, uri)?.substringBeforeLast('.') ?: "IMG_${System.currentTimeMillis()}"
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "${srcName}_AC.jpg")
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= 29) put(MediaStore.MediaColumns.RELATIVE_PATH, FOLDER)
        }
        val dest = cr.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        if (dest != null) {
            cr.openOutputStream(dest)?.use { os -> tmp.inputStream().use { it.copyTo(os) } }
        }
        tmp.delete()
        return dest
    }

    private fun queryName(ctx: Context, uri: Uri): String? = try {
        ctx.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }
    } catch (e: Exception) { null }

    /** 小图预览用。 */
    fun preview(src: Bitmap, look: Look): Bitmap {
        val b = src.copy(Bitmap.Config.ARGB_8888, true)
        Looks.apply(b, look)
        return b
    }
}
