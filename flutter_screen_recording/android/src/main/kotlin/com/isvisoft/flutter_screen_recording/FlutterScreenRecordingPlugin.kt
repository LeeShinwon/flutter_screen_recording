package com.isvisoft.flutter_screen_recording

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.*
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import android.util.DisplayMetrics
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer

class FlutterScreenRecordingPlugin :
    MethodChannel.MethodCallHandler,
    PluginRegistry.ActivityResultListener,
    FlutterPlugin,
    ActivityAware {

    private lateinit var mProjectionManager: MediaProjectionManager
    private var mMediaProjection: MediaProjection? = null
    private var mVirtualDisplay: VirtualDisplay? = null
    private var mMediaRecorder: MediaRecorder? = null
    private var audioRecordInternal: AudioRecord? = null
    private var mediaCodec: MediaCodec? = null
    private var isRecording = false

    private var mDisplayWidth: Int = 1280
    private var mDisplayHeight: Int = 800
    private var mScreenDensity: Int = 0
    private var videoFilePath: String? = null
    private var audioFilePath: String? = null
    private var outputFilePath: String? = null
    private val SCREEN_RECORD_REQUEST_CODE = 333

    private lateinit var _result: MethodChannel.Result
    private var pluginBinding: FlutterPlugin.FlutterPluginBinding? = null
    private var activityBinding: ActivityPluginBinding? = null

    private var serviceConnection: ServiceConnection? = null

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?): Boolean {
        if (requestCode == SCREEN_RECORD_REQUEST_CODE && resultCode == Activity.RESULT_OK && data != null) {
            startRecordScreen(resultCode, data)
            _result.success(true)  // 🔹 유지됨
            return true
        }
        return false
    }

    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        when (call.method) {
            "startRecordScreen" -> {
                _result = result
                startScreenCapture()
            }
            "stopRecordScreen" -> {
                stopRecordScreen()
                result.success(outputFilePath)
            }
            else -> result.notImplemented()
        }
    }

    private fun startScreenCapture() {
        val permissionIntent = mProjectionManager.createScreenCaptureIntent()
        ActivityCompat.startActivityForResult(
            activityBinding!!.activity, permissionIntent, SCREEN_RECORD_REQUEST_CODE, null
        )
    }

    private fun startRecordScreen(resultCode: Int, data: Intent) {
        val activity = activityBinding!!.activity

        val metrics = DisplayMetrics()
        activity.windowManager.defaultDisplay.getMetrics(metrics)
        mScreenDensity = metrics.densityDpi
        mDisplayWidth = metrics.widthPixels
        mDisplayHeight = metrics.heightPixels

        mMediaProjection = mProjectionManager.getMediaProjection(resultCode, data)
        mVirtualDisplay = createVirtualDisplay()

        videoFilePath = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES).absolutePath + "/screen_record.mp4"
        audioFilePath = videoFilePath!!.replace(".mp4", ".aac")
        outputFilePath = videoFilePath!!.replace(".mp4", "_final.mp4")

        mMediaRecorder = MediaRecorder()
        mMediaRecorder?.apply {
            setVideoSource(MediaRecorder.VideoSource.SURFACE)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setOutputFile(videoFilePath)
            setVideoSize(mDisplayWidth, mDisplayHeight)
            setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            setVideoEncodingBitRate(5 * mDisplayWidth * mDisplayHeight)
            setVideoFrameRate(30)
            prepare()
            start()
        }

        startInternalAudioCapture()
    }

    private fun createVirtualDisplay(): VirtualDisplay? {
        return mMediaProjection?.createVirtualDisplay(
            "ScreenRecording",
            mDisplayWidth, mDisplayHeight, mScreenDensity,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            mMediaRecorder?.surface, null, null
        )
    }

    private fun startInternalAudioCapture() {
        val SAMPLE_RATE = 44100
        val BUFFER_SIZE = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT)

        val config = AudioPlaybackCaptureConfiguration.Builder(mMediaProjection!!)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .build()

        audioRecordInternal = AudioRecord.Builder()
            .setAudioPlaybackCaptureConfig(config)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                    .build()
            )
            .setBufferSizeInBytes(BUFFER_SIZE)
            .build()

        mediaCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, SAMPLE_RATE, 2)
        format.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
        format.setInteger(MediaFormat.KEY_BIT_RATE, 128000)

        mediaCodec?.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        mediaCodec?.start()

        isRecording = true
        audioRecordInternal?.startRecording()

        Thread {
            val buffer = ByteArray(BUFFER_SIZE)
            val outputFile = File(audioFilePath!!)
            val outputStream = FileOutputStream(outputFile)

            while (isRecording) {
                val read = audioRecordInternal?.read(buffer, 0, BUFFER_SIZE) ?: 0
                if (read > 0) {
                    outputStream.write(buffer, 0, read)
                }
            }
            outputStream.close()
        }.start()
    }

    private fun stopRecordScreen() {
        isRecording = false
        mMediaRecorder?.stop()
        mMediaRecorder?.reset()
        mVirtualDisplay?.release()
        mMediaProjection?.stop()
        audioRecordInternal?.stop()
        audioRecordInternal?.release()

        mergeAudioAndVideo()
    }

    private fun mergeAudioAndVideo() {
        val muxer = MediaMuxer(outputFilePath!!, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val videoExtractor = MediaExtractor()
        videoExtractor.setDataSource(videoFilePath!!)
        val audioExtractor = MediaExtractor()
        audioExtractor.setDataSource(audioFilePath!!)

        val videoTrackIndex = muxer.addTrack(videoExtractor.getTrackFormat(0))
        val audioTrackIndex = muxer.addTrack(audioExtractor.getTrackFormat(0))

        muxer.start()

        muxer.stop()
        muxer.release()

        Log.d("ScreenRecording", "Merge completed: $outputFilePath")
    }

    override fun onAttachedToEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        pluginBinding = binding
        val channel = MethodChannel(binding.binaryMessenger, "flutter_screen_recording")
        channel.setMethodCallHandler(this)
    }

    override fun onAttachedToActivity(binding: ActivityPluginBinding) {
        activityBinding = binding
        mProjectionManager = activityBinding.activity.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
    }

    override fun onDetachedFromActivity() {}
}
