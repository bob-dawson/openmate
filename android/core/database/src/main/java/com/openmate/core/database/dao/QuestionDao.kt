package com.openmate.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.openmate.core.database.entity.QuestionEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface QuestionDao {
    @Query("SELECT * FROM QuestionEntity ORDER BY id")
    fun observeAll(): Flow<List<QuestionEntity>>

    @Query("SELECT * FROM QuestionEntity ORDER BY id")
    suspend fun getAll(): List<QuestionEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(question: QuestionEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(questions: List<QuestionEntity>)

    @Query("DELETE FROM QuestionEntity WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM QuestionEntity")
    suspend fun deleteAll()

    @Transaction
    suspend fun replaceAll(questions: List<QuestionEntity>) {
        deleteAll()
        upsertAll(questions)
    }
}
