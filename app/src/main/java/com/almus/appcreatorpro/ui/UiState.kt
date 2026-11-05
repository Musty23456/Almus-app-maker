package com.almus.appcreatorpro.ui

/** Generic screen state used by the ViewModels: loading, success (with data) or error. */
sealed interface UiState<out T> {
    data object Loading : UiState<Nothing>
    data class Success<T>(val data: T) : UiState<T>
    data class Error(val message: String) : UiState<Nothing>
}
