package org.p23q.shoppinglist.core

import kotlinx.coroutines.CoroutineDispatcher

/** kotlinx.coroutines' IO dispatcher, which common code cannot name: a member on the JVM, an extension on native. */
internal expect val ioDispatcher: CoroutineDispatcher
