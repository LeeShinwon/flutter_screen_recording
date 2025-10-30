package com.isvisoft.flutter_screen_recording

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.*
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.util.DisplayMetrics
import android.util.Log
import android.view.Surface
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.PluginRegistry
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean


class FlutterScreenRecordingPlugin :
    MethodChannel.MethodCallHandler,
    PluginRegistry.ActivityResultListener,
    FlutterPlugin,
    ActivityAware {

    private var pluginBinding: FlutterPlugin.FlutterPluginBinding? = null
    private var activityBinding: ActivityPluginBinding? = null
    private lateinit var _result: MethodChannel.Result

    // 기본 변수
    private var mScreenDensity: Int = 0
    private var mDisplayWidth: Int = 1280
    private var mDisplayHeight: Int = 800
    private var videoName: String? = ""
    private var mTitle = "Recording in progress"
    private var mMessage = "Your screen is being recorded"
    private val SCREEN_RECORD_REQUEST_CODE = 333

    // 미디어 관련 변수
    private var mProjectionManager: MediaProjectionManager? = null
    private var mMediaProjection: MediaProjection? = null
    private var mVirtualDisplay: VirtualDisplay? = null

    // 인코더 관련
    private var muxer: MediaMuxer? = null
    private var inputSurface: Surface? = null
    private var videoEncoder: MediaCodec? = null
    private var audioEncoder: MediaCodec? = null
    private var audioRecord: AudioRecord? = null
    private val muxerStarted = AtomicBoolean(false)
    private var videoTrackIndex = -1
    private var audioTrackIndex = -1

    // 연결용
    private var serviceConnection: ServiceConnection? = null

    override fun onAttachedToEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        pluginBinding = binding
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {}

    override fun onAttachedToActivity(binding: ActivityPluginBinding) {
        activityBinding = binding
        val channel = MethodChannel(pluginBinding!!.binaryMessenger, "flutter_screen_recording")
        channel.setMethodCallHandler(this)
        binding.addActivityResultListener(this)
        mProjectionManager = ContextCompat.getSystemService(
            binding.activity,
            MediaProjectionManager::class.java
        )
    }

    override fun onDetachedFromActivity() {}
    override fun onDetachedFromActivityForConfigChanges() {}
    override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) {
        activityBinding = binding
    }

    // 메서드 호출 처리
    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        val context = pluginBinding!!.applicationContext
        when (call.method) {
            "startRecordScreen" -> {
                _result = result
                mTitle = call.argument("title") ?: "Screen Recording"
                mMessage = call.argument("message") ?: "Your screen is being recorded"
                videoName = call.argument("name") ?: "record"
                val permissionIntent = mProjectionManager!!.createScreenCaptureIntent()
                ActivityCompat.startActivityForResult(
                    activityBinding!!.activity,
                    permissionIntent,
                    SCREEN_RECORD_REQUEST_CODE,
                    null
                )
            }

            "stopRecordScreen" -> {
                try {
                    stopRecordScreen()
                    result.success("Stopped successfully")
                } catch (e: Exception) {
                    Log.e("FlutterScreenRecording", "Stop error: ${e.message}")
                    result.success("Stop error: ${e.message}")
                }
            }

            else -> result.notImplemented()
        }
    }

    // 권한 결과 처리
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?): Boolean {
        val context = pluginBinding!!.applicationContext
        if (requestCode == SCREEN_RECORD_REQUEST_CODE) {
            if (resultCode == Activity.RESULT_OK && data != null) {
                ForegroundService.startService(context, mTitle, mMessage)
                val intentConnection = Intent(context, ForegroundService::class.java)
                serviceConnection = object : ServiceConnection {
                    override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                        try {
                            startRecordScreen(resultCode, data)
                            _result.success(true)
                        } catch (e: Exception) {
                            Log.e("ScreenRecordingPlugin", "startRecordScreen error: ${e.message}")
                            _result.success(false)
                        }
                    }
                    override fun onServiceDisconnected(name: ComponentName?) {}
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

    // 🔹 실제 녹화 시작
    private fun startRecordScreen(resultCode: Int, data: Intent) {
        mMediaProjection = mProjectionManager?.getMediaProjection(resultCode, data)

        // ✅ 콜백 먼저 등록 (Android 14 필수)
        val callback = MediaProjectionCallback()
        mMediaProjection?.registerCallback(callback, null)

        val metrics = DisplayMetrics()
        activityBinding!!.activity.windowManager.defaultDisplay.getRealMetrics(metrics)
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val density = metrics.densityDpi

        val outputFile = File(
            pluginBinding!!.applicationContext.getExternalFilesDir(Environment.DIRECTORY_MOVIES),
            "record-${System.currentTimeMillis()}.mp4"
        )
        muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

        prepareVideoEncoder(width, height, 25, 2_000_000)
        prepareAudioRecord()
        prepareAudioEncoder()

        // ✅ 콜백 등록 후에 가상 디스플레이 생성
        mVirtualDisplay = mMediaProjection?.createVirtualDisplay(
            "ScreenRecord",
            width, height, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            inputSurface, null, null
        )

        Log.d("FlutterScreenRecording", "✅ Recording started: ${outputFile.absolutePath}")
    }


    // 🔹 비디오 인코더 설정
    private fun prepareVideoEncoder(width: Int, height: Int, fps: Int, bitrate: Int) {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
        }

        videoEncoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).apply {
            configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            inputSurface = createInputSurface()
            setCallback(object : MediaCodec.Callback() {
                override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
                    val encodedData = codec.getOutputBuffer(index) ?: return
                    if (info.size > 0 && muxerStarted.get()) {
                        encodedData.position(info.offset)
                        encodedData.limit(info.offset + info.size)
                        synchronized(muxer!!) {
                            muxer?.writeSampleData(videoTrackIndex, encodedData, info)
                        }
                    }
                    codec.releaseOutputBuffer(index, false)
                }
                override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
                    videoTrackIndex = muxer!!.addTrack(format)
                    startMuxerIfReady()
                }
                override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
                    Log.e("VideoEncoder", "Error: ${e.message}")
                }
                override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {}
            })
            start()
        }
    }

    // 🔹 오디오 입력 설정
    private fun prepareAudioRecord() {
        val minBuf = AudioRecord.getMinBufferSize(
            44100,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        audioRecord = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && mMediaProjection != null) {
            val config = AudioPlaybackCaptureConfiguration.Builder(mMediaProjection!!)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .build()
            AudioRecord.Builder()
                .setAudioPlaybackCaptureConfig(config)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(44100)
                        .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                        .build()
                )
                .setBufferSizeInBytes(minBuf * 2)
                .build()
        } else {
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                44100,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                minBuf * 2
            )
        }
        audioRecord?.startRecording()
    }

    // 🔹 오디오 인코더 설정
    private fun prepareAudioEncoder() {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, 44100, 1)
        format.setInteger(MediaFormat.KEY_BIT_RATE, 64000)
        format.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)

        audioEncoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).apply {
            configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            setCallback(object : MediaCodec.Callback() {
                override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {
                    val inputBuffer = codec.getInputBuffer(index) ?: return
                    val read = audioRecord?.read(inputBuffer, inputBuffer.capacity()) ?: -1
                    if (read > 0) codec.queueInputBuffer(index, 0, read, System.nanoTime() / 1000, 0)
                }

                override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
                    val encodedData = codec.getOutputBuffer(index) ?: return
                    if (info.size > 0 && muxerStarted.get()) {
                        encodedData.position(info.offset)
                        encodedData.limit(info.offset + info.size)
                        synchronized(muxer!!) {
                            muxer?.writeSampleData(audioTrackIndex, encodedData, info)
                        }
                    }
                    codec.releaseOutputBuffer(index, false)
                }

                override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
                    audioTrackIndex = muxer!!.addTrack(format)
                    startMuxerIfReady()
                }

                override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
                    Log.e("AudioEncoder", "Error: ${e.message}")
                }
            })
            start()
        }
    }

    private fun startMuxerIfReady() {
        if (!muxerStarted.get() && videoTrackIndex >= 0 && audioTrackIndex >= 0) {
            muxer?.start()
            muxerStarted.set(true)
            Log.d("Muxer", "✅ Muxer started successfully")
        }
    }

    // 🔹 녹화 중지
    private fun stopRecordScreen() {
        try {
            videoEncoder?.stop(); videoEncoder?.release()
            audioEncoder?.stop(); audioEncoder?.release()
            audioRecord?.stop(); audioRecord?.release()
            inputSurface?.release()

            if (muxerStarted.get()) {
                muxer?.stop()
                muxer?.release()
            }

            mVirtualDisplay?.release()
            mMediaProjection?.stop()
            ForegroundService.stopService(pluginBinding!!.applicationContext)

            Log.d("Muxer", "✅ Recording stopped successfully")
        } catch (e: Exception) {
            Log.e("Muxer", "❌ Stop error: ${e.message}")
        }
    }

    private inner class MediaProjectionCallback : MediaProjection.Callback() {
        override fun onStop() {
            Log.d("MediaProjectionCallback", "MediaProjection stopped.")
            stopRecordScreen()
        }
    }

}
