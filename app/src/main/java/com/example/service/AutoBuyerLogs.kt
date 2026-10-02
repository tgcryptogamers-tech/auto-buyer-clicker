package com.example.service

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.Locale

object AutoBuyerLogs {
    private val _logsFlow = MutableSharedFlow<String>(replay = 500, extraBufferCapacity = 1000)
    val logsFlow: SharedFlow<String> = _logsFlow.asSharedFlow()

    private val logHistory = Collections.synchronizedList(mutableListOf<String>())
    private const val MAX_LOGS = 1000

    private val dateFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    suspend fun addLog(message: String) {
        val timeStamp = dateFormat.format(Date())
        val entry = "[$timeStamp] $message"
        synchronized(logHistory) {
            if (logHistory.size >= MAX_LOGS) {
                logHistory.removeAt(0)
            }
            logHistory.add(entry)
        }
        _logsFlow.emit(entry)
    }

    fun addLogBlocking(message: String) {
        val timeStamp = dateFormat.format(Date())
        val entry = "[$timeStamp] $message"
        synchronized(logHistory) {
            if (logHistory.size >= MAX_LOGS) {
                logHistory.removeAt(0)
            }
            logHistory.add(entry)
        }
        _logsFlow.tryEmit(entry)
    }

    fun getLogEntries(): List<String> {
        return synchronized(logHistory) {
            ArrayList(logHistory)
        }
    }

    fun getAllLogsText(header: String = ""): String {
        val logs = synchronized(logHistory) {
            ArrayList(logHistory)
        }
        return if (header.isNotBlank()) {
            "$header\n" + logs.joinToString("\n")
        } else {
            logs.joinToString("\n")
        }
    }

    fun clearLogs() {
        synchronized(logHistory) {
            logHistory.clear()
        }
    }
}

