package com.flashdrop.core.datastore

import android.content.Context
import android.os.Build
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.flashdrop.core.model.AppSettings
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.settingsDataStore by preferencesDataStore(name = "settings")

@Singleton
class SettingsStore @Inject constructor(@ApplicationContext private val context: Context) {
    private val store get() = context.settingsDataStore

    val settings: Flow<AppSettings> = context.settingsDataStore.data.map { p ->
        AppSettings(
            deviceName = p[DEVICE_NAME]?.takeIf { it.isNotBlank() } ?: defaultName(),
            stayVisible = p[STAY_VISIBLE] ?: false,
            saveTreeUri = p[SAVE_TREE],
            saveLabel = p[SAVE_LABEL] ?: AppSettings.DEFAULT_SAVE_LABEL,
            notificationsEnabled = p[NOTIFICATIONS] ?: true,
            autoAcceptTrusted = p[AUTO_ACCEPT] ?: false,
        )
    }

    suspend fun current(): AppSettings = settings.first()

    suspend fun setDeviceName(name: String) {
        store.edit { it[DEVICE_NAME] = name.trim().take(40) }
    }
    suspend fun setStayVisible(value: Boolean) {
        store.edit { it[STAY_VISIBLE] = value }
    }
    suspend fun setNotifications(value: Boolean) {
        store.edit { it[NOTIFICATIONS] = value }
    }
    suspend fun setAutoAcceptTrusted(value: Boolean) {
        store.edit { it[AUTO_ACCEPT] = value }
    }

    suspend fun setSaveLocation(treeUri: String?, label: String?) {
        store.edit {
            if (treeUri == null) {
                it.remove(SAVE_TREE)
                it.remove(SAVE_LABEL)
            } else {
                it[SAVE_TREE] = treeUri
                it[SAVE_LABEL] = label ?: "Custom folder"
            }
        }
    }

    private fun defaultName(): String = Build.MODEL?.takeIf { it.isNotBlank() } ?: "Android device"

    private companion object {
        val DEVICE_NAME = stringPreferencesKey("device_name")
        val STAY_VISIBLE = booleanPreferencesKey("stay_visible")
        val SAVE_TREE = stringPreferencesKey("save_tree_uri")
        val SAVE_LABEL = stringPreferencesKey("save_label")
        val NOTIFICATIONS = booleanPreferencesKey("notifications")
        val AUTO_ACCEPT = booleanPreferencesKey("auto_accept_trusted")
    }
}
