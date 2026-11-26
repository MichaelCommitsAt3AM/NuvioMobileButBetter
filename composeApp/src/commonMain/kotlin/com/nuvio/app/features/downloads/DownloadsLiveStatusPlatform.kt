package com.nuvio.app.features.downloads

internal expect object DownloadsLiveStatusPlatform {
    fun onItemsChanged(items: List<DownloadItem>)

    /** Tells the user [item] was not downloaded because no version of it plays on this device. */
    fun notifyNoCompatibleVersion(item: DownloadItem, message: String)
}
