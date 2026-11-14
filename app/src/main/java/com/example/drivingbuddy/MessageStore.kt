package com.example.drivingbuddy

/** Holds received SMS texts until the user double-blinks to hear them. */
object MessageStore {
    private val queue = ArrayDeque<String>()

    @Synchronized
    fun add(message: String) {
        queue.addLast(message)
    }

    /** Returns all waiting messages (oldest first) and empties the store. */
    @Synchronized
    fun takeAll(): List<String> {
        val all = queue.toList()
        queue.clear()
        return all
    }

    @Synchronized
    fun count(): Int = queue.size
}
