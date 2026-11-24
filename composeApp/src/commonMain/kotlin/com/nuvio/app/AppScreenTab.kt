package com.nuvio.app

import com.nuvio.app.core.ui.NativeNavigationTab

enum class AppScreenTab {
    Home,
    // Total Eclipse: the Apple TVs' Movies / Shows tabs (see TotalEclipseBrowse).
    Movies,
    Shows,
    Search,
    Library,
    LiveTv,
    Settings,
    ;

    companion object {
        fun fromName(name: String): AppScreenTab =
            entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: Home
    }
}

internal fun AppScreenTab.toNativeNavigationTab(): NativeNavigationTab = when (this) {
    AppScreenTab.Home -> NativeNavigationTab.Home
    AppScreenTab.Movies -> NativeNavigationTab.Movies
    AppScreenTab.Shows -> NativeNavigationTab.Shows
    AppScreenTab.Search -> NativeNavigationTab.Search
    AppScreenTab.Library -> NativeNavigationTab.Library
    AppScreenTab.LiveTv -> NativeNavigationTab.LiveTv
    AppScreenTab.Settings -> NativeNavigationTab.Settings
}

internal fun NativeNavigationTab.toAppScreenTab(): AppScreenTab = when (this) {
    NativeNavigationTab.Home -> AppScreenTab.Home
    NativeNavigationTab.Movies -> AppScreenTab.Movies
    NativeNavigationTab.Shows -> AppScreenTab.Shows
    NativeNavigationTab.Search -> AppScreenTab.Search
    NativeNavigationTab.Library -> AppScreenTab.Library
    NativeNavigationTab.LiveTv -> AppScreenTab.LiveTv
    NativeNavigationTab.Settings -> AppScreenTab.Settings
}
