package com.hopen.lib.sample.picassiette

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream

object BitmapDownloader {

    private val httpClient = OkHttpClient()

    fun downloadBitmap(url: String, width: Int? = null, height: Int? = null): Bitmap? {
        val request = Request.Builder().url(url).build()

        return httpClient.newCall(request).execute().takeIf {
            it.isSuccessful
        }?.body()?.byteStream()?.use {
            return if (width != null && height != null)
                decodeSampledBitmapFromStream(it, width, height)
            else
                BitmapFactory.decodeStream(it)
        }
    }

    private fun decodeSampledBitmapFromStream(
            stream: InputStream,
            reqWidth: Int,
            reqHeight: Int
    ): Bitmap? {

        val byteOutputStream = ByteArrayOutputStream()
        byteOutputStream.use { output ->
            stream.copyTo(output)
        }

        val byteInputStream = ByteArrayInputStream(byteOutputStream.toByteArray())

        // First decode with inJustDecodeBounds=true to check dimensions
        return BitmapFactory.Options().run {
            inJustDecodeBounds = true

            BitmapFactory.decodeStream(byteInputStream, null, this)

            // Calculate inSampleSize
            inSampleSize = calculateInSampleSize(this, reqWidth, reqHeight)

            // Decode bitmap with inSampleSize set
            inJustDecodeBounds = false

            byteInputStream.reset()
            BitmapFactory.decodeStream(byteInputStream, null, this)
        }
    }

    private fun calculateInSampleSize(options: BitmapFactory.Options, reqWidth: Int, reqHeight: Int): Int {
        // Raw height and width of image
        val (height: Int, width: Int) = options.run { outHeight to outWidth }
        var inSampleSize = 1

        if (height > reqHeight || width > reqWidth) {

            val halfHeight: Int = height / 2
            val halfWidth: Int = width / 2

            // Calculate the largest inSampleSize value that is a power of 2 and keeps both
            // height and width larger than the requested height and width.
            while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) {
                inSampleSize *= 2
            }
        }

        return inSampleSize
    }

}