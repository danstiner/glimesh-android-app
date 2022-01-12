package tv.glimesh.ui.channel

import android.app.PictureInPictureParams
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.util.Rational
import android.view.View
import android.view.inputmethod.EditorInfo
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.LinearLayoutManager
import com.bumptech.glide.Glide
import org.webrtc.EglBase
import org.webrtc.RendererCommon
import tv.glimesh.data.model.ChannelId
import tv.glimesh.data.model.StreamId
import tv.glimesh.databinding.ActivityChannelBinding


const val smashbetsChannelId = 10552L

const val CHANNEL_ID = "tv.glimesh.android.extra.channel.id"
const val STREAM_ID = "tv.glimesh.android.extra.stream.id"
const val STREAM_THUMBNAIL_URL = "tv.glimesh.android.extra.stream.url"

class ChannelActivity : AppCompatActivity() {

    private val TAG = "ChannelActivity"

    private lateinit var viewModel: ChannelViewModel
    private lateinit var binding: ActivityChannelBinding

    @RequiresApi(Build.VERSION_CODES.O)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityChannelBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val origStreamThumbnailUrl =
            intent.getStringExtra(STREAM_THUMBNAIL_URL)?.let { Uri.parse(it) }
        origStreamThumbnailUrl?.let {
            Log.d(TAG, "Stream thumbnail url: $it")
            Glide
                .with(this)
                .load(it)
                .onlyRetrieveFromCache(true)
                .into(binding.videoPreview)
        }

        val eglBase = EglBase.create()

        viewModel = ViewModelProvider(
            this,
            ChannelViewModelFactory(applicationContext, eglBase.eglBaseContext)
        )[ChannelViewModel::class.java]

        binding.videoView.init(eglBase.eglBaseContext, null)
        binding.videoView.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
        binding.videoView.setEnableHardwareScaler(true)
        binding.videoView.setZOrderOnTop(true);
        binding.videoView.holder.setFormat(PixelFormat.TRANSPARENT);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)
        ) {
            val sourceRectHint = Rect()
            binding.videoView.getGlobalVisibleRect(sourceRectHint)

            setPictureInPictureParams(
                PictureInPictureParams.Builder()
                    .setAspectRatio(Rational(16, 9))
                    .setSourceRectHint(sourceRectHint)
                    .setAutoEnterEnabled(true)
                    .build()
            )
        }

        viewModel.title.observe(this, {
            binding.textviewChannelTitle.text = it
        })
        viewModel.streamerDisplayname.observe(this, {
            binding.textviewStreamerDisplayName.text = it
        })
        viewModel.streamerUsername.observe(this, { username ->
            binding.buttonSupport.setOnClickListener {
                startActivity(
                    Intent(
                        Intent.ACTION_VIEW,
                        Uri.parse("https://glimesh.tv/").buildUpon()
                            .appendPath(username)
                            .appendPath("support").build()
                    )
                )
            }
        })
        viewModel.streamerAvatarUrl.observe(this, {
            if (it != null) {
                Glide
                    .with(this)
                    .load(it)
                    .fitCenter()
                    .circleCrop()
                    .into(binding.avatarImage)
            } else {
                Glide.with(this).clear(binding.avatarImage)
            }
        })
        viewModel.viewerCount.observe(this, {
            if (it != null) {
                binding.textviewChannelSubtitle.text = "$it viewers"
            } else {
                binding.textviewChannelSubtitle.text = "Not live"
            }
        })
        viewModel.streamerAvatarUrl.observe(this, {
            if (it != null) {
                Glide
                    .with(this)
                    .load(it)
                    .fitCenter()
                    .circleCrop()
                    .into(binding.avatarImage)
            } else {
                Glide.with(this).clear(binding.avatarImage)
            }
        })
        viewModel.videoThumbnailUrl.observe(this, {
            val newWithoutQuery = Uri.parse(it.toString()).buildUpon().clearQuery().build()
            val origWithoutQuery = origStreamThumbnailUrl?.buildUpon()?.clearQuery()?.build()
            when {
                newWithoutQuery == origWithoutQuery -> {
                    return@observe
                }
                it != null -> {
                    Glide
                        .with(this)
                        .load(it)
                        .onlyRetrieveFromCache(true)
                        .into(binding.videoPreview)
                }
                else -> {
                    Glide.with(this).clear(binding.videoPreview)
                }
            }
        })
        viewModel.videoTrack.observe(this, {
            it.addSink(binding.videoView)
        })

        val chatAdapter = ChatAdapter()
        binding.chatRecyclerView.adapter = chatAdapter
        val linearLayoutManager = LinearLayoutManager(this)
        linearLayoutManager.stackFromEnd = true
        binding.chatRecyclerView.layoutManager = linearLayoutManager
        viewModel.messages.observe(this, {
            chatAdapter.submitList(it)
            // TODO only scroll if we're already at the bottom of the list
            binding.chatRecyclerView.smoothScrollToPosition(it.size)
        })

        binding.chatInputEditText.setOnEditorActionListener { textView, id, _ ->
            when (id) {
                EditorInfo.IME_ACTION_DONE -> {
                    viewModel.sendMessage(textView.text)
                    textView.text = ""
                    true
                }
                else -> false
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    override fun onStart() {
        super.onStart()

        var channelId = ChannelId(intent.getLongExtra(CHANNEL_ID, smashbetsChannelId))

        Log.d(TAG, "Watching $channelId")
        viewModel.watch(channelId)
    }

    override fun onUserLeaveHint() {
        // TODO Check if paused before PiP'in
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val sourceRectHint = Rect()
            binding.videoView.getGlobalVisibleRect(sourceRectHint)
            enterPictureInPictureMode(
                PictureInPictureParams.Builder()
                    .setAspectRatio(Rational(16, 9))
                    .setSourceRectHint(sourceRectHint)
                    .build()
            )
        }
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration
    ) {
        if (isInPictureInPictureMode) {
            // Hide the normal UI (controls, etc.) while in picture-in-picture mode
            binding.group.visibility = View.INVISIBLE
        } else {
            // Restore the normal UI
            binding.group.visibility = View.VISIBLE
        }
    }

    companion object {
        fun intent(
            context: Context,
            channel: ChannelId,
            stream: StreamId,
            streamThumbnailUrl: Uri? = null
        ) =
            Intent(context, ChannelActivity::class.java).apply {
                putExtra(CHANNEL_ID, channel.id)
                putExtra(STREAM_ID, stream.id)
                streamThumbnailUrl?.let {
                    putExtra(
                        STREAM_THUMBNAIL_URL,
                        streamThumbnailUrl.toString()
                    )
                }
            }
    }
}
