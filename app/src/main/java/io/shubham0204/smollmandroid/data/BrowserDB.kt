/*
 * Copyright (C) 2025 AMM Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 */

package io.shubham0204.smollmandroid.data

import androidx.compose.runtime.Stable
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import java.util.Date

@Entity(tableName = "Bookmark")
@Stable
data class Bookmark(
    @PrimaryKey(autoGenerate = true) var id: Long = 0,
    var title: String = "",
    var url: String = "",
    var faviconUrl: String = "",
    var dateAdded: Date = Date(),
)

@Entity(tableName = "History")
@Stable
data class History(
    @PrimaryKey(autoGenerate = true) var id: Long = 0,
    var title: String = "",
    var url: String = "",
    var visitCount: Int = 1,
    var lastVisited: Date = Date(),
)

@Dao
interface BookmarkDao {
    @Query("SELECT * FROM Bookmark ORDER BY dateAdded DESC")
    fun getBookmarks(): Flow<List<Bookmark>>

    @Insert
    suspend fun insertBookmark(bookmark: Bookmark): Long

    @Query("DELETE FROM Bookmark WHERE id = :bookmarkId")
    suspend fun deleteBookmark(bookmarkId: Long)

    @Query("SELECT * FROM Bookmark WHERE url = :url LIMIT 1")
    suspend fun getBookmarkByUrl(url: String): Bookmark?

    @Query("SELECT COUNT(*) FROM Bookmark WHERE url = :url")
    suspend fun isBookmarked(url: String): Int
}

@Dao
interface HistoryDao {
    @Query("SELECT * FROM History ORDER BY lastVisited DESC LIMIT 100")
    fun getHistory(): Flow<List<History>>

    @Insert
    suspend fun insertHistory(history: History): Long

    @Update
    suspend fun updateHistory(history: History)

    @Query("DELETE FROM History WHERE id = :historyId")
    suspend fun deleteHistory(historyId: Long)

    @Query("DELETE FROM History")
    suspend fun clearHistory()

    @Query("SELECT * FROM History WHERE url = :url LIMIT 1")
    suspend fun getHistoryByUrl(url: String): History?

    @Query("SELECT * FROM History ORDER BY lastVisited DESC LIMIT 10")
    suspend fun getRecentHistory(): List<History>
}
