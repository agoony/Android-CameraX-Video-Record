package com.example.cameraxvideorecorder;

import android.content.Context;
import android.graphics.Bitmap;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.util.Log;

import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.vision.barcode.BarcodeScanner;
import com.google.mlkit.vision.barcode.BarcodeScanning;
import com.google.mlkit.vision.barcode.common.Barcode;
import com.google.mlkit.vision.common.InputImage;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import org.json.JSONObject;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class VideoVerifier {

    private static final String TAG = "VideoVerifier";
    private static final ExecutorService executor = Executors.newFixedThreadPool(2);

    public interface VerificationCallback {
        void onVerificationComplete(VideoItem item);
    }

    public static class ServerHashInfo {
        private final String lastHash;
        private final long startingFrame;

        public ServerHashInfo(String lastHash, long startingFrame) {
            this.lastHash = lastHash;
            this.startingFrame = startingFrame;
        }

        public String getLastHash() {
            return lastHash;
        }

        public long getStartingFrame() {
            return startingFrame;
        }
    }

    public static void verifyVideo(Context context, String baseUrl, String idToken, VideoItem item, VerificationCallback callback) {
        item.setStatus(VideoItem.VerificationStatus.VERIFYING);
        item.setStatusMessage("Scanning initial QR code...");

        executor.submit(() -> {
            try {
                // Step 1: Scan initial QR code from video frames
                String qrHash = scanInitialQrCode(context, item.getUri());
                if (qrHash == null) {
                    item.setStatus(VideoItem.VerificationStatus.ERROR);
                    item.setStatusMessage("Error: Could not detect initial QR code in video.");
                    callback.onVerificationComplete(item);
                    return;
                }
                item.setQrHash(qrHash);

                // Step 2: Fetch server last hash and starting frame
                item.setStatusMessage("Fetching last registered hash and starting frame from server...");
                ServerHashInfo serverInfo = fetchServerInfo(baseUrl, idToken, qrHash);
                String serverLastHash = serverInfo.getLastHash();
                item.setServerLastHash(serverLastHash);

                // Step 3: Recalculate chained hash starting from backend startingFrame
                item.setStatusMessage("Recalculating chained hashes starting from frame " + serverInfo.getStartingFrame() + "...");
                String calculatedHash = recalculateChainedHash(context, item.getUri(), serverInfo.getStartingFrame());
                item.setCalculatedHash(calculatedHash);

                // Step 4: Compare calculated hash with server hash
                if (serverLastHash != null && calculatedHash != null && calculatedHash.equalsIgnoreCase(serverLastHash)) {
                    item.setStatus(VideoItem.VerificationStatus.VALID);
                    item.setStatusMessage("VALID - Video is authentic and has not been modified.");
                } else {
                    item.setStatus(VideoItem.VerificationStatus.CORRUPTED);
                    item.setStatusMessage("CORRUPTED - Video hash mismatch! Video has been tampered with or corrupted.");
                }

            } catch (Exception e) {
                Log.e(TAG, "Error during video verification", e);
                item.setStatus(VideoItem.VerificationStatus.ERROR);
                item.setStatusMessage("Verification error: " + e.getMessage());
            }

            callback.onVerificationComplete(item);
        });
    }

    public static String scanInitialQrCode(Context context, Uri videoUri) {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        BarcodeScanner scanner = BarcodeScanning.getClient();
        try {
            retriever.setDataSource(context, videoUri);
            // Scan first 5 seconds at 200ms intervals
            for (long timeUs = 0; timeUs <= 5000000; timeUs += 200000) {
                Bitmap frame = retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST);
                if (frame != null) {
                    InputImage image = InputImage.fromBitmap(frame, 0);
                    try {
                        List<Barcode> barcodes = Tasks.await(scanner.process(image));
                        for (Barcode barcode : barcodes) {
                            String rawValue = barcode.getRawValue();
                            if (rawValue != null && !rawValue.isEmpty()) {
                                return rawValue;
                            }
                        }
                    } catch (Exception ignored) {
                    } finally {
                        frame.recycle();
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error scanning QR code from video", e);
        } finally {
            try {
                retriever.release();
            } catch (Exception ignored) {}
            scanner.close();
        }
        return null;
    }

    public static String recalculateChainedHash(Context context, Uri videoUri) {
        return recalculateChainedHash(context, videoUri, 0L);
    }

    public static String recalculateChainedHash(Context context, Uri videoUri, long startingFrame) {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        String currentHash = "hardcoded_initial_hash";
        try {
            retriever.setDataSource(context, videoUri);
            String durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            long durationMs = durationStr != null ? Long.parseLong(durationStr) : 0;

            long frameIntervalUs = 33000; // Sampling video at ~30 FPS (33ms per frame)
            long frameCounter = startingFrame;
            long startTimeUs = startingFrame * frameIntervalUs;

            for (long timeUs = startTimeUs; timeUs < durationMs * 1000; timeUs += frameIntervalUs) {
                frameCounter++;
                if (frameCounter % 13 == 0) {
                    Bitmap frame = retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST);
                    if (frame != null) {
                        ByteBuffer buffer = ByteBuffer.allocate(frame.getByteCount());
                        frame.copyPixelsToBuffer(buffer);
                        byte[] frameData = buffer.array();

                        String frameHash = sha256(frameData);
                        currentHash = sha256(currentHash + frameHash);
                        frame.recycle();
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error recalculating chained hash", e);
        } finally {
            try {
                retriever.release();
            } catch (Exception ignored) {}
        }
        return currentHash;
    }

    public static ServerHashInfo fetchServerInfo(String baseUrl, String idToken, String qrHash) throws Exception {
        OkHttpClient client = new OkHttpClient();
        String primaryUrl = (baseUrl.endsWith("/") ? baseUrl : baseUrl + "/")
                + "api/videohash/imagehash/last?hashValue=" + Uri.encode(qrHash);

        Request.Builder builder = new Request.Builder().url(primaryUrl).get();
        if (idToken != null) {
            builder.addHeader("Authorization", "Bearer " + idToken);
        }

        try (Response resp = client.newCall(builder.build()).execute()) {
            if (resp.isSuccessful() && resp.body() != null) {
                String body = resp.body().string();
                return parseServerHashInfo(body);
            }
        } catch (Exception ignored) {}

        // Fallback endpoint
        String fallbackUrl = (baseUrl.endsWith("/") ? baseUrl : baseUrl + "/")
                + "api/videohash/imageHash/last?hashValue=" + Uri.encode(qrHash);
        Request.Builder fbBuilder = new Request.Builder().url(fallbackUrl).get();
        if (idToken != null) {
            fbBuilder.addHeader("Authorization", "Bearer " + idToken);
        }

        try (Response resp = client.newCall(fbBuilder.build()).execute()) {
            if (resp.isSuccessful() && resp.body() != null) {
                String body = resp.body().string();
                return parseServerHashInfo(body);
            }
        }

        throw new Exception("Could not fetch last hash and starting frame from server for " + qrHash);
    }

    public static String fetchServerLastHash(String baseUrl, String idToken, String qrHash) throws Exception {
        ServerHashInfo info = fetchServerInfo(baseUrl, idToken, qrHash);
        return info.getLastHash();
    }

    private static ServerHashInfo parseServerHashInfo(String body) {
        if (body == null || body.trim().isEmpty()) {
            return new ServerHashInfo(null, 0L);
        }
        String lastHash = null;
        long startingFrame = 0L;
        try {
            JSONObject json = new JSONObject(body);

            if (json.has("lastHash")) lastHash = json.getString("lastHash");
            else if (json.has("imageHash")) lastHash = json.getString("imageHash");
            else if (json.has("hash")) lastHash = json.getString("hash");
            else if (json.has("hashValue")) lastHash = json.getString("hashValue");
            else if (json.has("lastImageHash")) lastHash = json.getString("lastImageHash");
            else lastHash = body.trim().replace("\"", "");

            if (json.has("startingFrame")) startingFrame = json.getLong("startingFrame");
            else if (json.has("startFrame")) startingFrame = json.getLong("startFrame");
            else if (json.has("initialFrame")) startingFrame = json.getLong("initialFrame");
            else if (json.has("start_frame")) startingFrame = json.getLong("start_frame");
            else if (json.has("starting_frame")) startingFrame = json.getLong("starting_frame");
            else if (json.has("firstFrame")) startingFrame = json.getLong("firstFrame");
            else if (json.has("startFrameNumber")) startingFrame = json.getLong("startFrameNumber");
            else if (json.has("startingFrameNumber")) startingFrame = json.getLong("startingFrameNumber");
            else if (json.has("frameNumber")) startingFrame = json.getLong("frameNumber");
            else if (json.has("frame")) startingFrame = json.getLong("frame");
        } catch (Exception ignored) {
            lastHash = body.trim().replace("\"", "");
        }
        return new ServerHashInfo(lastHash, startingFrame);
    }

    private static String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder();
            for (byte b : hashBytes) {
                hexString.append(String.format("%02x", b));
            }
            return hexString.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private static String sha256(byte[] input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = digest.digest(input);
            StringBuilder hexString = new StringBuilder();
            for (byte b : hashBytes) {
                hexString.append(String.format("%02x", b));
            }
            return hexString.toString();
        } catch (Exception e) {
            return "";
        }
    }
}
