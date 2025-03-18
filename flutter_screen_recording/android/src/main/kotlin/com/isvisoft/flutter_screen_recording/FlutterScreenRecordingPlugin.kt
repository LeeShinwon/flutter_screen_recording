package com.isvisoft.flutter_screen_recording

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
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
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.LinkedBlockingQueue

class FlutterScreenRecordingPlugin :
    MethodChannel.MethodCallHandler,
    FlutterPlugin,
    ActivityAware {

    private var mProjectionManager: MediaProjectionManager? = null
    private var mMediaProjection: MediaProjection? = null
    private var mVirtualDisplay: VirtualDisplay? = null
    @Suppress("DEPRECATION")
    private var mMediaRecorder: MediaRecorder? = null // DEPRECATION 경고 억제
    private var audioRecordInternal: AudioRecord? = null
    private var audioEncoder: MediaCodec? = null
    private var muxer: MediaMuxer? = null
    private var isRecording = false

    private var mDisplayWidth: Int = 0
    private var mDisplayHeight: Int = 0
    private var mScreenDensity: Int = 0
    private var videoName: String? = ""
    private var mVideoFileName: String? = "" // 임시 영상 파일 이름
    private var mFinalFileName: String? = "" // 최종 병합 파일 이름

    private lateinit var _result: MethodChannel.Result
    private var pluginBinding: FlutterPlugin.FlutterPluginBinding? = null
    private var activityBinding: ActivityPluginBinding? = null

    private val SCREEN_RECORD_REQUEST_CODE = 333
    private val SAMPLE_RATE = 44100
    private val audioQueue = LinkedBlockingQueue<ByteArray>()
    private val encodedAudioQueue = LinkedBlockingQueue<Pair<ByteBuffer, MediaCodec.BufferInfo>>()
    private var recordingStartTime: Long = 0

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
                result.success(mFinalFileName)
            }
            else -> result.notImplemented()
        }
    }

    private fun setupDisplayMetrics() {
        val metrics = DisplayMetrics()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            activityBinding?.activity?.display?.getRealMetrics(metrics)
        } else {
            @SuppressLint("NewApi")
            pluginBinding?.applicationContext?.display?.getMetrics(metrics)
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
        activityBinding?.activity?.let {
            ActivityCompat.startActivityForResult(it, permissionIntent, SCREEN_RECORD_REQUEST_CODE, null)
        }
    }

    private fun startRecordScreen(resultCode: Int, data: Intent) {
        mMediaProjection = mProjectionManager!!.getMediaProjection(resultCode, data)
        mVideoFileName = "${pluginBinding!!.applicationContext.getExternalFilesDir(Environment.DIRECTORY_MOVIES)?.absolutePath}/${videoName}_video.mp4"
        mFinalFileName = "${pluginBinding!!.applicationContext.getExternalFilesDir(Environment.DIRECTORY_MOVIES)?.absolutePath}/${videoName}.mp4"

        // 영상만 녹화
        mMediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(pluginBinding!!.applicationContext)
        } else {
            MediaRecorder() // DEPRECATION 경고는 변수 선언에서 억제됨
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

        // MediaMuxer 초기화
        muxer = MediaMuxer(mFinalFileName!!, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

        // 내부 오디오 캡처 및 인코딩 시작
        recordingStartTime = System.nanoTime() / 1000
        startInternalAudioCapture()
    }

    private fun createVirtualDisplay(): VirtualDisplay? {
        return mMediaProjection?.createVirtualDisplay(
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

        // AAC 인코더 설정
        audioEncoder = MediaCodec.createEncoderByType("audio/mp4a-latm")
        val audioFormat = MediaFormat.createAudioFormat("audio/mp4a-latm", SAMPLE_RATE, 2)
        audioFormat.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
        audioFormat.setInteger(MediaFormat.KEY_BIT_RATE, 128000)
        audioFormat.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, BUFFER_SIZE)
        audioEncoder?.configure(audioFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val audioTrackIndex = muxer?.addTrack(audioFormat) ?: -1
        audioEncoder?.start()

        isRecording = true
        audioRecordInternal?.startRecording()

        // PCM 데이터를 인코딩 및 muxing
        Thread {
            val buffer = ByteArray(BUFFER_SIZE)
            while (isRecording) {
                val read = audioRecordInternal?.read(buffer, 0, BUFFER_SIZE) ?: 0
                if (read > 0) {
                    audioQueue.offer(buffer.copyOf(read))
                }
            }
        }.start()

        Thread {
            encodeAudioToAAC(audioTrackIndex)
        }.start()

        // 영상 데이터 muxing 준비
        Thread { muxVideo(audioTrackIndex) }.start()
    }

    private fun encodeAudioToAAC(audioTrackIndex: Int) {
        val bufferInfo = MediaCodec.BufferInfo()
        while (isRecording || !audioQueue.isEmpty()) {
            val inputBufferIndex = audioEncoder?.dequeueInputBuffer(10000) ?: -1
            if (inputBufferIndex >= 0) {
                val inputBuffer = audioEncoder?.getInputBuffer(inputBufferIndex)
                val pcmData = audioQueue.poll()
                if (pcmData != null) {
                    inputBuffer?.clear()
                    inputBuffer?.put(pcmData)
                    val presentationTimeUs = (System.nanoTime() / 1000) - recordingStartTime
                    audioEncoder?.queueInputBuffer(inputBufferIndex, 0, pcmData.size, presentationTimeUs, 0)
                }
            }

            val outputBufferIndex = audioEncoder?.dequeueOutputBuffer(bufferInfo, 10000) ?: -1
            if (outputBufferIndex >= 0) {
                val outputBuffer = audioEncoder?.getOutputBuffer(outputBufferIndex)
                if (bufferInfo.size > 0 && muxer != null) {
                    muxer?.writeSampleData(audioTrackIndex, outputBuffer!!, bufferInfo)
                }
                audioEncoder?.releaseOutputBuffer(outputBufferIndex, false)
            }
        }
    }

    private fun muxVideo(audioTrackIndex: Int) {
        val extractor = MediaExtractor()
        extractor.setDataSource(mVideoFileName!!)
        var videoTrackIndex = -1

        for (i in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(i)
            if (format.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true) {
                extractor.selectTrack(i)
                videoTrackIndex = muxer?.addTrack(format) ?: -1
                break
            }
        }

        muxer?.start()

        val videoBuffer = ByteBuffer.allocate(1024 * 1024)
        val videoBufferInfo = MediaCodec.BufferInfo()
        while (true) {
            val sampleSize = extractor.readSampleData(videoBuffer, 0)
            if (sampleSize < 0) break
            videoBufferInfo.size = sampleSize
            videoBufferInfo.presentationTimeUs = extractor.sampleTime
            videoBufferInfo.flags = extractor.sampleFlags
            muxer?.writeSampleData(videoTrackIndex, videoBuffer, videoBufferInfo)
            extractor.advance()
        }

        extractor.release()
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
        audioEncoder?.stop()
        audioEncoder?.release()
        muxer?.stop()
        muxer?.release()

        // 임시 영상 파일 삭제
        File(mVideoFileName!!).delete()
    }

    override fun onAttachedToEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        pluginBinding = binding
        val channel = MethodChannel(binding.binaryMessenger, "flutter_screen_recording")
        channel.setMethodCallHandler(this)
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        pluginBinding = null
    }

    override fun onAttachedToActivity(binding: ActivityPluginBinding) {
        activityBinding = binding
        binding.addActivityResultListener { requestCode, resultCode, data ->
            if (requestCode == SCREEN_RECORD_REQUEST_CODE) {
                if (resultCode == Activity.RESULT_OK) {
                    startRecordScreen(resultCode, data!!)
                    _result.success(true)
                } else {
                    _result.success(false)
                }
                true
            } else {
                false
            }
        }
    }

    override fun onDetachedFromActivity() {
        activityBinding = null
    }

    override fun onDetachedFromActivityForConfigChanges() {}

    override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) {
        activityBinding = binding
    }
}