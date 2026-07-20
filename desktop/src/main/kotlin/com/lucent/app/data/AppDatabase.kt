package com.lucent.app.data

import android.content.Context

/**
 * Desktop twin of the Android AppDatabase: same class name, same dao accessors, same
 * `getInstance(context)` singleton — but backed by the hand-rolled SQLite layer in [Db] instead of
 * Room. Everything above this line of the stack (tools, backup, screens) is unaware of the swap.
 */
class AppDatabase private constructor(db: Db) {

    private val notes = NoteDao(db)
    private val tasks = TaskDao(db)
    private val versions = NoteVersionDao(db)
    private val chats = ChatDao(db)
    private val conversations = ChatConversationDao(db)

    fun noteDao(): NoteDao = notes
    fun taskDao(): TaskDao = tasks
    fun noteVersionDao(): NoteVersionDao = versions
    fun chatDao(): ChatDao = chats
    fun chatConversationDao(): ChatConversationDao = conversations

    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            INSTANCE?.let { return it }
            synchronized(this) {
                INSTANCE?.let { return it }
                val created = AppDatabase(Db.open(context.applicationContext))
                INSTANCE = created
                return created
            }
        }
    }
}
