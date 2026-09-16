package dev.dertyp.services.podcast

class FeedParseException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

class FeedFetchException(message: String, val status: Int? = null) : RuntimeException(message)

class FeedTooLargeException(message: String) : RuntimeException(message)
