package com.myfinance.app.ui

import kotlinx.coroutines.flow.SharingStarted

/**
 * For views worked out from all the entries: kept up to date only while a screen shows them (and 5 seconds
 * after, so turning the phone or switching tabs doesn't start over). The last result stays, so coming back
 * shows it at once while it is brought up to date. Before, every view opened once kept being worked out again
 * on every change to any entry, for as long as the app ran.
 */
internal val WhileShown: SharingStarted = SharingStarted.WhileSubscribed(5_000)
