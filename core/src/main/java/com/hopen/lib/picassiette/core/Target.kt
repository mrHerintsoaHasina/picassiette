package com.hopen.lib.picassiette.core

import androidx.annotation.UiThread

/**
 * Class used to be notified from {@code Picassiette.fetch} process.
 *
 * Don't use directly an instance from an anonymous class
 * to the method {@code Picassiette.fetch}. The Garbage Collector
 * can clear the instance easly as {@code Picassiette} keeps it in
 * a WeakReference to avoid memory leaks. See the sample
 * on how to attach the instance to a RecyclerView.ViewHolder
 */
abstract class Target<R> {

    internal var taskHandler: Picassiette.TaskHandler<R>? = null

    /**
     * Called before [onReceive]. You can reset
     * any view which will be used to bind the data
     *
     * @param key The same key from {@code Picassiette.fetch}
     * @param customParams The same custom params from {@code Picassiette.fetch}
     */
    @UiThread
    open fun onPreReceive(key: String, customParams: Any?) {
        // e.g: Reset any view which will be used to bind data
    }

    /**
     * Called when data was fetched from a long
     * running process.
     *
     * @param data The data as result
     */
    @UiThread
    abstract fun onReceive(data: R?)

}