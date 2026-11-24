package com.nuvio.app.totaleclipse

internal actual suspend fun <T> withBackgroundTime(name: String, block: suspend () -> T): T = block()
