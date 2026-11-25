package com.nuvio.app.core.ui

import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.memory.MemoryCache
import okio.Path.Companion.toPath
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.UIKit.UIApplicationDidReceiveMemoryWarningNotification
import platform.UIKit.UIDevice
import platform.UIKit.UIUserInterfaceIdiomPhone

private const val IosImageDiskCacheBytes = 300L * 1024 * 1024
private const val IosImageMemoryCacheBytes = 256L * 1024 * 1024
private const val IosPhoneImageMemoryCacheBytes = 160L * 1024 * 1024
private const val IosImageMemoryCacheDuringPlaybackBytes = 64L * 1024 * 1024

private val isPhone: Boolean by lazy {
    UIDevice.currentDevice.userInterfaceIdiom == UIUserInterfaceIdiomPhone
}

private var memoryWarningObserverInstalled = false

internal actual fun ImageLoader.Builder.configurePlatformImageLoader(): ImageLoader.Builder {
    installMemoryWarningObserver()
    return memoryCache {
        MemoryCache.Builder()
            .maxSizeBytes(if (isPhone) IosPhoneImageMemoryCacheBytes else IosImageMemoryCacheBytes)
            .build()
    }.diskCache {
        // Total Eclipse: artwork kept in Caches (Coil's default is the temporary folder, which
        // iOS empties often), so posters and backdrops aren't downloaded again.
        coil3.disk.DiskCache.Builder()
            .directory("${platform.Foundation.NSHomeDirectory()}/Library/Caches/ImageCache".toPath())
            .maxSizeBytes(IosImageDiskCacheBytes)
            .build()
    }
}

internal actual val platformProvidesImageLoader: Boolean = false

private fun installMemoryWarningObserver() {
    if (memoryWarningObserverInstalled) return
    memoryWarningObserverInstalled = true
    NSNotificationCenter.defaultCenter.addObserverForName(
        name = UIApplicationDidReceiveMemoryWarningNotification,
        `object` = null,
        queue = NSOperationQueue.mainQueue,
    ) { _ ->
        SingletonImageLoader.get(PlatformContext.INSTANCE).memoryCache?.clear()
    }
}

@OptIn(kotlin.native.runtime.NativeRuntimeApi::class)
fun trimImageMemoryForPlayback() {
    val memoryCache = SingletonImageLoader.get(PlatformContext.INSTANCE).memoryCache
    if (isPhone) memoryCache?.clear() else memoryCache?.trimToSize(IosImageMemoryCacheDuringPlaybackBytes)
    suspendAnimatedImagesForPlayback()
    kotlin.native.runtime.GC.collect()
    if (isPhone) kotlin.native.runtime.GC.collect()
}

@OptIn(kotlin.native.runtime.NativeRuntimeApi::class)
fun collectAfterPlayback() {
    kotlin.native.runtime.GC.collect()
    resumeAnimatedImagesAfterPlayback()
}
