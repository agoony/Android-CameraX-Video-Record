package com.example.cameraxvideorecorder;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.ContentValues;
import android.content.pm.PackageManager;
import android.media.Image;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.view.View;
import android.widget.ImageButton;
import android.widget.Toast;

import android.media.MediaRecorder;

import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.core.content.ContextCompat;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.Camera;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.Preview;
import androidx.camera.video.MediaStoreOutputOptions;
import androidx.camera.video.Quality;
import androidx.camera.video.QualitySelector;
import androidx.camera.video.Recorder;
import androidx.camera.video.Recording;
import androidx.camera.video.VideoCapture;
import androidx.camera.video.VideoRecordEvent;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;

import com.google.common.util.concurrent.ListenableFuture;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity implements FrameProccesor.FrameProcessor {
    ExecutorService service;
    Recording recording = null;
    VideoCapture<Recorder> videoCapture = null;
    ImageButton capture, toggleFlash, flipCamera;
    PreviewView previewView;

    private MediaRecorder mediaRecorder;
    private File videoFile;
    private FrameProccesor frameProccesor;

    int cameraFacing = CameraSelector.LENS_FACING_BACK;
    private final ActivityResultLauncher<String> activityResultLauncher = registerForActivityResult(new ActivityResultContracts.RequestPermission(), result -> {
        if (result) {
            if (checkPermissions()) {
                initCamera();
            }
        } else {
            Toast.makeText(this, "Permissions are required to use the app", Toast.LENGTH_SHORT).show();
        }
    });

    private ProcessCameraProvider cameraProvider;
    private Preview preview;
    private Recorder recorder;
    private Camera camera;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        previewView = findViewById(R.id.viewFinder);
        capture = findViewById(R.id.capture);
        toggleFlash = findViewById(R.id.toggleFlash);
        flipCamera = findViewById(R.id.flipCamera);

        frameProccesor = new FrameProccesor(this);
        
        // Initialize the frame processor with our hash processing
        frameProccesor.setFrameProcessor(this);

        capture.setOnClickListener(view -> {
            if (!checkPermissions()) {
                requestPermissions();
            } else {
                captureVideo();
            }
        });

        if (!checkPermissions()) {
            requestPermissions();
        } else {
            initCamera();
        }

        flipCamera.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                if (cameraFacing == CameraSelector.LENS_FACING_BACK) {
                    cameraFacing = CameraSelector.LENS_FACING_FRONT;
                } else {
                    cameraFacing = CameraSelector.LENS_FACING_BACK;
                }
                initCamera();
            }
        });

        service = Executors.newSingleThreadExecutor();
    }

    @SuppressLint("MissingPermission")
    public void captureVideo() {
        try {
            if (recording != null) {
                recording.stop();
                recording = null;
                capture.setImageResource(R.drawable.round_fiber_manual_record_24);
                
                // Reset hash chain when recording stops
                frameProccesor.resetHashChain();
                return;
            }

            // Create a unique filename
            String name = new SimpleDateFormat("yyyy-MM-dd-HH-mm-ss-SSS", Locale.getDefault()).format(System.currentTimeMillis());
            ContentValues contentValues = new ContentValues();
            contentValues.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
            contentValues.put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4");
            contentValues.put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/CameraX-Video");

            // Create a temporary file to store the video
            File videoFile = new File(getExternalFilesDir(null), name + ".mp4");
            
            // Create MediaStoreOutputOptions with the file
            MediaStoreOutputOptions outputOptions = new MediaStoreOutputOptions.Builder(getContentResolver(), MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
                    .setContentValues(contentValues)
                    .build();

            // Prepare recording
            Recording newRecording = videoCapture.getOutput()
                    .prepareRecording(this, outputOptions)
                    .withAudioEnabled()
                    .start(ContextCompat.getMainExecutor(this), videoRecordEvent -> {
                        if (videoRecordEvent instanceof VideoRecordEvent.Start) {
                            capture.setEnabled(true);
                            capture.setImageResource(R.drawable.round_stop_circle_24);
                            Toast.makeText(this, "Recording started", Toast.LENGTH_SHORT).show();
                            
                            // Reset hash chain when recording starts
                            frameProccesor.resetHashChain();
                        } else if (videoRecordEvent instanceof VideoRecordEvent.Finalize) {
                            if (!((VideoRecordEvent.Finalize) videoRecordEvent).hasError()) {
                                // Move the temporary file to the final location
                                Uri finalUri = ((VideoRecordEvent.Finalize) videoRecordEvent).getOutputResults().getOutputUri();
                                String msg = "Video capture succeeded: " + finalUri;
                                Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
                                
                                // Get final hash and display it
                                String finalHash = frameProccesor.getCurrentHash();
                                Toast.makeText(this, "Final hash: " + finalHash, Toast.LENGTH_LONG).show();
                            } else {
                                String msg = "Error: " + ((VideoRecordEvent.Finalize) videoRecordEvent).getError();
                                Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
                            }
                            recording = null;
                            capture.setImageResource(R.drawable.round_fiber_manual_record_24);
                        }
                    });

            // Set the recording reference only after successful start
            recording = newRecording;

        } catch (Exception e) {
            Toast.makeText(this, "Error starting video capture: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            if (recording != null) {
                recording.stop();
                recording = null;
            }
        }
    }

    private void toggleFlash() {
        if (camera != null && camera.getCameraInfo().hasFlashUnit()) {
            if (camera.getCameraInfo().getTorchState().getValue() == 0) {
                camera.getCameraControl().enableTorch(true);
                toggleFlash.setImageResource(R.drawable.round_flash_off_24);
            } else {
                camera.getCameraControl().enableTorch(false);
                toggleFlash.setImageResource(R.drawable.round_flash_on_24);
            }
        } else {
            Toast.makeText(this, "Flash is not available", Toast.LENGTH_SHORT).show();
        }
    }

    private boolean checkPermissions() {
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            String[] permissions = {
                Manifest.permission.CAMERA,
                Manifest.permission.RECORD_AUDIO
            };
            for (String permission : permissions) {
                if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                    return false;
                }
            }
            return true;
        }
        return true;
    }

    private void requestPermissions() {
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            String[] permissions = {
                Manifest.permission.CAMERA,
                Manifest.permission.RECORD_AUDIO
            };
            for (String permission : permissions) {
                if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                    activityResultLauncher.launch(permission);
                    return;
                }
            }
        }
    }

    private void initCamera() {
        ListenableFuture<ProcessCameraProvider> cameraProviderFuture = ProcessCameraProvider.getInstance(this);
        
        cameraProviderFuture.addListener(() -> {
            try {
                cameraProvider = cameraProviderFuture.get();
                
                // Configurar Preview
                preview = new Preview.Builder()
                        .build();
                preview.setSurfaceProvider(previewView.getSurfaceProvider());

                // Configurar Recorder y VideoCapture
                recorder = new Recorder.Builder()
                        .setQualitySelector(QualitySelector.from(Quality.HIGHEST))
                        .build();
                videoCapture = VideoCapture.withOutput(recorder);

                // Configurar CameraSelector
                CameraSelector cameraSelector = new CameraSelector.Builder()
                        .requireLensFacing(cameraFacing)
                        .build();

                // Unbind existing use cases
                cameraProvider.unbindAll();

                // Bind use cases to lifecycle
                camera = cameraProvider.bindToLifecycle(
                        this, cameraSelector, preview, videoCapture);

                // Configurar flash
                toggleFlash.setOnClickListener(view -> toggleFlash());

            } catch (Exception e) {
                e.printStackTrace();
                Toast.makeText(this, "Error initializing camera: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            }
        }, ContextCompat.getMainExecutor(this));
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        service.shutdown();
        if (cameraProvider != null) {
            cameraProvider.unbindAll();
        }
    }

    private File getOutputFile() {
        File dir = new File(getExternalFilesDir(null), "videos");
        if (!dir.exists()) dir.mkdirs();
        return new File(dir, "video_stream.ts");  // Cambiamos a .ts
    }

    @Override
    public void processFrame(Image image) {
        // Frame processing is handled by FrameProccesor's processFrame method
        frameProccesor.processFrame(image);
    }
}