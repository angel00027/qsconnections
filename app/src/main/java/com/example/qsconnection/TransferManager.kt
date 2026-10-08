package com.example.qsconnection

import androidx.compose.runtime.mutableStateListOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

object TransferManager {
    val activeTransfers = mutableStateListOf<MainViewModel.TransferTask>()
    val managerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    
    fun addTransfer(task: MainViewModel.TransferTask) {
        activeTransfers.add(task)
    }
    
    fun removeTransfer(task: MainViewModel.TransferTask) {
        activeTransfers.remove(task)
    }

    fun clearCompleted() {
        activeTransfers.removeAll { 
            it.status.contains("Completado") || 
            it.status.contains("Error") || 
            it.status.contains("Cancelado") 
        }
    }
}
