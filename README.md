# Picassiette
A light-weight library to make asynchronous task launched from a RecyclerView (or ListView). It sounds like Picasso or Glide. The difference with Picassiette, it can support custom types of data (not only Bitmap). If you choose Bitmap as the fetched data, stay with Picasso or Glide as they are more advanced library. But if you want support also custom data in your RecyclerView's item view, use Picassiette.

## Under the hood
* Androidx or Android support
* Kotlin
* Coroutines
* Android LruCache
* Jake Wharton's DiskLruCache

## Installation
Add to your app `build.gradle` :
* Project with androidx 
````
dependencies {
    implementation 'com.hopen.lib:picassiette-core:1.0.0'
}
````

## How to use
Define your RecyclerView.Adapter :
```
class ItemAdapter(context: Context) : RecyclerView.Adapter<ItemViewHolder>() {

    var tracks: List<Track> = emptyList()

    private val picassiette = Picassiette.newInstance(Config<Favorite>(context).apply {
        onFetchData = this@ItemAdapter::fetchTrackFavorite
    }, Favorite::class.java)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ItemViewHolder {
        return ItemViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.item_view, parent, false))
    }

    override fun getItemCount(): Int {
        return tracks.size
    }

    override fun onBindViewHolder(holder: ItemViewHolder, position: Int) {
        with(tracks[position]) {
            holder.tvLabel.text = name
            picassiette.fetch(trackId, /* null or custom params */, holder.target)
        }
    }

    @WorkerThread
    private fun fetchTrackFavorite(key: String, customParams: Any?): Favorite? {
        val trackId = key
        // Call your service to fetch on a remote server or a local database
        val result: Favorite? = ...
        return result
    }

}

class ItemViewHolder(view: View) : RecyclerView.ViewHolder(view) {

    val tvLabel: TextView = view.label

    val imgFavorite: ImageView = view.favorite

    val target = object : Target<Favorite>() {

        override fun onPreReceive(key: String, customParams: Any?) {
            // Reset your view here
            imgFavorite.setImageResource(0)
        }

        override fun onReceive(data: Favorite?) {
            val imgResId = if (data.value) R.drawable.ic_heart else 0
            imgFavorite.setImageResource(imgResId)
        }
    }

}
```
Then attach your adapter to your recycler view.
In the snippet above, there is two data models `Track` and `Favorite` :
```
data class Track(val trackId: String, val name)
data class Favorite(val value: Boolean) // The custom data fetched by Picassiette
```
Notice that the target instance is binded to the ViewHolder. Do not call `Picassiette.fetch()` with the target which is from an anonymous class :
```
picassiette.fetch(key, customParams, object : Target<Favorite>() {

        override fun onPreReceive(key: String, customParams: Any?) {
        }

        override fun onReceive(data: Favorite?) {
        }
    })
```
because Picassiette holds the instance in a WeakReference to avoid memory leak and the Garbage Collector can clear the instance easly.
If you need more details, there is a sample app in the repository (using a bitmap as custom data).

## Picassiette configuration
You can customize picassiette behaviour with a `Picassiette.Config` instance :
```
class Config<R>(val context: Context) {

        var cacheEnabled: Boolean // set to false if you, for example, use a local database to cache response instead of the internal picassiette cache

        var memCacheSize: Int = // memory cache size

        var diskCacheSize: Long = // disk cache size

        var diskCacheSubDir: String = // name of the sub directory under cache directory

        // Your implementation logic to make long running operation to fetch data. And save also the response in a local database.
        var onFetchData = fun(key: String, customParams: Any?): R? = null

        // To let the internal memory cache to know about the weight of
        // the fetched data. You don't need to set it most of the time.
        var getSizeOf: ((key: String, data: R) -> Int)? = null
    }
```
## Contributing
Feel free to contribute if you found bugs, performance issues or improvements. Send me a pull requests :)
## License
Apache 2.0. See the [LICENSE](https://github.com/mrHerintsoaHasina/picassiette/blob/master/LICENSE.md) file for details.
```
   Copyright 2019 Hasina R.

   Licensed under the Apache License, Version 2.0 (the "License");
   you may not use this file except in compliance with the License.
   You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.`
```
