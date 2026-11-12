package com.example.musictage.utils

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import java.io.File
import java.io.FileOutputStream

/**
 * 歌曲内置封面与本地缓存处理工具 喵~
 */
object EmbeddedArtwork {
    private const val TAG = "EmbeddedArtwork"

    /**
     * 从音频文件中抽取内嵌封面图片并保存到缓存目录
     */
    fun extractArtwork(context: Context, audioPath: String): File? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(audioPath)
            val embedPic = retriever.embeddedPicture ?: return null
            val cacheDir = File(context.cacheDirectory(), "artworks")
            if (!cacheDir.exists()) cacheDir.mkdirs()

            val fileName = "art_${audioPath.hashCode()}.jpg"
            val targetFile = File(cacheDir, fileName)
            FileOutputStream(targetFile).use { out ->
                out.write(embedPic)
            }
            targetFile
        } catch (e: Exception) {
            Log.e(TAG, "抽取内嵌封面失败: ${e.message}")
            null
        } finally {
            try {
                retriever.release()
            } catch (_: Exception) {}
        }
    }

    private fun Context.cacheDirectory(): File {
        return externalCacheDir ?: cacheDir
    }
}
