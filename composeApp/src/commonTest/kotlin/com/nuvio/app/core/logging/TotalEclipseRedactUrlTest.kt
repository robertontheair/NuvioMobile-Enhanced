package com.nuvio.app.core.logging

import kotlin.test.Test
import kotlin.test.assertEquals

class TotalEclipseRedactUrlTest {
    @Test fun xtreamLiveHidesUserAndPassword() = assertEquals(
        "http://example.com:8080/live/<user>/<pass>/123.ts",
        InAppLogger.redactUrl("http://example.com:8080/live/alice/s3cret/123.ts"),
    )

    @Test fun bareXtreamHidesUserAndPassword() = assertEquals(
        "http://example.com/<user>/<pass>/985331",
        InAppLogger.redactUrl("http://example.com/alice/s3cret/985331"),
    )

    @Test fun remoteLiveTvKeyIsHidden() = assertEquals(
        "https://live.example.org/k/<key>/playlist.m3u",
        InAppLogger.redactUrl("https://live.example.org/k/abc123/playlist.m3u?x=1"),
    )

    @Test fun longTokensAreHidden() = assertEquals(
        "https://cdn.example.com/dl/<token>/file.mkv",
        InAppLogger.redactUrl("https://cdn.example.com/dl/ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789/file.mkv"),
    )

    @Test fun ordinaryPathsStay() = assertEquals(
        "https://api.trakt.tv/sync/favorites/movies",
        InAppLogger.redactUrl("https://api.trakt.tv/sync/favorites/movies"),
    )
}
