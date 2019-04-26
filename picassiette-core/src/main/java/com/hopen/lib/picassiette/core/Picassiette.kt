package com.hopen.lib.picassiette.core

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Environment
import androidx.collection.LruCache
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.jakewharton.disklrucache.DiskLruCache
import kotlinx.coroutines.*
import timber.log.Timber
import java.io.File
import java.io.IOException
import java.lang.Exception
import java.lang.ref.WeakReference
import java.util.concurrent.locks.Condition
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

class Picassiette<R>
private constructor(
        context: Context,
        val cacheEnabled: Boolean,
        val memCacheSize: Int,
        val diskCacheSize: Long,
        diskCacheSubDir: String,
        private val bitmapData: Boolean,
        private val onFetchData: (key: String, customParams: Any?) -> R?,
        private val getSizeOf: ((key: String, value: R) -> Int)?) {

    private val parentJob = Job()

    private val mainIOScope = CoroutineScope(IO + parentJob)

    private val memoryCache = object : LruCache<String, R>(memCacheSize) {

        override fun sizeOf(key: String, value: R): Int {
            return getSizeOf?.invoke(key, value) ?: super.sizeOf(key, value)
        }

        override fun entryRemoved(evicted: Boolean, key: String, oldValue: R, newValue: R?) {
            if (evicted) {
                mainIOScope.launch {
                    writeToDiskCache(key, oldValue)
                }
            }
        }
    }

    private var setupDiskCacheStarting = false

    private val cacheDir = getDiskCacheDir(context, diskCacheSubDir)

    private var diskLruCache: DiskLruCache? = null

    init {
        if (cacheEnabled) {
            mainIOScope.launch {
                InitDiskCacheTask().execute(cacheDir)
            }
        }
    }

    /**
     * Launch a task and binds it to the provided Target. The
     * binding is immediate if a data is found in the cache and will be done asynchronously
     * otherwise. A null data will be associated to the target if an error occurs. The
     * target must not be an anonymous object. See sample project.
     *
     * @param key The unique key to identify task.
     * @param customParams other params necessary for the task
     * @param target The target to bind the fetched data to.
     */
    fun fetch(key: String, customParams: Any?, target: Target<R>) {
        mainIOScope.launch {

            val data = if (cacheEnabled) getDataFromCache(key) else null

            if (data == null) {
                forceFetch(key, customParams, target)
            } else {
                cancelPotentialFetch(key, target)

                withContext(UI) {
                    target.onReceive(data)
                }
            }
        }
    }

    /**
     * @param key The key of the data that will be retrieved from the cache.
     * @return The cached data or null if it was not found.
     */
    private fun getDataFromCache(key: String): R? {
        return synchronized(memoryCache) {
            memoryCache[key]

            // Then try the disk reference cache
        } ?: readFromDiskCache(key)
    }

    /**
     * Adds this data to the cache.
     * @param data The newly fetched data.
     */
    private fun addDataToCache(key: String, data: R?) {
        data?.takeIf { cacheEnabled }?.let {
            synchronized(memoryCache) {
                memoryCache.put(key, it)
            }
        }
    }

    /**
     * Same as download but the image is always downloaded and the cache is not used.
     * Kept private at the moment as its interest is not clear.
     */
    private suspend fun forceFetch(key: String, customParams: Any?, target: Target<R>) {
        if (cancelPotentialFetch(key, target)) {
            val task = DataFetchTask(target)
            target.taskHandler = TaskHandler(task)

            withContext(UI) {
                target.onPreReceive(key, customParams)
            }

            task.execute(key, customParams)
        }
    }

    /**
     * Returns true if the current task has been canceled or if there was no task in
     * progress on this target.
     * Returns false if the task in progress deals with the same key. The task is not
     * stopped in that case.
     */
    private fun cancelPotentialFetch(key: String, target: Target<R>): Boolean {
        val dataFetchTask = getDataFetchTask(target)

        if (dataFetchTask != null) {
            val dataKey = dataFetchTask.key
            if (dataKey == null || dataKey != key) {
                dataFetchTask.cancel()
            } else {
                // The same key is already used in task.
                return false
            }
        }
        return true
    }

    private fun clearDiskCache() {
        diskLruCache?.apply {
            try {
                delete()
                InitDiskCacheTask().execute(cacheDir)
            } catch (e: IOException) {
                Timber.w(e, "open disk cache error: $e")
            }
        }
    }

    /**
     * Clears the data cache used internally to improve performance.
     */
    fun clearCache() {
        if (!cacheEnabled)
            return

        mainIOScope.launch {
            memoryCache.evictAll()
            clearDiskCache()
        }
    }

    /**
     * Cancel all jobs attached to the parent
     */
    fun cancelAll() {
        parentJob.cancel()
    }

    /**
     * @param target Any target
     * @return Retrieve the currently active task (if any) associated with this target.
     * null if there is no such task.
     */
    private fun getDataFetchTask(target: Target<R>?): DataFetchTask? {
        return target?.taskHandler?.dataFetchTask
    }


    internal inner class DataFetchTask(target: Target<R>) {

        private val job = Job(parentJob)

        var key: String? = null
            private set

        private val targetReference: WeakReference<Target<R>>? = WeakReference(target)

        fun cancel() {
            mainIOScope.launch {
                job.cancelAndJoin()
            }
        }

        suspend fun execute(key: String, customParams: Any?) {
            withContext(IO + job) {
                this@DataFetchTask.key = key

                val fetchedData = try {
                    try {
                        onFetchData(key, customParams)
                    } catch (e: Exception) {
                        Timber.e(e)
                        null
                    }
                } catch (e: OutOfMemoryError) {
                    memoryCache.evictAll()
                    try {
                        diskLruCache?.flush()
                    } catch (e1: IOException) {
                    }
                    null
                }

                var data = fetchedData

                if (!isActive) {
                    data = null
                }

                addDataToCache(key, fetchedData)

                targetReference?.get()?.let { target ->
                    val dataFetchTask = getDataFetchTask(target)
                    // Call onReceive only if this process is still associated with it
                    if (this@DataFetchTask === dataFetchTask) {
                        withContext(UI) {
                            target.onReceive(data)
                        }
                    }
                }
            }
        }

    }

    /**
     * A task handler that will be attached to the target while the task is in progress.
     *
     * Contains a reference to the actual process task, so that a process task can be stopped
     * if a new binding is required, and makes sure that only the last started task process can
     * bind its result, independently of the task finish order.
     */
    internal class TaskHandler<R>(dataFetchTask: Picassiette<R>.DataFetchTask) {
        private val dataFetchTaskReference: WeakReference<Picassiette<R>.DataFetchTask> = WeakReference(dataFetchTask)

        val dataFetchTask: Picassiette<R>.DataFetchTask?
            get() = dataFetchTaskReference.get()
    }

    internal inner class InitDiskCacheTask {

        fun execute(cacheDir: File) {
            setupDiskCacheStarting = true

            diskCacheLock.withLock {
                diskLruCache = DiskLruCache.open(cacheDir, 1, 1, diskCacheSize)
                setupDiskCacheStarting = false // Finished initialization
                diskCacheLockCondition.signalAll() // Wake any waiting threads
            }
        }
    }

    @Synchronized
    private fun writeToDiskCache(key: String, value: R): Boolean {
        var isOk = false

        diskLruCache?.let {
            val cacheKey = key.md5()
            try {
                it.edit(cacheKey)?.let { editor ->
                    if (bitmapData) {
                        val out = editor.newOutputStream(0)
                        (value as Bitmap).compress(Bitmap.CompressFormat.PNG, 95, out)
                        out.close()
                    } else {
                        editor.set(0, jsonConverter.toJson(value))
                    }
                    editor.commit()
                    isOk = true
                }
            } catch (e: IOException) {
                isOk = false
                Timber.w(e, "write disk lru cache error:$e")
            }
        } ?: Timber.w("disk lru cache is null")

        return isOk
    }

    @Synchronized
    private fun readFromDiskCache(key: String): R? {
        var data: R? = null
        diskLruCache?.let { diskLruCache ->
            val cacheKey = key.md5()
            try {
                diskLruCache.get(cacheKey)?.let { snapshot ->
                    if (bitmapData) {
                        val `in` = snapshot.getInputStream(0)
                        data = BitmapFactory.decodeStream(`in`) as R
                        `in`.close()
                    } else {
                        data = jsonConverter.fromJson(snapshot.getString(0), object : TypeToken<R>() {}.type)
                    }
                    snapshot.close()
                }
            } catch (e: IOException) {
                Timber.w(e, "Read disk lru cache error: $e")
            }
        } ?: Timber.w("Disk lru cache is null")

        return data
    }

    class Config<R>(val context: Context) {

        var cacheEnabled = true

        var memCacheSize = getDefaultMemCacheSize()

        var diskCacheSize = DEFAULT_DISK_CACHE_SIZE

        var diskCacheSubDir = DEFAULT_DISK_CACHE_SUB_DIR

        var onFetchData = fun(key: String, customData: Any?): R? = null

        var getSizeOf: ((key: String, value: R) -> Int)? = null

        companion object {

            private const val DEFAULT_DISK_CACHE_SIZE = 1_024 * 1_024 * 10L // 10MB

            private const val DEFAULT_DISK_CACHE_SUB_DIR = "picache"

            // Use 1/8th of the available memory
            private fun getDefaultMemCacheSize() = (Runtime.getRuntime().maxMemory() / 1024).toInt() / 8

        }

    }

    companion object {

        private val UI = Dispatchers.Main

        private val IO = Dispatchers.IO

        private val jsonConverter = Gson()

        private val diskCacheLock = ReentrantLock()

        private val diskCacheLockCondition: Condition = diskCacheLock.newCondition()

        // Creates a unique subdirectory of the designated app cache directory. Tries to use external
        // but if not mounted, falls back on internal storage.
        private fun getDiskCacheDir(context: Context, uniqueName: String): File {
            // Check if media is mounted or storage is built-in, if so, try and use external cache dir
            // otherwise use internal cache dir
            val cachePath =
                    if (Environment.MEDIA_MOUNTED == Environment.getExternalStorageState()
                            || !Environment.isExternalStorageRemovable()) {
                        context.externalCacheDir?.path ?: context.cacheDir.path
                    } else {
                        context.cacheDir.path
                    }

            return File(cachePath + File.separator + uniqueName)
        }

        fun <R> newInstance(config: Config<R>, clazz: Class<R>): Picassiette<R> {

            val isBitmapData = Bitmap::class.java.isAssignableFrom(clazz)

            return with(config) {
                if (isBitmapData && getSizeOf == null) {
                    getSizeOf = fun(_: String, bitmap: R): Int = (bitmap as Bitmap).byteCount / 1024
                }
                Picassiette(
                        context,
                        cacheEnabled,
                        memCacheSize,
                        diskCacheSize,
                        diskCacheSubDir,
                        isBitmapData,
                        onFetchData,
                        getSizeOf
                )
            }
        }

    }

}