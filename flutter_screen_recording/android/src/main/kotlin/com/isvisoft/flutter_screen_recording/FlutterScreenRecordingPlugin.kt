package com.isvisoft.flutter_screen_recording;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.*;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Environment;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Surface;

import androidx.core.content.ContextCompat;

import java.io.IOException;
import java.nio.ByteBuffer;

import io.flutter.embedding.engine.plugins.FlutterPlugin;
import io.flutter.embedding.engine.plugins.activity.ActivityAware;
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding;
import io.flutter.plugin.common.MethodCall;
import io.flutter.plugin.common.MethodChannel;
import io.flutter.plugin.common.PluginRegistry;

public class FlutterScreenRecordingPlugin implements MethodChannel.MethodCallHandler, PluginRegistry.ActivityResultListener, FlutterPlugin, ActivityAware {
    private static final int SCREEN_RECORD_REQUEST_CODE = 333;
    private MediaProjectionManager mediaProjectionManager;
    private MediaProjection mediaProjection;
    private VirtualDisplay virtualDisplay;
    private MediaCodec videoEncoder;
    private MediaCodec audioEncoder;
    private MediaMuxer mediaMuxer;
    private Surface inputSurface;
    private AudioRecord audioRecord;
    private boolean isRecording = false;
    private int videoTrackIndex = -1;
    private int audioTrackIndex = -1;
    private ActivityPluginBinding activityBinding;
    private FlutterPlugin.FlutterPluginBinding pluginBinding;

    @Override
    public void onMethodCall(MethodCall call, MethodChannel.Result result) {
        if (call.method.equals("startRecordScreen")) {
            startScreenRecording(call, result);
        } else if (call.method.equals("stopRecordScreen")) {
            stopScreenRecording(result);
        } else {
            result.notImplemented();
        }
    }

    private void startScreenRecording(MethodCall call, MethodChannel.Result result) {
        Intent permissionIntent = mediaProjectionManager.createScreenCaptureIntent();
        activityBinding.getActivity().startActivityForResult(permissionIntent, SCREEN_RECORD_REQUEST_CODE);
    }

    private void stopScreenRecording(MethodChannel.Result result) {
        isRecording = false;
        try {
            if (mediaMuxer != null) {
                mediaMuxer.stop();
                mediaMuxer.release();
            }
        } catch (Exception e) {
            Log.e("ScreenRecording", "Error stopping muxer", e);
        }
        result.success("Recording stopped");
    }

    @Override
    public boolean onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == SCREEN_RECORD_REQUEST_CODE && resultCode == Activity.RESULT_OK) {
            mediaProjection = mediaProjectionManager.getMediaProjection(resultCode, data);
            setupMediaComponents();
            startRecording();
            return true;
        }
        return false;
    }

    private void setupMediaComponents() {
        try {
            mediaMuxer = new MediaMuxer(Environment.getExternalStorageDirectory() + "/recorded_video.mp4", MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            setupVideoEncoder();
            setupAudioEncoder();
        } catch (IOException e) {
            Log.e("ScreenRecording", "Error setting up media components", e);
        }
    }

    private void setupVideoEncoder() throws IOException {
        MediaFormat format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, 1280, 720);
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        format.setInteger(MediaFormat.KEY_BIT_RATE, 5 * 1024 * 1024);
        format.setInteger(MediaFormat.KEY_FRAME_RATE, 30);
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);

        videoEncoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
        videoEncoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        inputSurface = videoEncoder.createInputSurface();
        videoEncoder.start();
    }

    private void setupAudioEncoder() throws IOException {
        MediaFormat format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, 44100, 1);
        format.setInteger(MediaFormat.KEY_BIT_RATE, 128000);
        format.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);

        audioEncoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
        audioEncoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        audioEncoder.start();
    }

    private void startRecording() {
        isRecording = true;

        Thread videoThread = new Thread(() -> {
        MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
        while (isRecording) {
            int outputBufferIndex = videoEncoder.dequeueOutputBuffer(bufferInfo, 10000);
            if (outputBufferIndex >= 0) {
                ByteBuffer outputBuffer = videoEncoder.getOutputBuffer(outputBufferIndex);
                if (videoTrackIndex == -1) {
                    videoTrackIndex = mediaMuxer.addTrack(videoEncoder.getOutputFormat());
                    mediaMuxer.start();
                }
                mediaMuxer.writeSampleData(videoTrackIndex, outputBuffer, bufferInfo);
                videoEncoder.releaseOutputBuffer(outputBufferIndex, false);
            }
        }
    });

        videoThread.start();
    }

    @Override
    public void onAttachedToEngine(FlutterPlugin.FlutterPluginBinding binding) {
        this.pluginBinding = binding;
        mediaProjectionManager = (MediaProjectionManager) binding.getApplicationContext().getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        new MethodChannel(binding.getBinaryMessenger(), "flutter_screen_recording").setMethodCallHandler(this);
    }

    @Override
    public void onDetachedFromEngine(FlutterPlugin.FlutterPluginBinding binding) {}

    @Override
    public void onAttachedToActivity(ActivityPluginBinding binding) {
        this.activityBinding = binding;
        binding.addActivityResultListener(this);
    }

    @Override
    public void onDetachedFromActivityForConfigChanges() {}

    @Override
    public void onReattachedToActivityForConfigChanges(ActivityPluginBinding binding) {
        this.activityBinding = binding;
    }

    @Override
    public void onDetachedFromActivity() {}
}
