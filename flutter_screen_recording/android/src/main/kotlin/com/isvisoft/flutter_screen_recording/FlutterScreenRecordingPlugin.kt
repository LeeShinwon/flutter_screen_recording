package com.isvisoft.flutter_screen_recording

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.util.DisplayMetrics
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.MethodChannel.Result
import io.flutter.plugin.common.PluginRegistry
import java.io.IOException

import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding


class FlutterScreenRecordingPlugin :
    MethodCallHandler,
    PluginRegistry.ActivityResultListener,
    FlutterPlugin,
    ActivityAware {

    private var mScreenDensity: Int = 0
    var mMediaRecorder: MediaRecorder? = null
    val mProjectionManager: MediaProjectionManager by lazy {
        ContextCompat.getSystemService(
            pluginBinding!!.applicationContext,
            MediaProjectionManager::class.java
        ) ?: throw Exception("MediaProjectionManager not found")
    }
    var mMediaProjection: MediaProjection? = null
    var mMediaProjectionCallback: MediaProjectionCallback? = null
    var mVirtualDisplay: VirtualDisplay? = null
    private var mDisplayWidth: Int = 1280
    private var mDisplayHeight: Int = 800
    private var videoName: String? = ""
    private var mFileName: String? = ""
    private var mTitle = "Your screen is being recorded"
    private var mMessage = "Your screen is being recorded"
    private var recordAudio: Boolean? = false;
    private val SCREEN_RECORD_REQUEST_CODE = 333

    private lateinit var _result: Result

    private var pluginBinding: FlutterPlugin.FlutterPluginBinding? = null
    private var activityBinding: ActivityPluginBinding? = null

    private var serviceConnection: ServiceConnection? = null

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?): Boolean {

        val context = pluginBinding!!.applicationContext

        if (requestCode == SCREEN_RECORD_REQUEST_CODE) {
            if (resultCode == Activity.RESULT_OK) {

                ForegroundService.startService(context, mTitle, mMessage)
                val intentConnection = Intent(context, ForegroundService::class.java)

                serviceConnection = object : ServiceConnection {

                    override fun onServiceConnected(name: ComponentName?, service: IBinder?) {

                        try {
                            startRecordScreen()
                            mMediaProjectionCallback = MediaProjectionCallback()
                            mMediaProjection = mProjectionManager.getMediaProjection(resultCode, data!!)
                            mMediaProjection?.registerCallback(mMediaProjectionCallback!!, null)
                            mVirtualDisplay = createVirtualDisplay()
                            _result.success(true)

                        } catch (e: Throwable) {
                            e.message?.let {
                                Log.e("ScreenRecordingPlugin", it)
                            }
                            _result.success(false)
                        }
                    }

                    override fun onServiceDisconnected(name: ComponentName?) {
                    }
                }

                context.bindService(intentConnection, serviceConnection!!, Activity.BIND_AUTO_CREATE)

            } else {
                ForegroundService.stopService(context)
                _result.success(false)
            }
            return true
        }
        return false
    }

    override fun onMethodCall(call: MethodCall, result: Result) {
        val appContext = pluginBinding!!.applicationContext

        when (call.method) {
            "startRecordScreen" -> {

                try {
                    _result = result
                    val title = call.argument<String?>("title")
                    val message = call.argument<String?>("message")

                    if (!title.isNullOrEmpty()) {
                        mTitle = title
                    }

                    if (!message.isNullOrEmpty()) {
                        mMessage = message
                    }

                    val metrics = DisplayMetrics()

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        val display = activityBinding!!.activity.display
                        display?.getRealMetrics(metrics)
                    } else {
                        @SuppressLint("NewApi")
                        val defaultDisplay = appContext.display
                        defaultDisplay?.getMetrics(metrics)
                    }
                    mScreenDensity = metrics.densityDpi
                    calculateResolution(metrics)
                    videoName = call.argument<String?>("name")
                    recordAudio = call.argument<Boolean?>("audio")

                    val permissionIntent = mProjectionManager.createScreenCaptureIntent()
                    ActivityCompat.startActivityForResult(
                        activityBinding!!.activity,
                        permissionIntent,
                        SCREEN_RECORD_REQUEST_CODE,
                        null
                    )

                } catch (e: Exception) {
                    println("Error onMethodCall startRecordScreen")
                    println(e.message)
                    result.success(false)
                }
            }
            "stopRecordScreen" -> {
                try {
                    serviceConnection?.let {
                        appContext.unbindService(it)
                    }
                    ForegroundService.stopService(pluginBinding!!.applicationContext)
                    if (mMediaRecorder != null) {
                        stopRecordScreen()
                        result.success(mFileName)
                    } else {
                        result.success("")
                    }
                } catch (e: Exception) {
                    result.success("")
                }
            }
            else -> {
                result.notImplemented()
            }
        }
    }

    private fun calculateResolution(metrics: DisplayMetrics) {
        // ✅ 원본 해상도 그대로 사용
        mDisplayHeight = metrics.heightPixels
        mDisplayWidth = metrics.widthPixels

        println("📌 Original Resolution: ${metrics.widthPixels} x ${metrics.heightPixels}")
        println("✅ Using Original Resolution for Recording: $mDisplayWidth x $mDisplayHeight")
    }

    fun createAudioPlaybackConfig(mediaProjection: MediaProjection): AudioPlaybackCaptureConfiguration {
        return AudioPlaybackCaptureConfiguration.Builder(mediaProjection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA) // 🎵 미디어 사운드 캡처
            .build()
    }

    private fun startRecordScreen() {
        try {

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                mMediaRecorder = MediaRecorder(pluginBinding!!.applicationContext)
            } else {
                @Suppress("DEPRECATION")
                mMediaRecorder = MediaRecorder()
            }

            // 파일 저장 경로 설정
            try {
                mFileName = if (Environment.getExternalStorageState() == Environment.MEDIA_MOUNTED) {
                    pluginBinding!!.applicationContext.externalCacheDir?.absolutePath
                } else {
                    pluginBinding!!.applicationContext.cacheDir?.absolutePath
                }
                mFileName += "/$videoName.mp4"
            } catch (e: IOException) {
                println("Error creating name")
                return
            }

            // media recorder 기본 설정 (화면 녹화)
            mMediaRecorder?.setVideoSource(MediaRecorder.VideoSource.SURFACE)
            mMediaRecorder?.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            mMediaRecorder?.setOutputFile(mFileName)
            mMediaRecorder?.setVideoSize(mDisplayWidth, mDisplayHeight)
            mMediaRecorder?.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            mMediaRecorder?.setVideoEncodingBitRate(5 * mDisplayWidth * mDisplayHeight)
            mMediaRecorder?.setVideoFrameRate(30)

            // 내부 오디오 녹음  (Android 10 이상)
            val audioPlaybackConfig = AudioPlaybackCaptureConfiguration.Builder(mMediaProjection!!)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA) // 미디어 오디오 캡처
                .build()

            val audioRecord = AudioRecord.Builder()
                .setAudioPlaybackCaptureConfig(audioPlaybackConfig)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(44100)
                        .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                        .build()
                )
                .setBufferSizeInBytes(AudioRecord.getMinBufferSize(44100, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT))
                .build()

            // 오디오 인코더(MediaCodec) 설정 (PCM → AAC 변환)
            val audioFormat = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, 44100, 2)
            audioFormat.setInteger(MediaFormat.KEY_BIT_RATE, 128000)
            audioFormat.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)

            val audioEncoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            audioEncoder.configure(audioFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            audioEncoder.start()

            // 내부 오디오 캡처 시작
            audioRecord.startRecording()

            // 오디오 데이터를 MediaCodec을 통해 인코딩 (실시간 변환)
            Thread {
                val buffer = ByteBuffer.allocateDirect(4096)
                val bufferInfo = MediaCodec.BufferInfo()

                while (true) {
                    val size = audioRecord.read(buffer, buffer.capacity())
                    if (size > 0) {
                        val inputBufferIndex = audioEncoder.dequeueInputBuffer(10000)
                        if (inputBufferIndex >= 0) {
                            val inputBuffer = audioEncoder.getInputBuffer(inputBufferIndex)
                            inputBuffer?.clear()
                            inputBuffer?.put(buffer)
                            audioEncoder.queueInputBuffer(inputBufferIndex, 0, size, System.nanoTime() / 1000, 0)
                        }

                        val outputBufferIndex = audioEncoder.dequeueOutputBuffer(bufferInfo, 10000)
                        if (outputBufferIndex >= 0) {
                            val encodedData = audioEncoder.getOutputBuffer(outputBufferIndex)
                            encodedData?.let {
                                // MP4 파일로 저장 (MediaMuxer 활용)
                                mediaMuxer.writeSampleData(audioTrackIndex, it, bufferInfo)
                            }
                            audioEncoder.releaseOutputBuffer(outputBufferIndex, false)
                        }
                    }
                }
            }.start()


            mMediaRecorder?.prepare()
            mMediaRecorder?.start()

        } catch (e: Exception) {
            Log.d("--INIT-RECORDER", e.message + "")
            println("Error startRecordScreen")
            println(e.message)
        }
    }

    private fun stopRecordScreen() {
        try {
            println("stopRecordScreen")
            mMediaRecorder?.stop()
            mMediaRecorder?.reset()
            audioRecord?.stop()
            audioRecord?.release()
            audioEncoder?.stop()
            audioEncoder?.release()
            mediaMuxer?.stop()
            mediaMuxer?.release()
            println("stopRecordScreen success")

        } catch (e: Exception) {
            Log.d("--INIT-RECORDER", e.message + "")
            println("stopRecordScreen error")
            println(e.message)

        } finally {
            stopScreenSharing()
        }
    }

    private fun createVirtualDisplay(): VirtualDisplay? {
        try {
            return mMediaProjection?.createVirtualDisplay(
                "MainActivity", mDisplayWidth, mDisplayHeight, mScreenDensity,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, mMediaRecorder?.surface, null, null
            )
        } catch (e: Exception) {
            println("createVirtualDisplay err")
            println(e.message)
            return null
        }
    }

    private fun stopScreenSharing() {
        if (mVirtualDisplay != null) {
            mVirtualDisplay?.release()
            if (mMediaProjection != null && mMediaProjectionCallback != null) {
                mMediaProjection?.unregisterCallback(mMediaProjectionCallback!!)
                mMediaProjection?.stop()
                mMediaProjection = null
            }
            Log.d("TAG", "MediaProjection Stopped")
        }
    }

    override fun onAttachedToEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        pluginBinding = binding;
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {}

    override fun onAttachedToActivity(binding: ActivityPluginBinding) {
        activityBinding = binding;
        val channel = MethodChannel(pluginBinding!!.binaryMessenger, "flutter_screen_recording")
        channel.setMethodCallHandler(this)
        activityBinding!!.addActivityResultListener(this)
    }

    override fun onDetachedFromActivityForConfigChanges() {}

    override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) {
        activityBinding = binding;
    }

    override fun onDetachedFromActivity() {}

    inner class MediaProjectionCallback : MediaProjection.Callback() {
        override fun onStop() {
            mMediaRecorder?.reset()
            mMediaProjection = null
            stopScreenSharing()
        }
    }
}
