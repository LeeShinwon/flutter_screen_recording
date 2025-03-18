package com.isvisoft.flutter_screen_recording

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.*
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Environment
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
    PluginRegistry.ActivityResultListener,
    FlutterPlugin,
    ActivityAware {

    private var mProjectionManager: MediaProjectionManager? = null
    private var mMediaProjection: MediaProjection? = null
    private var mVirtualDisplay: VirtualDisplay? = null
    private var mMediaRecorder: MediaRecorder? = null
    private var audioRecordInternal: AudioRecord? = null
    private var isRecording = false

    private var mDisplayWidth: Int = 0
    private var mDisplayHeight: Int = 0
    private var mScreenDensity: Int = 0
    private var videoName: String? = ""
    private var mVideoFileName: String? = "" // 영상 파일 이름
    private var mAudioFileName: String? = "" // 오디오 파일 이름
    private var mFinalFileName: String? = "" // 최종 병합 파일 이름

    private lateinit var _result: MethodChannel.Result
    private var pluginBinding: FlutterPlugin.FlutterPluginBinding? = null
    private var activityBinding: ActivityPluginBinding? = null

    private val SCREEN_RECORD_REQUEST_CODE = 333

    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        when (call.method) {
            "startRecordScreen" -> {
                _result = result
                videoName = call.argument<String?>("name")
                setupDisplayMetrics()
                startScreenCapture()
            }
            "stopRecordScreen" -> {
                stopRecordScreen()
                mergeVideoAndAudio()
                result.success(mFinalFileName)
            }
            else -> result.notImplemented()
        }
    }

    private fun setupDisplayMetrics() {
        val metrics = DisplayMetrics()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            activityBinding!!.activity.display?.getRealMetrics(metrics)
        } else {
            @SuppressLint("NewApi")
            val defaultDisplay = pluginBinding!!.applicationContext.display
            defaultDisplay?.getMetrics(metrics)
        }
        mScreenDensity = metrics.densityDpi
        mDisplayWidth = metrics.widthPixels
        mDisplayHeight = metrics.heightPixels
    }

    private fun startScreenCapture() {
        mProjectionManager = ContextCompat.getSystemService(
            pluginBinding!!.applicationContext,
            MediaProjectionManager::class.java
        ) ?: throw Exception("MediaProjectionManager not found")
        val permissionIntent = mProjectionManager!!.createScreenCaptureIntent()
        ActivityCompat.startActivityForResult(
            activityBinding!!.activity, permissionIntent, SCREEN_RECORD_REQUEST_CODE, null
        )
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?): Boolean {
        if (requestCode == SCREEN_RECORD_REQUEST_CODE) {
            if (resultCode == Activity.RESULT_OK) {
                startRecordScreen(resultCode, data!!)
                _result.success(true)
            } else {
                _result.success(false)
            }
            return true
        }
        return false
    }

    private fun startRecordScreen(resultCode: Int, data: Intent) {
        mMediaProjection = mProjectionManager!!.getMediaProjection(resultCode, data)
        mVideoFileName = "${Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES).absolutePath}/${videoName}_video.mp4"
        mAudioFileName = "${Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES).absolutePath}/${videoName}_audio.pcm"
        mFinalFileName = "${Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES).absolutePath}/${videoName}.mp4"

        // 영상만 녹화
        mMediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(pluginBinding!!.applicationContext)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }
        mMediaRecorder?.apply {
            setVideoSource(MediaRecorder.VideoSource.SURFACE)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setOutputFile(mVideoFileName)
            setVideoSize(mDisplayWidth, mDisplayHeight)
            setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            setVideoEncodingBitRate(5 * mDisplayWidth * mDisplayHeight)
            setVideoFrameRate(30)
            prepare()
            start()
        }

        mVirtualDisplay = createVirtualDisplay()

        // 내부 오디오 캡처 시작
        startInternalAudioCapture()
    }

    private fun createVirtualDisplay(): VirtualDisplay {
        return mMediaProjection!!.createVirtualDisplay(
            "ScreenRecording",
            mDisplayWidth,
            mDisplayHeight,
            mScreenDensity,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            mMediaRecorder?.surface,
            null,
            null
        )
    }

    private fun startInternalAudioCapture() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            Log.e("ScreenRecording", "Internal audio capture is only supported on Android 10+")
            return
        }

        val SAMPLE_RATE = 44100
        val BUFFER_SIZE = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_STEREO,
            AudioFormat.ENCODING_PCM_16BIT
        )

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
            val outputFile = File(mAudioFileName!!)
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
        mMediaRecorder?.release()
        mVirtualDisplay?.release()
        mMediaProjection?.stop()
        audioRecordInternal?.stop()
        audioRecordInternal?.release()
    }

    // FFmpeg를 사용하여 영상과 오디오 병합 (예시)
    private fun mergeVideoAndAudio() {
        try {
            val audioWavFile = File(mAudioFileName!!.replace(".pcm", ".wav"))
            convertPcmToWav(File(mAudioFileName!!), audioWavFile)

            // FFmpeg 명령어 실행 (FFmpeg 라이브러리 필요)
            val ffmpegCommand = arrayOf(
                "-i", mVideoFileName!!,
                "-i", audioWavFile.absolutePath,
                "-c:v", "copy",
                "-c:a", "aac",
                mFinalFileName!!
            )
            // FFmpeg 실행 로직은 별도로 추가해야 함 (예: FFmpeg-Android-Java 라이브러리 사용)
            Log.d("ScreenRecording", "Merging video and audio: $mFinalFileName")
        } catch (e: Exception) {
            Log.e("ScreenRecording", "Error merging video and audio", e)
        }
    }

    private fun convertPcmToWav(pcmFile: File, wavFile: File) {
        val pcmData = pcmFile.readBytes()
        val totalDataLen = pcmData.size + 44 - 8
        val byteRate = 44100 * 2 * 16 / 8

        val outputStream = wavFile.outputStream()
        outputStream.write("RIFF".toByteArray())
        outputStream.write(intToByteArray(totalDataLen))
        outputStream.write("WAVE".toByteArray())
        outputStream.write("fmt ".toByteArray())
        outputStream.write(intToByteArray(16))
        outputStream.write(shortToByteArray(1)) // PCM 포맷
        outputStream.write(shortToByteArray(2)) // 스테레오
        outputStream.write(intToByteArray(44100)) // 샘플레이트
        outputStream.write(intToByteArray(byteRate)) // 바이트레이트
        outputStream.write(shortToByteArray(4)) // 블록 정렬
        outputStream.write(shortToByteArray(16)) // 비트/샘플
        outputStream.write("data".toByteArray())
        outputStream.write(intToByteArray(pcmData.size))
        outputStream.write(pcmData)
        outputStream.close()
    }

    private fun intToByteArray(value: Int): ByteArray {
        return ByteBuffer.allocate(4).putInt(value).array()
    }

    private fun shortToByteArray(value: Short): ByteArray {
        return ByteBuffer.allocate(2).putShort(value).array()
    }

    override fun onAttachedToEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        pluginBinding = binding
        val channel = MethodChannel(binding.binaryMessenger, "flutter_screen_recording")
        channel.setMethodCallHandler(this)
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {}

    override fun onAttachedToActivity(binding: ActivityPluginBinding) {
        activityBinding = binding
        activityBinding!!.addActivityResultListener(this)
    }

    override fun onDetachedFromActivity() {}

    override fun onDetachedFromActivityForConfigChanges() {}

    override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) {
        activityBinding = binding
    }
}