package com.germanium.shortcuts.core

/** Stable resource keys keep the core independent of Android and of the user's language. */
class Problem(val key: String, vararg val arguments: String) : IllegalArgumentException(key)
