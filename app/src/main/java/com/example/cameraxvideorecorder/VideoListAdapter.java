package com.example.cameraxvideorecorder;

import android.graphics.Color;
import android.text.format.Formatter;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class VideoListAdapter extends RecyclerView.Adapter<VideoListAdapter.VideoViewHolder> {

    public interface OnVerifyClickListener {
        void onVerifyClick(VideoItem item, int position);
    }

    private final List<VideoItem> videoList;
    private final OnVerifyClickListener verifyClickListener;

    public VideoListAdapter(List<VideoItem> videoList, OnVerifyClickListener verifyClickListener) {
        this.videoList = videoList;
        this.verifyClickListener = verifyClickListener;
    }

    @NonNull
    @Override
    public VideoViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_video, parent, false);
        return new VideoViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull VideoViewHolder holder, int position) {
        VideoItem item = videoList.get(position);

        holder.txtTitle.setText(item.getName());

        String sizeStr = Formatter.formatFileSize(holder.itemView.getContext(), item.getSize());
        String dateStr = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(new Date(item.getDateAdded() * 1000));
        long sec = item.getDuration() / 1000;
        String durationStr = String.format(Locale.getDefault(), "%d:%02d", sec / 60, sec % 60);

        holder.txtInfo.setText(sizeStr + " • " + durationStr + " • " + dateStr);

        if (item.getThumbnail() != null) {
            holder.imgThumbnail.setImageBitmap(item.getThumbnail());
        } else {
            holder.imgThumbnail.setImageResource(android.R.drawable.ic_menu_slideshow);
        }

        switch (item.getStatus()) {
            case UNVERIFIED:
                holder.txtStatus.setText("Not Verified");
                holder.txtStatus.setTextColor(Color.parseColor("#757575"));
                holder.btnVerify.setEnabled(true);
                holder.txtHashDetails.setVisibility(View.GONE);
                break;
            case VERIFYING:
                holder.txtStatus.setText("Verifying Integrity...");
                holder.txtStatus.setTextColor(Color.parseColor("#1976D2"));
                holder.btnVerify.setEnabled(false);
                holder.txtHashDetails.setVisibility(View.VISIBLE);
                holder.txtHashDetails.setText(item.getStatusMessage() != null ? item.getStatusMessage() : "Processing...");
                break;
            case VALID:
                holder.txtStatus.setText("✓ VALID - Video Authentic");
                holder.txtStatus.setTextColor(Color.parseColor("#2E7D32"));
                holder.btnVerify.setEnabled(true);
                holder.txtHashDetails.setVisibility(View.VISIBLE);
                holder.txtHashDetails.setText("QR Hash: " + item.getQrHash() + "\nCalculated: " + item.getCalculatedHash() + "\nServer Hash: " + item.getServerLastHash());
                break;
            case CORRUPTED:
                holder.txtStatus.setText("⚠ CORRUPTED - Hash Mismatch!");
                holder.txtStatus.setTextColor(Color.parseColor("#C62828"));
                holder.btnVerify.setEnabled(true);
                holder.txtHashDetails.setVisibility(View.VISIBLE);
                holder.txtHashDetails.setText("QR Hash: " + item.getQrHash() + "\nCalculated: " + item.getCalculatedHash() + "\nServer Hash: " + item.getServerLastHash());
                break;
            case ERROR:
                holder.txtStatus.setText("Error Verifying");
                holder.txtStatus.setTextColor(Color.parseColor("#D32F2F"));
                holder.btnVerify.setEnabled(true);
                holder.txtHashDetails.setVisibility(View.VISIBLE);
                holder.txtHashDetails.setText(item.getStatusMessage());
                break;
        }

        holder.btnVerify.setOnClickListener(v -> {
            if (verifyClickListener != null) {
                verifyClickListener.onVerifyClick(item, holder.getAdapterPosition());
            }
        });
    }

    @Override
    public int getItemCount() {
        return videoList.size();
    }

    public static class VideoViewHolder extends RecyclerView.ViewHolder {
        ImageView imgThumbnail;
        TextView txtTitle, txtInfo, txtStatus, txtHashDetails;
        Button btnVerify;

        public VideoViewHolder(@NonNull View itemView) {
            super(itemView);
            imgThumbnail = itemView.findViewById(R.id.imgThumbnail);
            txtTitle = itemView.findViewById(R.id.txtTitle);
            txtInfo = itemView.findViewById(R.id.txtInfo);
            txtStatus = itemView.findViewById(R.id.txtStatus);
            txtHashDetails = itemView.findViewById(R.id.txtHashDetails);
            btnVerify = itemView.findViewById(R.id.btnVerify);
        }
    }
}
