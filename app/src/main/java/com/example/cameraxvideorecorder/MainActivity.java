package com.example.cameraxvideorecorder;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.ContentValues;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.media.Image;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.provider.Settings;
import android.telephony.TelephonyManager;
import android.view.View;
import android.widget.ImageButton;
import android.widget.Button;
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
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
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
import com.google.android.gms.auth.api.signin.GoogleSignIn;
import com.google.android.gms.auth.api.signin.GoogleSignInAccount;
import com.google.android.gms.auth.api.signin.GoogleSignInClient;
import com.google.android.gms.auth.api.signin.GoogleSignInOptions;
import com.google.android.gms.common.api.ApiException;
import com.google.android.gms.tasks.Task;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.json.JSONException;
import org.json.JSONObject;
import java.net.NetworkInterface;
import java.util.Collections;

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
    Button btnSignIn, btnCallBackend, btnLoginDevice;
    PreviewView previewView;

    private MediaRecorder mediaRecorder;
    private File videoFile;
    private FrameProccesor frameProccesor;

    int cameraFacing = CameraSelector.LENS_FACING_BACK;
    private final ActivityResultLauncher<String[]> activityResultLauncher = registerForActivityResult(
            new ActivityResultContracts.RequestMultiplePermissions(), result -> {
                boolean cameraGranted = Boolean.TRUE.equals(result.get(Manifest.permission.CAMERA));
                boolean audioGranted = Boolean.TRUE.equals(result.get(Manifest.permission.RECORD_AUDIO));
                if (cameraGranted) {
                    initCamera();
                } else {
                    Toast.makeText(this, "Camera permission is required to use the app", Toast.LENGTH_SHORT).show();
                }
                if (!audioGranted) {
                    Toast.makeText(this, "Audio will be disabled because RECORD_AUDIO permission was denied", Toast.LENGTH_SHORT).show();
                }
            }
    );

    private ProcessCameraProvider cameraProvider;
    private Preview preview;
    private Recorder recorder;
    private ImageAnalysis imageAnalysis;
    private Camera camera;

    // Google Sign-In
    private GoogleSignInClient googleSignInClient;
    private ActivityResultLauncher<Intent> signInLauncher;
    private String idToken;
    private String accountEmail;
    private SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        previewView = findViewById(R.id.viewFinder);
        capture = findViewById(R.id.capture);
        toggleFlash = findViewById(R.id.toggleFlash);
        flipCamera = findViewById(R.id.flipCamera);
        btnSignIn = findViewById(R.id.btnSignIn);
        btnCallBackend = findViewById(R.id.btnCallBackend);
        btnLoginDevice = findViewById(R.id.btnLoginDevice);

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

        // Setup auth storage
        prefs = getSharedPreferences("auth", MODE_PRIVATE);
        idToken = prefs.getString("idToken", null);
        accountEmail = prefs.getString("accountEmail", null);

        // Configure Google Sign-In and launcher
        setupGoogleSignIn();
        btnSignIn.setOnClickListener(v -> startGoogleSignIn());
        btnCallBackend.setOnClickListener(v -> testCallBackend());
        btnLoginDevice.setOnClickListener(v -> loginDevice());
    }

    private void setupGoogleSignIn() {
        GoogleSignInOptions gso = new GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                .requestIdToken(getString(R.string.server_client_id))
                .requestEmail()
                .build();
        googleSignInClient = GoogleSignIn.getClient(this, gso);

        signInLauncher = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
            Intent data = result.getData();
            Task<GoogleSignInAccount> task = GoogleSignIn.getSignedInAccountFromIntent(data);
            try {
                GoogleSignInAccount account = task.getResult(ApiException.class);
                if (account != null) {
                    idToken = account.getIdToken();
                    accountEmail = account.getEmail();
                    if (idToken != null) {
                        prefs.edit().putString("idToken", idToken).putString("accountEmail", accountEmail).apply();
                        Toast.makeText(this, "Signed in with Google", Toast.LENGTH_SHORT).show();
                    } else {
                        Toast.makeText(this, "Failed to obtain ID token", Toast.LENGTH_SHORT).show();
                    }
                }
            } catch (ApiException e) {
                Toast.makeText(this, "Google Sign-In failed: " + e.getStatusCode(), Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void startGoogleSignIn() {
        Intent signInIntent = googleSignInClient.getSignInIntent();
        signInLauncher.launch(signInIntent);
    }

    private Response sendAuthenticatedRequest(String url, String jsonBody) throws IOException {
        if (idToken == null) {
            throw new IOException("No ID token available. Please sign in first.");
        }
        OkHttpClient client = new OkHttpClient();
        MediaType JSON = MediaType.parse("application/json; charset=utf-8");
        RequestBody body = jsonBody != null ? RequestBody.create(jsonBody, JSON) : RequestBody.create(new byte[0], null);
        Request request = new Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer " + idToken)
                .post(body)
                .build();
        return client.newCall(request).execute();
    }

    private Response sendAuthenticatedGet(String url) throws IOException {
        if (idToken == null) {
            throw new IOException("No ID token available. Please sign in first.");
        }
        OkHttpClient client = new OkHttpClient();
        Request request = new Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer " + idToken)
                .get()
                .build();
        return client.newCall(request).execute();
    }

    private void testCallBackend() {
        final String baseUrl = getString(R.string.backend_base_url);
        service.submit(() -> {
            String endpoint = baseUrl.endsWith("/") ? baseUrl + "api/auth/me" : baseUrl + "/api/auth/me";
            try (Response resp = sendAuthenticatedGet(endpoint)) {
                final String body = resp.body() != null ? resp.body().string() : "<empty>";
                runOnUiThread(() -> {
                    if (resp.isSuccessful()) {
                        Toast.makeText(this, "Login OK (" + resp.code() + ")", Toast.LENGTH_LONG).show();
                    } else {
                        Toast.makeText(this, "Backend: " + resp.code() + " - " + body, Toast.LENGTH_LONG).show();
                    }
                });
            } catch (IOException e) {
                runOnUiThread(() -> Toast.makeText(this, "Backend error: " + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        });
    }

    private void loginDevice() {
        final String baseUrl = getString(R.string.backend_base_url);
        final String endpoint = baseUrl.endsWith("/") ? baseUrl + "api/auth/login" : baseUrl + "/api/auth/login";
        service.submit(() -> {
            try {
                JSONObject payload = buildLoginPayload();
                try (Response resp = sendAuthenticatedRequest(endpoint, payload.toString())) {
                    final String body = resp.body() != null ? resp.body().string() : "<empty>";
                    runOnUiThread(() -> {
                        if (resp.isSuccessful()) {
                            Toast.makeText(this, "Login device OK (" + resp.code() + ")", Toast.LENGTH_LONG).show();
                        } else {
                            Toast.makeText(this, "Login device fail: " + resp.code() + " - " + body, Toast.LENGTH_LONG).show();
                        }
                    });
                }
            } catch (IOException | JSONException e) {
                runOnUiThread(() -> Toast.makeText(this, "Login device error: " + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        });
    }

    private JSONObject buildLoginPayload() throws JSONException {
        JSONObject o = new JSONObject();
        // Stable app-specific deviceId stored in prefs
        String deviceId = prefs.getString("deviceId", null);
        if (deviceId == null) {
            deviceId = java.util.UUID.randomUUID().toString();
            prefs.edit().putString("deviceId", deviceId).apply();
        }
        String androidId = Settings.Secure.getString(getContentResolver(), Settings.Secure.ANDROID_ID);

        o.put("deviceId", deviceId);
        o.put("androidId", androidId != null ? androidId : JSONObject.NULL);
        // Wi-Fi MAC (puede no estar disponible o ser 02:00:00:00:00:00)
        String wifiMac = getWifiMacAddress();
        o.put("macAddress", wifiMac != null ? wifiMac : JSONObject.NULL);
        o.put("bluetoothMac", JSONObject.NULL);
        o.put("simSerialNumber", JSONObject.NULL);
        o.put("phoneNumber", JSONObject.NULL);
        // Carrier name sin permisos sensibles
        String carrier = getCarrierName();
        o.put("carrierName", carrier != null ? carrier : JSONObject.NULL);

        o.put("manufacturer", android.os.Build.MANUFACTURER);
        o.put("model", android.os.Build.MODEL);
        o.put("osVersion", android.os.Build.VERSION.RELEASE);
        // Device name: fallback to model
        o.put("deviceName", android.os.Build.MODEL);
        // Cuenta Google asociada al login
        o.put("email", accountEmail != null ? accountEmail : JSONObject.NULL);
        return o;
    }

    private String getCarrierName() {
        try {
            TelephonyManager tm = (TelephonyManager) getSystemService(TELEPHONY_SERVICE);
            if (tm != null) {
                String name = tm.getNetworkOperatorName();
                return (name != null && !name.isEmpty()) ? name : null;
            }
        } catch (Exception ignored) {}
        return null;
    }

    private String getWifiMacAddress() {
        try {
            for (NetworkInterface nif : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                byte[] mac = nif.getHardwareAddress();
                if (mac == null || mac.length == 0) continue;
                // Prefer wlan0 si existe
                String iface = nif.getName();
                StringBuilder sb = new StringBuilder();
                for (byte b : mac) sb.append(String.format("%02X:", b));
                if (sb.length() > 0) sb.setLength(sb.length() - 1);
                String addr = sb.toString();
                if ("wlan0".equalsIgnoreCase(iface) || (addr != null && !addr.equals("02:00:00:00:00:00"))) {
                    return addr;
                }
            }
        } catch (Exception ignored) {}
        return null;
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
            boolean hasAudioPermission = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
            androidx.camera.video.PendingRecording pendingRecording = videoCapture.getOutput()
                    .prepareRecording(this, outputOptions);
            if (hasAudioPermission) {
                pendingRecording = pendingRecording.withAudioEnabled();
            }
            Recording newRecording = pendingRecording
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

    private void requestPermissions() {
        String[] permissions = new String[]{
                Manifest.permission.CAMERA,
                Manifest.permission.RECORD_AUDIO
        };
        activityResultLauncher.launch(permissions);
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

                // Configurar ImageAnalysis para obtener frames y calcular hash cada 5 frames
                imageAnalysis = new ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build();
                imageAnalysis.setAnalyzer(service, imageProxy -> {
                    try {
                        if (recording != null) { // Solo procesar cuando estamos grabando
                            Image image = imageProxy.getImage();
                            if (image != null) {
                                processFrame(image); // delega en FrameProccesor
                            }
                        }
                    } finally {
                        imageProxy.close();
                    }
                });

                // Configurar CameraSelector
                CameraSelector cameraSelector = new CameraSelector.Builder()
                        .requireLensFacing(cameraFacing)
                        .build();

                // Unbind existing use cases
                cameraProvider.unbindAll();

                // Bind use cases to lifecycle
                camera = cameraProvider.bindToLifecycle(
                        this, cameraSelector, preview, videoCapture, imageAnalysis);

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