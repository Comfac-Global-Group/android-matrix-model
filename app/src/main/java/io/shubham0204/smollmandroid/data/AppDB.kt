package io.shubham0204.smollmandroid.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.room.migration.Migration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runBlocking
import org.koin.core.annotation.Single
import java.util.Date

@Database(
    entities = [Chat::class, ChatMessage::class, LLMModel::class, Task::class, Folder::class, Bookmark::class, History::class],
    version = 3,
)
@TypeConverters(Converters::class)
abstract class AppRoomDatabase : RoomDatabase() {
    abstract fun chatsDao(): ChatsDao

    abstract fun chatMessagesDao(): ChatMessageDao

    abstract fun llmModelDao(): LLMModelDao

    abstract fun taskDao(): TaskDao

    abstract fun folderDao(): FolderDao

    abstract fun bookmarkDao(): BookmarkDao

    abstract fun historyDao(): HistoryDao
}

val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL(
            "CREATE TABLE IF NOT EXISTS Bookmark (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "title TEXT NOT NULL DEFAULT '', " +
                "url TEXT NOT NULL DEFAULT '', " +
                "faviconUrl TEXT NOT NULL DEFAULT '', " +
                "dateAdded INTEGER NOT NULL DEFAULT 0)"
        )
        database.execSQL(
            "CREATE TABLE IF NOT EXISTS History (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "title TEXT NOT NULL DEFAULT '', " +
                "url TEXT NOT NULL DEFAULT '', " +
                "visitCount INTEGER NOT NULL DEFAULT 1, " +
                "lastVisited INTEGER NOT NULL DEFAULT 0)"
        )
    }
}

val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL("ALTER TABLE LLMModel ADD COLUMN mmprojUrl TEXT NOT NULL DEFAULT ''")
        database.execSQL("ALTER TABLE LLMModel ADD COLUMN mmprojPath TEXT NOT NULL DEFAULT ''")
        database.execSQL("ALTER TABLE LLMModel ADD COLUMN isVisionModel INTEGER NOT NULL DEFAULT 0")
    }
}

@Single
class AppDB(context: Context) {
    private val db =
        Room.databaseBuilder(context, AppRoomDatabase::class.java, "app-database")
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
            .build()

    /** Get all chats from the database sorted by dateUsed in descending order. */
    fun getChats(): Flow<List<Chat>> = db.chatsDao().getChats()

    fun loadDefaultChat(): Chat {
        val defaultChat =
            if (getChatsCount() == 0L) {
                addChat("Untitled")
                getRecentlyUsedChat()!!
            } else {
                // Given that chatsDB has at least one chat
                // chatsDB.getRecentlyUsedChat() will never return null
                getRecentlyUsedChat()!!
            }
        return defaultChat
    }

    /**
     * Get the most recently used chat from the database. This function might return null, if there
     * are no chats in the database.
     */
    fun getRecentlyUsedChat(): Chat? =
        runBlocking(Dispatchers.IO) { db.chatsDao().getRecentlyUsedChat() }

    /**
     * Adds a new chat to the database initialized with given arguments and returns the new Chat
     * object
     */
    fun addChat(
        chatName: String,
        chatTemplate: String = "",
        systemPrompt: String = "You are a helpful assistant.",
        llmModelId: Long = -1,
        isTask: Boolean = false,
    ): Chat =
        runBlocking(Dispatchers.IO) {
            val newChat =
                Chat(
                    name = chatName,
                    systemPrompt = systemPrompt,
                    dateCreated = Date(),
                    dateUsed = Date(),
                    llmModelId = llmModelId,
                    contextSize = 2048,
                    chatTemplate = chatTemplate,
                    isTask = isTask,
                )
            val newChatId = db.chatsDao().insertChat(newChat)
            newChat.copy(id = newChatId)
        }

    /** Update the chat in the database. */
    fun updateChat(modifiedChat: Chat) =
        runBlocking(Dispatchers.IO) { db.chatsDao().updateChat(modifiedChat) }

    fun deleteChat(chat: Chat) = runBlocking(Dispatchers.IO) { db.chatsDao().deleteChat(chat.id) }

    fun getChatsCount(): Long = runBlocking(Dispatchers.IO) { db.chatsDao().getChatsCount() }

    fun getChatsForFolder(folderId: Long): Flow<List<Chat>> =
        db.chatsDao().getChatsForFolder(folderId)

    // Chat Messages

    fun getMessages(chatId: Long): Flow<List<ChatMessage>> =
        db.chatMessagesDao().getMessages(chatId)

    fun getMessagesForModel(chatId: Long): List<ChatMessage> =
        runBlocking(Dispatchers.IO) { db.chatMessagesDao().getMessagesForModel(chatId) }

    fun addUserMessage(chatId: Long, message: String) =
        runBlocking(Dispatchers.IO) {
            db.chatMessagesDao()
                .insertMessage(
                    ChatMessage(chatId = chatId, message = message, isUserMessage = true)
                )
        }

