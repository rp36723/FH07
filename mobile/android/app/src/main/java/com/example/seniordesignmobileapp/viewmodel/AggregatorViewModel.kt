package com.example.seniordesignmobileapp.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.seniordesignmobileapp.ble.BleAggregatorController
import com.example.seniordesignmobileapp.model.AggregatorUiState
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

class AggregatorViewModel(
    applicationContext: Context,
) : ViewModel() {
    private val controller = BleAggregatorController(applicationContext)

    val uiState: StateFlow<AggregatorUiState> = controller.uiState.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000),
        initialValue = controller.uiState.value,
    )

    fun onBlePermissionsChanged(granted: Boolean) {
        if (granted) {
            controller.start()
        } else {
            controller.stop()
        }
    }

    fun reconnect() {
        controller.restart()
    }

    override fun onCleared() {
        controller.stop()
        super.onCleared()
    }

    companion object {
        fun factory(applicationContext: Context): ViewModelProvider.Factory =
            viewModelFactory {
                initializer {
                    AggregatorViewModel(applicationContext)
                }
            }
    }
}
