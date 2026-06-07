package com.aigena.messenger

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.aigena.messenger.data.AppDatabase
import com.aigena.messenger.data.HermesRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class ProfileStatus(
    val agentStatus: String = "connecting",
    val model: String = "",
    val totalTokens: Long = 0L,
    val lastActive: Long = 0L,
    val offline: Boolean = false,
)

data class MediaTab(
    val items: List<HermesRepository.AttachmentMsg> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
)

class ProfileViewModel(application: Application) : AndroidViewModel(application) {

    private val db = AppDatabase.getInstance(application)
    private val repo = HermesRepository(db.messageDao())

    private val _status = MutableStateFlow(ProfileStatus())
    val status: StateFlow<ProfileStatus> = _status.asStateFlow()

    private val _mediaTabs = MutableStateFlow<List<MediaTab>>(List(5) { MediaTab() })
    val mediaTabs: StateFlow<List<MediaTab>> = _mediaTabs.asStateFlow()

    private val lastFetched = LongArray(5) { 0L }

    companion object {
        val TAB_TYPES = listOf("photo", "video", "voice", "file", "link")
        const val CACHE_TTL_MS = 30_000L
    }

    init {
        startPolling()
    }

    fun loadTab(tabIndex: Int) {
        if (tabIndex < 0 || tabIndex >= TAB_TYPES.size) return
        val tab = _mediaTabs.value[tabIndex]
        if (tab.items.isNotEmpty() && System.currentTimeMillis() - lastFetched[tabIndex] < CACHE_TTL_MS) return
        if (tab.loading) return

        val type = TAB_TYPES[tabIndex]

        viewModelScope.launch {
            _mediaTabs.value = _mediaTabs.value.toMutableList().also { t ->
                t[tabIndex] = t[tabIndex].copy(loading = true, error = null)
            }
            try {
                val items = withContext(Dispatchers.IO) { repo.fetchAttachments(type) }
                _mediaTabs.value = _mediaTabs.value.toMutableList().also { t ->
                    t[tabIndex] = MediaTab(items = items)
                }
                lastFetched[tabIndex] = System.currentTimeMillis()
            } catch (e: Exception) {
                _mediaTabs.value = _mediaTabs.value.toMutableList().also { t ->
                    t[tabIndex] = t[tabIndex].copy(loading = false, error = e.message)
                }
            }
        }
    }

    private fun startPolling() {
        viewModelScope.launch {
            while (isActive) {
                try {
                    val s = withContext(Dispatchers.IO) { repo.fetchAgentStatus() }
                    if (s != null) {
                        _status.value = ProfileStatus(
                            agentStatus = s.agentStatus, model = s.model,
                            totalTokens = s.totalTokens, lastActive = s.lastActive,
                        )
                    } else {
                        _status.value = ProfileStatus(agentStatus = "offline", offline = true)
                    }
                } catch (_: Exception) {
                    _status.value = ProfileStatus(agentStatus = "offline", offline = true)
                }
                delay(5000)
            }
        }
    }
}
