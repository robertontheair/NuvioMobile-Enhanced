package com.nuvio.app.totaleclipse

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSCachesDirectory
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.NSUserDomainMask
import platform.Foundation.create
import platform.Foundation.stringWithContentsOfFile
import platform.Foundation.writeToFile

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
internal actual object TotalEclipseLogFile {
    private val path: String? by lazy {
        (NSSearchPathForDirectoriesInDomains(NSCachesDirectory, NSUserDomainMask, true).firstOrNull() as? String)
            ?.let { "$it/TotalEclipseLog.txt" }
    }

    actual fun read(): String? {
        val p = path ?: return null
        return NSString.stringWithContentsOfFile(p, NSUTF8StringEncoding, null)
    }

    actual fun write(text: String) {
        val p = path ?: return
        NSString.create(string = text).writeToFile(p, atomically = true, encoding = NSUTF8StringEncoding, error = null)
    }
}
