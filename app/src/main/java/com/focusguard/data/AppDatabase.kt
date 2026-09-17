package com.focusguard.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [Task::class, Commitment::class, StudySession::class, JournalEntry::class, AppRule::class, DomainRule::class],
    version = 1,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun tasks(): TaskDao
    abstract fun commitments(): CommitmentDao
    abstract fun sessions(): SessionDao
    abstract fun journal(): JournalDao
    abstract fun appRules(): AppRuleDao
    abstract fun domainRules(): DomainRuleDao

    companion object {
        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "focusguard.db")
                .fallbackToDestructiveMigration()
                .build()
    }
}