    fun addAssistantMessage(chatId: Long, message: String) =
        runBlocking(Dispatchers.IO) {
            db.chatMessagesDao()
                .insertMessage(
                    ChatMessage(chatId = chatId, message = message, isUserMessage = false)
                )
        }

    fun deleteMessage(messageId: Long) =
        runBlocking(Dispatchers.IO) { db.chatMessagesDao().deleteMessage(messageId) }

    fun deleteMessages(chatId: Long) =
        runBlocking(Dispatchers.IO) { db.chatMessagesDao().deleteMessages(chatId) }

    // Models

    fun addModel(
        name: String,
        url: String,
        path: String,
        contextSize: Int,
        chatTemplate: String,
        mmprojUrl: String = "",
        mmprojPath: String = "",
        isVisionModel: Boolean = false,
    ) =
        runBlocking(Dispatchers.IO) {
            db.llmModelDao()
                .insertModels(
                    LLMModel(
                        name = name,
                        url = url,
                        path = path,
                        contextSize = contextSize,
                        chatTemplate = chatTemplate,
                        mmprojUrl = mmprojUrl,
                        mmprojPath = mmprojPath,
                        isVisionModel = isVisionModel,
                    )
                )
        }

    fun getModel(id: Long): LLMModel = runBlocking(Dispatchers.IO) { db.llmModelDao().getModel(id) }

    fun getModels(): Flow<List<LLMModel>> =
        runBlocking(Dispatchers.IO) { db.llmModelDao().getAllModels() }

    fun getModelsList(): List<LLMModel> =
        runBlocking(Dispatchers.IO) { db.llmModelDao().getAllModelsList() }

    fun deleteModel(id: Long) = runBlocking(Dispatchers.IO) { db.llmModelDao().deleteModel(id) }

    // Tasks

    fun getTask(taskId: Long): Task = runBlocking(Dispatchers.IO) { db.taskDao().getTask(taskId) }

    fun getTasks(): Flow<List<Task>> = db.taskDao().getTasks()

    fun addTask(name: String, systemPrompt: String, modelId: Long) =
        runBlocking(Dispatchers.IO) {
            db.taskDao()
                .insertTask(Task(name = name, systemPrompt = systemPrompt, modelId = modelId))
        }

    fun deleteTask(taskId: Long) = runBlocking(Dispatchers.IO) { db.taskDao().deleteTask(taskId) }

    fun updateTask(task: Task) = runBlocking(Dispatchers.IO) { db.taskDao().updateTask(task) }

    // Folders

    fun getFolders(): Flow<List<Folder>> = db.folderDao().getFolders()

    fun addFolder(folderName: String) =
        runBlocking(Dispatchers.IO) { db.folderDao().insertFolder(Folder(name = folderName)) }

    fun updateFolder(folder: Folder) =
        runBlocking(Dispatchers.IO) { db.folderDao().updateFolder(folder) }

    /** Deletes the folder from the Folder table only */
    fun deleteFolder(folderId: Long) =
        runBlocking(Dispatchers.IO) {
            db.folderDao().deleteFolder(folderId)
            db.chatsDao().updateFolderIds(folderId, -1L)
        }

    /** Deletes the folder from the Folder table and corresponding chats from the Chat table */
    fun deleteFolderWithChats(folderId: Long) =
        runBlocking(Dispatchers.IO) {
            db.folderDao().deleteFolder(folderId)
            db.chatsDao().deleteChatsInFolder(folderId)
        }

    // Bookmarks

    fun getBookmarks(): Flow<List<Bookmark>> = db.bookmarkDao().getBookmarks()

    fun addBookmark(title: String, url: String, faviconUrl: String = "") =
        runBlocking(Dispatchers.IO) {
            db.bookmarkDao().insertBookmark(Bookmark(title = title, url = url, faviconUrl = faviconUrl))
        }

    fun deleteBookmark(bookmarkId: Long) = runBlocking(Dispatchers.IO) { db.bookmarkDao().deleteBookmark(bookmarkId) }

    fun isBookmarked(url: String): Boolean =
        runBlocking(Dispatchers.IO) { db.bookmarkDao().isBookmarked(url) > 0 }

    fun getBookmarkByUrl(url: String): Bookmark? =
        runBlocking(Dispatchers.IO) { db.bookmarkDao().getBookmarkByUrl(url) }

    // History

    fun getHistory(): Flow<List<History>> = db.historyDao().getHistory()

    fun addOrUpdateHistory(title: String, url: String) =
        runBlocking(Dispatchers.IO) {
            val existing = db.historyDao().getHistoryByUrl(url)
            if (existing != null) {
                db.historyDao().updateHistory(existing.copy(title = title, visitCount = existing.visitCount + 1, lastVisited = Date()))
            } else {
                db.historyDao().insertHistory(History(title = title, url = url))
            }
        }

    fun deleteHistory(historyId: Long) = runBlocking(Dispatchers.IO) { db.historyDao().deleteHistory(historyId) }

    fun clearHistory() = runBlocking(Dispatchers.IO) { db.historyDao().clearHistory() }

    fun getRecentHistory(): List<History> =
        runBlocking(Dispatchers.IO) { db.historyDao().getRecentHistory() }
}
