package com.example.ffmpegapp.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

interface DataRepository {
  val data: Flow<List<String>>
  fun saveOutputDirectoryUri(uri: String)
  fun getOutputDirectoryUri(): String?
}

class DefaultDataRepository(context: Context) : DataRepository {
  private val prefs: SharedPreferences = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)

  override val data: Flow<List<String>> = flow { emit(listOf("Android")) }

  override fun saveOutputDirectoryUri(uri: String) {
    prefs.edit().putString("output_dir_uri", uri).apply()
  }

  override fun getOutputDirectoryUri(): String? {
    return prefs.getString("output_dir_uri", null)
  }
}
