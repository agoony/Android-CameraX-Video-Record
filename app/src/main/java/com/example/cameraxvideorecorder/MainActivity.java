package com.example.cameraxvideorecorder;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.ContentValues;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.Image;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.provider.Settings;
import android.telephony.TelephonyManager;
import android.view.View;
import android.widget.ImageButton;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.Toast;

import android.media.MediaRecorder;

import androidx.appcompat.app.AlertDialog;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.video.PendingRecording;
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

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Collections;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.SimpleDateFormat;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import androidx.annotation.OptIn;
import androidx.camera.core.ExperimentalGetImage;
import com.google.mlkit.vision.barcode.BarcodeScanner;
import com.google.mlkit.vision.barcode.BarcodeScanning;
import com.google.mlkit.vision.barcode.common.Barcode;
import com.google.mlkit.vision.common.InputImage;

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

    // QR Scanning and Trust Polling
    private volatile boolean isScanningQrForVideo = false;
    private volatile String targetQrHash = null;
    private volatile String pendingVideoId = null;
    private BarcodeScanner barcodeScanner;
    private AlertDialog currentQrDialog;
    private ScheduledExecutorService trustPollingScheduler;
    private ScheduledFuture<?> trustPollingTask;

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

        barcodeScanner = BarcodeScanning.getClient();

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
            deviceId = UUID.randomUUID().toString();
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

        o.put("manufacturer", Build.MANUFACTURER);
        o.put("model", Build.MODEL);
        o.put("osVersion", Build.VERSION.RELEASE);
        // Device name: fallback to model
        o.put("deviceName", Build.MODEL);
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

            if (isScanningQrForVideo) {
                isScanningQrForVideo = false;
                stopTrustPolling();
                Toast.makeText(this, "Cancelled QR scanning", Toast.LENGTH_SHORT).show();
                return;
            }

            // Create a unique filename
            String name = new SimpleDateFormat("yyyy-MM-dd-HH-mm-ss-SSS", Locale.getDefault()).format(System.currentTimeMillis());
            
            // Register video hash and show QR code, waiting for trust status
            registerVideoHashAndShowQR(name);

        } catch (Exception e) {
            Toast.makeText(this, "Error in captureVideo: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void registerVideoHashAndShowQR(final String videoId) {
        final String baseUrl = getString(R.string.backend_base_url);
        final String endpoint = baseUrl.endsWith("/") ? baseUrl + "api/videohash/register" : baseUrl + "/api/videohash/register";
        
        runOnUiThread(() -> Toast.makeText(MainActivity.this, "Registering video hash with backend...", Toast.LENGTH_SHORT).show());
        
        service.submit(() -> {
            try {
                JSONObject payload = buildVideoHashPayload(videoId);
                try (Response resp = sendAuthenticatedRequest(endpoint, payload.toString())) {
                    final String body = resp.body() != null ? resp.body().string() : "";
                    if (!resp.isSuccessful()) {
                        runOnUiThread(() -> Toast.makeText(MainActivity.this, "Failed to register video hash: " + resp.code() + " - " + body, Toast.LENGTH_LONG).show());
                        return;
                    }
                    
                    String hashCode = videoId; // fallback
                    try {
                        JSONObject responseJson = new JSONObject(body);
                        if (responseJson.has("hash")) {
                            hashCode = responseJson.getString("hash");
                        } else if (responseJson.has("hashCode")) {
                            hashCode = responseJson.getString("hashCode");
                        } else if (responseJson.has("hashValue")) {
                            hashCode = responseJson.getString("hashValue");
                        } else if (responseJson.has("id")) {
                            hashCode = responseJson.getString("id");
                        } else {
                            hashCode = body.trim();
                        }
                    } catch (Exception e) {
                        if (!body.isEmpty()) {
                            hashCode = body.trim();
                        }
                    }
                    
                    final String finalHashCode = hashCode;
                    String qrUrl = "https://api.qrserver.com/v1/create-qr-code/?size=400x400&data=" + Uri.encode(finalHashCode);
                    OkHttpClient client = new OkHttpClient();
                    Request qrRequest = new Request.Builder().url(qrUrl).build();
                    try (Response qrResp = client.newCall(qrRequest).execute()) {
                        if (qrResp.isSuccessful() && qrResp.body() != null) {
                            Bitmap qrBitmap = BitmapFactory.decodeStream(qrResp.body().byteStream());
                            runOnUiThread(() -> showQRDialog(finalHashCode, qrBitmap, videoId));
                        } else {
                            runOnUiThread(() -> Toast.makeText(MainActivity.this, "Failed to fetch QR code image", Toast.LENGTH_SHORT).show());
                        }
                    }
                }
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(MainActivity.this, "Error: " + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        });
    }

    private void showQRDialog(String hashCode, Bitmap qrBitmap, final String videoId) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Video Registered");
        builder.setMessage("Hash: " + hashCode + "\n\nWaiting for trust status 'preparedTrust'...");
        
        ImageView imageView = new ImageView(this);
        imageView.setImageBitmap(qrBitmap);
        int padding = (int) (16 * getResources().getDisplayMetrics().density);
        imageView.setPadding(padding, padding, padding, padding);
        builder.setView(imageView);
        
        builder.setNegativeButton("Cancel", (dialog, which) -> {
            stopTrustPolling();
            dialog.dismiss();
        });
        builder.setOnDismissListener(dialog -> {
            stopTrustPolling();
            currentQrDialog = null;
        });
        builder.setCancelable(false);
        
        currentQrDialog = builder.create();
        currentQrDialog.show();

        // Start polling backend for trust status every 200 ms
        startTrustPolling(hashCode, videoId);
    }

    private void startTrustPolling(final String hashCode, final String videoId) {
        stopTrustPolling();
        trustPollingScheduler = Executors.newSingleThreadScheduledExecutor();
        trustPollingTask = trustPollingScheduler.scheduleWithFixedDelay(() -> {
            try {
                String url = "http://agoony.freedynamicdns.net:8080/api/videohash/truststatus?hashValue=" + Uri.encode(hashCode);
                OkHttpClient client = new OkHttpClient();
                Request request = new Request.Builder().url(url).build();
                try (Response resp = client.newCall(request).execute()) {
                    if (resp.isSuccessful() && resp.body() != null) {
                        String body = resp.body().string();
                        if (body != null && body.contains("preparedTrust")) {
                            stopTrustPolling();
                            runOnUiThread(() -> {
                                if (currentQrDialog != null && currentQrDialog.isShowing()) {
                                    currentQrDialog.dismiss();
                                }
                                Toast.makeText(MainActivity.this, "Status: preparedTrust. Scan QR code to start recording.", Toast.LENGTH_LONG).show();
                                startQrScanning(hashCode, videoId);
                            });
                        }
                    }
                }
            } catch (Exception ignored) {
            }
        }, 0, 200, TimeUnit.MILLISECONDS);
    }

    private synchronized void stopTrustPolling() {
        if (trustPollingTask != null) {
            trustPollingTask.cancel(true);
            trustPollingTask = null;
        }
        if (trustPollingScheduler != null) {
            trustPollingScheduler.shutdownNow();
            trustPollingScheduler = null;
        }
    }

    private void startQrScanning(String hashCode, String videoId) {
        targetQrHash = hashCode;
        pendingVideoId = videoId;
        isScanningQrForVideo = true;
    }

    @SuppressLint("MissingPermission")
    private void startRecordingLifecycle(final String name) {
        try {
            ContentValues contentValues = new ContentValues();
            contentValues.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
            contentValues.put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4");
            contentValues.put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/CameraX-Video");

            MediaStoreOutputOptions outputOptions = new MediaStoreOutputOptions.Builder(getContentResolver(), MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
                    .setContentValues(contentValues)
                    .build();

            boolean hasAudioPermission = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
            PendingRecording pendingRecording = videoCapture.getOutput()
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
                            
                            frameProccesor.resetHashChain();
                        } else if (videoRecordEvent instanceof VideoRecordEvent.Finalize) {
                            if (!((VideoRecordEvent.Finalize) videoRecordEvent).hasError()) {
                                Uri finalUri = ((VideoRecordEvent.Finalize) videoRecordEvent).getOutputResults().getOutputUri();
                                String msg = "Video capture succeeded: " + finalUri;
                                Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
                                
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

            recording = newRecording;

        } catch (Exception e) {
            Toast.makeText(this, "Error starting video capture: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            if (recording != null) {
                recording.stop();
                recording = null;
            }
        }
    }

    private JSONObject buildVideoHashPayload(String videoId) throws JSONException {
        JSONObject o = new JSONObject();
        o.put("id", UUID.randomUUID().toString());
        o.put("videoId", videoId);
        o.put("imageHashes", new JSONArray());
        
        String deviceId = prefs.getString("deviceId", null);
        if (deviceId == null) {
            deviceId = UUID.randomUUID().toString();
            prefs.edit().putString("deviceId", deviceId).apply();
        }
        o.put("deviceId", deviceId);
        o.put("manufacturer", Build.MANUFACTURER);
        o.put("model", Build.MODEL);
        o.put("osVersion", Build.VERSION.RELEASE);
        o.put("androidId", Settings.Secure.getString(getContentResolver(), Settings.Secure.ANDROID_ID));
        o.put("macAddress", getWifiMacAddress() != null ? getWifiMacAddress() : JSONObject.NULL);
        o.put("simSerialNumber", JSONObject.NULL);
        o.put("carrierName", getCarrierName() != null ? getCarrierName() : JSONObject.NULL);
        o.put("phoneNumber", JSONObject.NULL);
        o.put("bluetoothMac", JSONObject.NULL);
        o.put("deviceName", Build.MODEL);
        o.put("screenResolution", getScreenResolution());
        o.put("ipAddress", getIpAddress());
        o.put("batteryLevel", getBatteryLevel());
        o.put("locale", Locale.getDefault().toString());
        o.put("latitude", 0.0);
        o.put("longitude", 0.0);

        String payloadJson = o.toString();
        String hashValue = sha256(payloadJson);
        o.put("hashValue", hashValue);

        return o;
    }

    private String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder();
            for (byte b : hashBytes) {
                hexString.append(String.format("%02x", b));
            }
            return hexString.toString();
        } catch (NoSuchAlgorithmException e) {
            e.printStackTrace();
            return "";
        }
    }

    private String getScreenResolution() {
        return getResources().getDisplayMetrics().widthPixels + "x" + getResources().getDisplayMetrics().heightPixels;
    }

    private String getIpAddress() {
        try {
            for (NetworkInterface nif : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                for (InetAddress ia : Collections.list(nif.getInetAddresses())) {
                    if (!ia.isLoopbackAddress()) {
                        String sAddr = ia.getHostAddress();
                        if (sAddr.indexOf(':') < 0) {
                            return sAddr;
                        }
                    }
                }
            }
        } catch (Exception ignored) {}
        return "0.0.0.0";
    }

    private String getBatteryLevel() {
        try {
            BatteryManager bm = (BatteryManager) getSystemService(BATTERY_SERVICE);
            if (bm != null) {
                return bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) + "%";
            }
        } catch (Exception ignored) {}
        return "100%";
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
                    @SuppressWarnings("UnsafeOptInUsageError")
                    @OptIn(markerClass = ExperimentalGetImage.class)
                    Image mediaImage = imageProxy.getImage();

                    if (isScanningQrForVideo && mediaImage != null) {
                        InputImage inputImage = InputImage.fromMediaImage(
                                mediaImage,
                                imageProxy.getImageInfo().getRotationDegrees()
                        );
                        barcodeScanner.process(inputImage)
                                .addOnSuccessListener(barcodes -> {
                                    if (!isScanningQrForVideo) return;
                                    for (Barcode barcode : barcodes) {
                                        String rawValue = barcode.getRawValue();
                                        if (rawValue != null && rawValue.equals(targetQrHash)) {
                                            isScanningQrForVideo = false;
                                            final String vidId = pendingVideoId;
                                            runOnUiThread(() -> {
                                                Toast.makeText(MainActivity.this, "QR Code matched! Starting recording...", Toast.LENGTH_SHORT).show();
                                                startRecordingLifecycle(vidId);
                                            });
                                            break;
                                        }
                                    }
                                })
                                .addOnCompleteListener(task -> {
                                    try {
                                        if (recording != null && mediaImage != null) {
                                            processFrame(mediaImage);
                                        }
                                    } finally {
                                        imageProxy.close();
                                    }
                                });
                    } else {
                        try {
                            if (recording != null && mediaImage != null) {
                                processFrame(mediaImage);
                            }
                        } finally {
                            imageProxy.close();
                        }
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
        stopTrustPolling();
        if (barcodeScanner != null) {
            barcodeScanner.close();
        }
        if (service != null) {
            service.shutdown();
        }
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