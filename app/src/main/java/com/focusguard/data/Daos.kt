package com.focusguard.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface TaskDao {
    @Query("SELECT * FROM tasks WHERE completed = 0 ORDER BY deadlineMillis IS NULL, deadlineMillis ASC, importance DESC")
    fun observeOpen(): Flow<List<Task>>

    @Query("SELECT * FROM tasks WHERE completed = 0")
    suspend fun open(): List<Task>

    @Query("SELECT * FROM tasks WHERE id = :id")
    suspend fun byId(id: Long): Task?

    @Query("SELECT * FROM tasks WHERE completed = 1 AND createdMillis >= :since ORDER BY deadlineMillis")
    suspend fun completedSince(since: Long): List<Task>

    @Insert
    suspend fun insert(task: Task): Long

    @Update
    suspend fun update(task: Task)

    @Delete
    suspend fun delete(task: Task)
}

@Dao
interface CommitmentDao {
    @Query("SELECT * FROM commitments ORDER BY dayOfWeek, startMinute")
    fun observeAll(): Flow<List<Commitment>>

    @Query("SELECT * FROM commitments")
    suspend fun all(): List<Commitment>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(c: Commitment): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(cs: List<Commitment>)

    @Delete
    suspend fun delete(c: Commitment)

    @Query("DELETE FROM commitments WHERE kind = :kind")
    suspend fun deleteKind(kind: CommitmentKind)
}

@Dao
interface SessionDao {
    @Insert
    suspend fun insert(s: StudySession): Long

    @Query("SELECT * FROM sessions WHERE startMillis >= :since ORDER BY startMillis")
    suspend fun since(since: Long): List<StudySession>

    @Query("SELECT * FROM sessions ORDER BY startMillis DESC LIMIT 1")
    suspend fun latest(): StudySession?
}

@Dao
interface JournalDao {
    @Insert
    suspend fun insert(e: JournalEntry): Long

    @Query("SELECT * FROM journal WHERE createdMillis >= :since ORDER BY createdMillis DESC")
    suspend fun since(since: Long): List<JournalEntry>

    @Query("SELECT * FROM journal WHERE kind = :kind ORDER BY createdMillis DESC LIMIT 1")
    suspend fun latestOfKind(kind: JournalKind): JournalEntry?
}

@Dao
interface AppRuleDao {
    @Query("SELECT * FROM app_rules")
    suspend fun all(): List<AppRule>

    @Query("SELECT * FROM app_rules")
    fun observeAll(): Flow<List<AppRule>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(rule: AppRule)

    @Query("DELETE FROM app_rules WHERE packageName = :pkg")
    suspend fun delete(pkg: String)
}

@Dao
interface DomainRuleDao {
    @Query("SELECT * FROM domain_rules")
    suspend fun all(): List<DomainRule>

    @Query("SELECT * FROM domain_rules ORDER BY host")
    fun observeAll(): Flow<List<DomainRule>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(rule: DomainRule)

    @Query("DELETE FROM domain_rules WHERE host = :host")
    suspend fun delete(host: String)
}
