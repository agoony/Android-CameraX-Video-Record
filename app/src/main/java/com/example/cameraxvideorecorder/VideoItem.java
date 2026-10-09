package com.example.cameraxvideorecorder;

import android.graphics.Bitmap;
import android.net.Uri;

public class VideoItem {
    public enum VerificationStatus {
        UNVERIFIED,
        VERIFYING,
        VALID,
        CORRUPTED,
        ERROR
    }

    private final String name;
    private final Uri uri;
    private final long size;
    private final long dateAdded;
    private final long duration;
    private Bitmap thumbnail;

    private VerificationStatus status = VerificationStatus.UNVERIFIED;
    private String qrHash;
    private String calculatedHash;
    private String serverLastHash;
    private String statusMessage;

    public VideoItem(String name, Uri uri, long size, long dateAdded, long duration, Bitmap thumbnail) {
        this.name = name;
        this.uri = uri;
        this.size = size;
        this.dateAdded = dateAdded;
        this.duration = duration;
        this.thumbnail = thumbnail;
    }

    public String getName() { return name; }
    public Uri getUri() { return uri; }
    public long getSize() { return size; }
    public long getDateAdded() { return dateAdded; }
    public long getDuration() { return duration; }
    public Bitmap getThumbnail() { return thumbnail; }
    public void setThumbnail(Bitmap thumbnail) { this.thumbnail = thumbnail; }

    public VerificationStatus getStatus() { return status; }
    public void setStatus(VerificationStatus status) { this.status = status; }

    public String getQrHash() { return qrHash; }
    public void setQrHash(String qrHash) { this.qrHash = qrHash; }

    public String getCalculatedHash() { return calculatedHash; }
    public void setCalculatedHash(String calculatedHash) { this.calculatedHash = calculatedHash; }

    public String getServerLastHash() { return serverLastHash; }
    public void setServerLastHash(String serverLastHash) { this.serverLastHash = serverLastHash; }

    public String getStatusMessage() { return statusMessage; }
    public void setStatusMessage(String statusMessage) { this.statusMessage = statusMessage; }
}
