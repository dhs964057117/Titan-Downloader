package com.awesome.dhs.tools.downloader.interfac


/**
 * FileName: ILogger
 * Author: haosen
 * Date: 10/2/2025 8:52 PM
 * Description: The logging interface for the downloader framework.
 * Allows the host app to inject its own logging implementation.
 **/

interface ILogger {
    fun d(tag: String, message: String)
    fun e(tag: String, message: String, throwable: Throwable? = null)
}

/**
 * A default, no-op logger that does nothing.
 * This is used if the user does not provide a custom logger.
 */
internal class NoOpLogger : ILogger {
    override fun d(tag: String, message: String) { /* No Operation */
    }

    override fun e(tag: String, message: String, throwable: Throwable?) { /* No Operation */
    }
}