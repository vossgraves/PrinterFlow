package com.example.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface PrinterDao {
    @Query("SELECT * FROM printers ORDER BY isSelected DESC, lastSeenMs DESC, id ASC")
    fun observePrinters(): Flow<List<PrinterEntity>>

    @Query("SELECT * FROM printers WHERE isSelected = 1 LIMIT 1")
    suspend fun getSelectedPrinter(): PrinterEntity?

    @Query("SELECT * FROM printers WHERE id = :id LIMIT 1")
    suspend fun getPrinterById(id: Long): PrinterEntity?

    @Query("SELECT * FROM printers WHERE host = :host AND port = :port LIMIT 1")
    suspend fun findByHostPort(host: String, port: Int): PrinterEntity?

    @Query("SELECT COUNT(*) FROM printers")
    suspend fun countPrinters(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(printer: PrinterEntity): Long

    @Update
    suspend fun update(printer: PrinterEntity)

    @Query("UPDATE printers SET isSelected = 0")
    suspend fun clearSelected()

    @Query("UPDATE printers SET isSelected = 1 WHERE id = :id")
    suspend fun markSelected(id: Long)

    @Query("DELETE FROM printers WHERE id = :id")
    suspend fun deleteById(id: Long)
}

@Dao
interface PrintJobDao {
    @Query("SELECT * FROM print_jobs ORDER BY createdAtMs DESC")
    fun observeAllJobs(): Flow<List<PrintJobEntity>>

    @Query("SELECT * FROM print_jobs WHERE id = :id LIMIT 1")
    suspend fun getJobById(id: Long): PrintJobEntity?

    @Query("SELECT * FROM print_jobs WHERE status IN (:statuses) ORDER BY createdAtMs ASC")
    suspend fun getJobsByStatuses(statuses: List<String>): List<PrintJobEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(job: PrintJobEntity): Long

    @Update
    suspend fun update(job: PrintJobEntity)

    @Query(
        "UPDATE print_jobs SET status = :status, statusDetail = :detail, blockReason = :blockReason, updatedAtMs = :now WHERE id = :id"
    )
    suspend fun updateStatus(
        id: Long,
        status: String,
        detail: String,
        blockReason: String? = null,
        now: Long = System.currentTimeMillis()
    )

    @Query(
        "UPDATE print_jobs SET currentPage = :currentPage, statusDetail = :detail, updatedAtMs = :now WHERE id = :id"
    )
    suspend fun updateProgress(
        id: Long,
        currentPage: Int,
        detail: String,
        now: Long = System.currentTimeMillis()
    )

    @Query("DELETE FROM print_jobs WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM print_jobs WHERE status IN ('COMPLETED', 'CANCELLED')")
    suspend fun clearFinished()
}

@Dao
interface DiagnosticLogDao {
    @Query("SELECT * FROM diagnostic_logs ORDER BY timestampMs DESC LIMIT 120")
    fun observeRecentLogs(): Flow<List<DiagnosticLogEntity>>

    @Insert
    suspend fun insert(log: DiagnosticLogEntity)

    @Query("DELETE FROM diagnostic_logs")
    suspend fun clearAll()
}
