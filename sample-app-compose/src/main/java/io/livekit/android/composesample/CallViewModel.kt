package io.livekit.android.composesample

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.github.ajalt.timberkt.Timber
import io.livekit.android.ConnectOptions
import io.livekit.android.LiveKit
import io.livekit.android.room.Room
import io.livekit.android.room.RoomListener
import io.livekit.android.room.participant.AudioTrackPublishOptions
import io.livekit.android.room.participant.Participant
import io.livekit.android.room.participant.RemoteParticipant
import io.livekit.android.room.participant.VideoTrackPublishOptions
import io.livekit.android.room.track.*
import kotlinx.coroutines.launch

class CallViewModel(
    val url: String,
    val token: String,
    application: Application
) : AndroidViewModel(application), RoomListener {
    private val mutableRoom = MutableLiveData<Room>()
    val room: LiveData<Room> = mutableRoom
    private val mutableRemoteParticipants = MutableLiveData<List<RemoteParticipant>>()
    val remoteParticipants: LiveData<List<RemoteParticipant>> = mutableRemoteParticipants

    private var localAudioTrack: LocalAudioTrack? = null
    private var localVideoTrack: LocalVideoTrack? = null
    private var localScreencastTrack: LocalScreencastVideoTrack? = null

    private val mutableMicEnabled = MutableLiveData(true)
    val micEnabled = mutableMicEnabled.hide()

    private val mutableVideoEnabled = MutableLiveData(true)
    val videoEnabled = mutableVideoEnabled.hide()

    private val mutableFlipVideoButtonEnabled = MutableLiveData(true)
    val flipButtonVideoEnabled = mutableFlipVideoButtonEnabled.hide()

    private val mutableScreencastEnabled = MutableLiveData(false)
    val screencastEnabled = mutableScreencastEnabled.hide()

    init {
        viewModelScope.launch {
            val room = LiveKit.connect(
                application,
                url,
                token,
                ConnectOptions(),
                this@CallViewModel
            )

            // Create and publish audio/video tracks
            val localParticipant = room.localParticipant
            val audioTrack = localParticipant.createAudioTrack()
            localParticipant.publishAudioTrack(audioTrack, AudioTrackPublishOptions(dtx = true))
            this@CallViewModel.localAudioTrack = audioTrack
            mutableMicEnabled.postValue(audioTrack.enabled)

            val videoTrack = localParticipant.createVideoTrack()
            localParticipant.publishVideoTrack(
                videoTrack,
                VideoTrackPublishOptions(simulcast = false)
            )
            videoTrack.startCapture()
            this@CallViewModel.localVideoTrack = videoTrack
            mutableVideoEnabled.postValue(videoTrack.enabled)

            updateParticipants(room)
            mutableRoom.value = room
        }
    }

    fun startScreenCapture(mediaProjectionPermissionResultData: Intent) {
        val localParticipant = room.value?.localParticipant ?: return
        viewModelScope.launch {
            val screencastTrack =
                localParticipant.createScreencastTrack(mediaProjectionPermissionResultData = mediaProjectionPermissionResultData)
            localParticipant.publishVideoTrack(
                screencastTrack
            )

            // Must start the foreground prior to startCapture.
            screencastTrack.startForegroundService(null, null)
            screencastTrack.startCapture()

            this@CallViewModel.localScreencastTrack = screencastTrack
            mutableScreencastEnabled.postValue(screencastTrack.enabled)
        }
    }

    fun stopScreenCapture() {
        viewModelScope.launch {
            localScreencastTrack?.let { localScreencastVideoTrack ->
                localScreencastVideoTrack.stop()
                room.value?.localParticipant?.unpublishTrack(localScreencastVideoTrack)
                mutableScreencastEnabled.postValue(localScreencastTrack?.enabled ?: false)
            }
        }
    }

    private fun updateParticipants(room: Room) {
        mutableRemoteParticipants.postValue(
            room.remoteParticipants
                .keys
                .sortedBy { it }
                .mapNotNull { room.remoteParticipants[it] }
        )
    }

    override fun onCleared() {
        super.onCleared()
        mutableRoom.value?.disconnect()
    }

    override fun onDisconnect(room: Room, error: Exception?) {
    }

    override fun onParticipantConnected(
        room: Room,
        participant: RemoteParticipant
    ) {
        updateParticipants(room)
    }

    override fun onParticipantDisconnected(
        room: Room,
        participant: RemoteParticipant
    ) {
        updateParticipants(room)
    }

    override fun onFailedToConnect(room: Room, error: Exception) {
    }

    override fun onActiveSpeakersChanged(speakers: List<Participant>, room: Room) {
        Timber.i { "active speakers changed ${speakers.count()}" }
    }

    override fun onMetadataChanged(participant: Participant, prevMetadata: String?, room: Room) {
        Timber.i { "Participant metadata changed: ${participant.identity}" }
    }

    fun setMicEnabled(enabled: Boolean) {
        localAudioTrack?.enabled = enabled
        mutableMicEnabled.postValue(enabled)
    }

    fun setVideoEnabled(enabled: Boolean) {
        localVideoTrack?.enabled = enabled
        mutableVideoEnabled.postValue(enabled)
    }

    fun flipVideo() {
        room.value?.localParticipant?.let { participant ->
            val videoTrack = participant.videoTracks.values
                .firstOrNull()
                ?.track as? LocalVideoTrack
                ?: return@let

            val newOptions = when (videoTrack.options.position) {
                CameraPosition.FRONT -> LocalVideoTrackOptions(position = CameraPosition.BACK)
                CameraPosition.BACK -> LocalVideoTrackOptions(position = CameraPosition.FRONT)
            }

            videoTrack.restartTrack(newOptions)
        }
    }
}

private fun <T> LiveData<T>.hide(): LiveData<T> = this