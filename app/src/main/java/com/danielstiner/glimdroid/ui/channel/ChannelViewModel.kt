package com.danielstiner.glimdroid.ui.channel

import android.net.Uri
import android.util.Log
import androidx.annotation.MainThread
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.danielstiner.glimdroid.data.AuthStateDataSource
import com.danielstiner.glimdroid.data.ChannelRepository
import com.danielstiner.glimdroid.data.ChatRepository
import com.danielstiner.glimdroid.data.UserRepository
import com.danielstiner.glimdroid.data.model.*
import com.danielstiner.phoenix.absinthe.Subscription
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class ChannelViewModel(
    private val auth: AuthStateDataSource,
    private val channels: ChannelRepository,
    private val chats: ChatRepository,
    private val users: UserRepository,
    private val countryCode: String,
) : ViewModel() {

    val isAuthorized: Boolean
        get() = auth.isAuthorized

    private val _uiState = MutableStateFlow(ChannelUiState())
    val uiState: StateFlow<ChannelUiState> = _uiState.asStateFlow()

    private val _title = MutableLiveData<String>()
    val title: LiveData<String> = _title

    private val _matureContent = MutableLiveData<Boolean>()
    val matureContent: LiveData<Boolean> = _matureContent

    private val _displayLanguage = MutableLiveData<String?>()
    val displayLanguage: LiveData<String?> = _displayLanguage

    private val _category = MutableLiveData<Category>()
    val category: LiveData<Category> = _category

    private val _subcategory = MutableLiveData<Subcategory?>()
    val subcategory: LiveData<Subcategory?> = _subcategory

    private val _tags = MutableLiveData<List<Tag>>()
    val tags: LiveData<List<Tag>> = _tags

    private val _displayname = MutableLiveData<String>()
    val displayname: LiveData<String> = _displayname

    private val _username = MutableLiveData<String>()
    val username: LiveData<String> = _username

    private val _avatarUri = MutableLiveData<Uri?>()
    val avatarUri: LiveData<Uri?> = _avatarUri

    private val _messages = MutableLiveData<List<ChatMessage>>().apply {
        value = listOf()
    }
    val messages: LiveData<List<ChatMessage>> = _messages

    private val _viewerCount = MutableLiveData<Int?>()
    val viewerCount: LiveData<Int?> = _viewerCount

    private val _thumbnailUri = MutableLiveData<Uri?>()
    val thumbnailUri: LiveData<Uri?> = _thumbnailUri

    private val _following = MutableLiveData<Boolean>()
    val following: LiveData<Boolean> = _following

    private val _liveNotifications = MutableLiveData<Boolean>()
    val liveNotifications: LiveData<Boolean> = _liveNotifications

    // Always accessed from main thread
    private var current: ChannelWatcher? = null

    @MainThread
    fun watch(channel: ChannelId, thumbnailUri: Uri?) {
        Log.d(
            TAG,
            "Watch, new:$channel, current:${current?.channel}, isClosed:${current?.isClosed}"
        )

        if (current?.channel == channel && current?.isClosed == false) {
            return
        }

        _uiState.update { currentUiState ->
            currentUiState.copy(isStopped = false, channel = channel, edgeRoute = null)
        }
        _thumbnailUri.value = thumbnailUri
        current?.close()
        current = ChannelWatcher(channel)
    }

    @MainThread
    fun start() {
        current?.apply {
            viewModelScope.launch(Dispatchers.IO) {
                start()
            }
        }
    }

    @MainThread
    fun stop() {
        current?.apply {
            viewModelScope.launch(Dispatchers.IO) {
                stop()
            }
        }
    }

    @MainThread
    fun follow() = followWithoutLiveNotifications()

    @MainThread
    fun followWithoutLiveNotifications() {
        val channel = current!!.channel
        viewModelScope.launch(Dispatchers.IO) {
            channels.follow(channel, liveNotifications = false)
            _following.postValue(true)
            _liveNotifications.postValue(false)
        }
    }

    @MainThread
    fun followWithLiveNotifications() {
        val channel = current!!.channel
        viewModelScope.launch(Dispatchers.IO) {
            channels.follow(channel, liveNotifications = true)
            _following.postValue(true)
            _liveNotifications.postValue(true)
        }
    }

    @MainThread
    fun unfollow() {
        val channel = current!!.channel
        viewModelScope.launch(Dispatchers.IO) {
            channels.unfollow(channel)
            _following.postValue(false)
            _liveNotifications.postValue(false)
        }
    }

    @MainThread
    fun sendMessage(text: CharSequence?) {
        val channel = current!!.channel
        viewModelScope.launch(Dispatchers.IO) {
            chats.sendMessage(channel, text!!)
        }
    }

    override fun onCleared() {
        super.onCleared()

        stop()

        current?.close()
    }

    /**
     * Container for subscriptions to channel and stream metadata.
     *
     * Having a container makes it easy to cleanly stop subscriptions when the channel is changed.
     */
    inner class ChannelWatcher(val channel: ChannelId) {

        val isClosed: Boolean get() = closed

        @Volatile
        private var closed = false
        private var chatSubscription: Subscription<ChatMessage>? = null
        private var edgeRoute: EdgeRoute? = null
        private val mutex = Mutex()

        init {
            viewModelScope.launch(Dispatchers.IO) {
                // Get edge route in parallel with fetching channel and chat info
                joinAll(
                    launch { startWatch(channel) },
                    launch { fetchChannelInfo(channel) },
                    launch { fetchFollowInfo(channel) }
                )

                // Kept separate from fetchChannelInfo because fetching chat messages is slow,
                // likely due to inefficient joins on the server
                fetchRecentChatHistory(channel)

                // After initial fetch is done, start subscriptions and background work
                joinAll(
                    launch { watchChats(channel) },
                    launch { watchChannelUpdates(channel) },
                )
            }
        }

        fun start() {
            _uiState.update { currentUiState ->
                currentUiState.copy(isStopped = false)
            }
        }

        fun stop() {
            _uiState.update { currentUiState ->
                currentUiState.copy(isStopped = true)
            }
        }

        private suspend fun startWatch(channel: ChannelId) {
            edgeRoute = channels.watch(channel, countryCode)

            if (!closed) {
                Log.d(TAG, "Start watch; $channel, $edgeRoute")
                _uiState.update { currentUiState ->
                    currentUiState.copy(
                        channel = channel,
                        edgeRoute = edgeRoute
                    )
                }
            }
        }

        private suspend fun fetchChannelInfo(channel: ChannelId) {
            updateChannelLiveData(channels.get(channel))
        }

        private suspend fun updateChannelLiveData(channel: Channel) {
            withContext(Dispatchers.Main) {
                if (!closed) {
                    _title.value = channel.title
                    _matureContent.value = channel.matureContent
                    _displayLanguage.value = channel.displayLanguage()
                    _category.value = channel.category
                    _subcategory.value = channel.subcategory
                    _tags.value = channel.tags
                    _displayname.value = channel.streamer.displayName
                    _username.value = channel.streamer.username
                    _avatarUri.value = channel.streamer.avatarUrl?.let { Uri.parse(it) }
                    _viewerCount.value = channel.stream?.viewerCount

                    if (channel.stream?.thumbnailUrl != null) {
                        _thumbnailUri.value = Uri.parse(channel.stream.thumbnailUrl)
                    }
                }
            }
        }

        private suspend fun fetchFollowInfo(channel: ChannelId) {
            updateFollowingLiveData(channel, users.me())
        }

        private suspend fun updateFollowingLiveData(channel: ChannelId, me: User) {
            withContext(Dispatchers.Main) {
                if (!closed) {
                    val follow = me.following.firstOrNull { follow -> follow.channel == channel }

                    _following.value = follow != null
                    _liveNotifications.value = follow?.liveNotifications ?: false
                }
            }
        }

        private suspend fun fetchRecentChatHistory(channel: ChannelId) {
            withContext(Dispatchers.Main) {
                if (!closed) {
                    _messages.value = listOf()
                } else {
                    Log.w(TAG, "Channel changed before before chats could be cleared")
                }
            }
            val recentMessages = chats.recentMessages(channel)

            withContext(Dispatchers.Main) {
                if (!closed) {
                    _messages.value = recentMessages
                } else {
                    Log.w(TAG, "Channel changed before recent chats could be fetched")
                }
            }
        }

        private suspend fun watchChats(channel: ChannelId) {
            val sub = chats.subscribe(channel).apply {
                this.data.collect { message ->
                    withContext(Dispatchers.Main) {
                        if (!closed) {
                            _messages.value = buildList {
                                addAll(_messages.value!!)
                                add(message)
                            }
                        } else {
                            Log.w(TAG, "Channel changed before this chat arrived, ignoring")
                        }
                    }
                }
            }

            mutex.withLock {
                if (!closed) {
                    chatSubscription = sub
                } else {
                    Log.w(TAG, "Channel changed before chat subscription started, cancelling")
                    sub.cancel()
                }
            }
        }

        private suspend fun watchChannelUpdates(channel: ChannelId) {
            // Simplistic approach because websocket subscriptions for a channel do not receive
            // updates for stream metadata like number of viewers
            while (!closed) {
                delay(30_000)
                fetchChannelInfo(channel)
            }
        }

        fun close() {
            viewModelScope.launch(Dispatchers.IO) {
                mutex.withLock {
                    chatSubscription?.cancel()
                    closed = true
                }
            }
        }
    }

    companion object {
        private const val TAG = "ChannelViewModel"
    }
}

data class ChannelUiState(
    val isStopped: Boolean = false,
    val channel: ChannelId? = null,
    val edgeRoute: EdgeRoute? = null,
)
