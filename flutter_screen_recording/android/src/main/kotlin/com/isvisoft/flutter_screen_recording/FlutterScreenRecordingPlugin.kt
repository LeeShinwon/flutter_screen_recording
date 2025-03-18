package com.isvisoft.flutter_screen_recording

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
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
import java.io.IOException
import java.nio.ByteBuffer

class FlutterScreenRecordingPlugin :
    MethodChannel.MethodCallHandler,
    FlutterPlugin,
    ActivityAware {

    private lateinit var mProjectionManager: MediaProjectionManager
    private var mMediaProjection: MediaProjection? = null
    private var mVirtualDisplay: VirtualDisplay? = null
    private var mMediaRecorder: MediaRecorder? = null
    private var audioRecordInternal: AudioRecord? = null
    private var isRecording = false

    private var mDisplayWidth: Int = 1280
    private var mDisplayHeight: Int = 800
    private var mScreenDensity: Int = 0
    private var videoName: String? = ""
    private var mFileName: String? = ""

    private lateinit var _result: MethodChannel.Result
    private var pluginBinding: FlutterPlugin.FlutterPluginBinding? = null
    private var activityBinding: ActivityPluginBinding? = null

    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        when (call.method) {
            "startRecordScreen" -> {
                _result = result
                videoName = call.argument<String?>("name")
                startScreenCapture()
            }
            "stopRecordScreen" -> {
                stopRecordScreen()
                result.success(mFileName)
            }
            else -> result.notImplemented()
        }
    }

    private fun startScreenCapture() {
        val permissionIntent = mProjectionManager.createScreenCaptureIntent()
        ActivityCompat.startActivityForResult(
            activityBinding!!.activity, permissionIntent, 333, null
        )
    }

    private fun startRecordScreen(resultCode: Int, data: Intent) {
        mMediaProjection = mProjectionManager.getMediaProjection(resultCode, data)
        mVirtualDisplay = createVirtualDisplay()

        mFileName = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES).absolutePath + "/$videoName.mp4"

        mMediaRecorder = MediaRecorder()
        mMediaRecorder?.apply {
            setVideoSource(MediaRecorder.VideoSource.SURFACE)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setOutputFile(mFileName)
            setVideoSize(mDisplayWidth, mDisplayHeight)
            setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            setVideoEncodingBitRate(5 * mDisplayWidth * mDisplayHeight)
            setVideoFrameRate(30)
            prepare()
            start()
        }

        // 내부 오디오 캡처 시작
        startInternalAudioCapture()
    }

    private fun createVirtualDisplay(): VirtualDisplay {
        return mMediaProjection!!.createVirtualDisplay(
            "ScreenRecording", mDisplayWidth, mDisplayHeight, mScreenDensity,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, mMediaRecorder?.surface, null, null
        )
    }

    private fun startInternalAudioCapture() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            Log.e("ScreenRecording", "Internal audio capture is only supported on Android 10+")
            return
        }

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

        isRecording = true
        audioRecordInternal?.startRecording()

        Thread {
            val buffer = ByteArray(BUFFER_SIZE)
            val outputFile = File(mFileName!!.replace(".mp4", ".pcm"))

            try {
                val outputStream = outputFile.outputStream()
                while (isRecording) {
                    val read = audioRecordInternal?.read(buffer, 0, BUFFER_SIZE) ?: 0
                    if (read > 0) {
                        outputStream.write(buffer, 0, read)
                    }
                }
                outputStream.close()
            } catch (e: IOException) {
                Log.e("ScreenRecording", "Error writing audio file", e)
            }
        }.start()
    }

    private fun stopRecordScreen() {
        isRecording = false
        mMediaRecorder?.stop()
        mMediaRecorder?.reset()
        mMediaProjection?.stop()
        mVirtualDisplay?.release()
        audioRecordInternal?.stop()
        audioRecordInternal?.release()

        val pcmFile = File(mFileName!!.replace(".mp4", ".pcm"))
        val wavFile = File(mFileName!!.replace(".mp4", ".wav"))
        convertPcmToWav(pcmFile, wavFile)

        val outputFile = File(mFileName!!.replace(".mp4", "_final.mp4"))
        mergeAudioVideo(File(mFileName!!), wavFile, outputFile)
    }

    override fun onAttachedToEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        pluginBinding = binding
        mProjectionManager = ContextCompat.getSystemService(binding.applicationContext, MediaProjectionManager::class.java)
            ?: throw Exception("MediaProjectionManager not found")
    }

    override fun onAttachedToActivity(binding: ActivityPluginBinding) {
        activityBinding = binding
        val channel = MethodChannel(pluginBinding!!.binaryMessenger, "flutter_screen_recording")
        channel.setMethodCallHandler(this)
    }

    override fun onDetachedFromActivity() {}

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {}

    override fun onDetachedFromActivityForConfigChanges() {}

    override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) {
        activityBinding = binding
    }
}
