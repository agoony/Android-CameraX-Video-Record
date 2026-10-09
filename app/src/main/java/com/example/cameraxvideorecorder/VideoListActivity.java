package com.example.cameraxvideorecorder;

import android.content.ContentUris;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.util.Size;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

public class VideoListActivity extends AppCompatActivity {

    private RecyclerView recyclerView;
    private TextView txtEmpty;
    private Button btnRefresh;
    private VideoListAdapter adapter;
    private final List<VideoItem> videoList = new ArrayList<>();

    private String baseUrl;
    private String idToken;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_video_list);

        recyclerView = findViewById(R.id.recyclerViewVideos);
        txtEmpty = findViewById(R.id.txtEmpty);
        btnRefresh = findViewById(R.id.btnRefresh);

        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        adapter = new VideoListAdapter(videoList, (item, position) -> verifyVideoItem(item, position));
        recyclerView.setAdapter(adapter);

        baseUrl = getString(R.string.backend_base_url);
        SharedPreferences prefs = getSharedPreferences("auth", MODE_PRIVATE);
        idToken = prefs.getString("idToken", null);

        btnRefresh.setOnClickListener(v -> loadVideos());

        loadVideos();
    }

    private void loadVideos() {
        videoList.clear();

        Uri collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI;
        String[] projection = new String[]{
                MediaStore.Video.Media._ID,
                MediaStore.Video.Media.DISPLAY_NAME,
                MediaStore.Video.Media.SIZE,
                MediaStore.Video.Media.DATE_ADDED,
                MediaStore.Video.Media.DURATION,
                MediaStore.Video.Media.RELATIVE_PATH
        };

        String selection = MediaStore.Video.Media.RELATIVE_PATH + " LIKE ?";
        String[] selectionArgs = new String[]{"%CameraX-Video%"};
        String sortOrder = MediaStore.Video.Media.DATE_ADDED + " DESC";

        try (Cursor cursor = getContentResolver().query(collection, projection, selection, selectionArgs, sortOrder)) {
            if (cursor != null) {
                int idColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID);
                int nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME);
                int sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE);
                int dateColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED);
                int durationColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION);

                while (cursor.moveToNext()) {
                    long id = cursor.getLong(idColumn);
                    String name = cursor.getString(nameColumn);
                    long size = cursor.getLong(sizeColumn);
                    long dateAdded = cursor.getLong(dateColumn);
                    long duration = cursor.getLong(durationColumn);

                    Uri contentUri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id);

                    Bitmap thumbnail = null;
                    try {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            thumbnail = getContentResolver().loadThumbnail(contentUri, new Size(160, 160), null);
                        } else {
                            thumbnail = MediaStore.Video.Thumbnails.getThumbnail(
                                    getContentResolver(), id, MediaStore.Video.Thumbnails.MINI_KIND, null);
                        }
                    } catch (Exception ignored) {}

                    VideoItem item = new VideoItem(name, contentUri, size, dateAdded, duration, thumbnail);
                    videoList.add(item);
                }
            }
        } catch (Exception e) {
            Toast.makeText(this, "Error loading videos: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }

        // Fallback: If no videos matched "RELATIVE_PATH LIKE %CameraX-Video%", query all videos
        if (videoList.isEmpty()) {
            try (Cursor cursor = getContentResolver().query(collection, projection, null, null, sortOrder)) {
                if (cursor != null) {
                    int idColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID);
                    int nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME);
                    int sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE);
                    int dateColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED);
                    int durationColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION);

                    while (cursor.moveToNext()) {
                        long id = cursor.getLong(idColumn);
                        String name = cursor.getString(nameColumn);
                        long size = cursor.getLong(sizeColumn);
                        long dateAdded = cursor.getLong(dateColumn);
                        long duration = cursor.getLong(durationColumn);

                        Uri contentUri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id);

                        Bitmap thumbnail = null;
                        try {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                thumbnail = getContentResolver().loadThumbnail(contentUri, new Size(160, 160), null);
                            }
                        } catch (Exception ignored) {}

                        VideoItem item = new VideoItem(name, contentUri, size, dateAdded, duration, thumbnail);
                        videoList.add(item);
                    }
                }
            } catch (Exception ignored) {}
        }

        adapter.notifyDataSetChanged();
        if (videoList.isEmpty()) {
            txtEmpty.setVisibility(View.VISIBLE);
            recyclerView.setVisibility(View.GONE);
        } else {
            txtEmpty.setVisibility(View.GONE);
            recyclerView.setVisibility(View.VISIBLE);
        }
    }

    private void verifyVideoItem(VideoItem item, int position) {
        adapter.notifyItemChanged(position);
        VideoVerifier.verifyVideo(this, baseUrl, idToken, item, updatedItem -> {
            runOnUiThread(() -> adapter.notifyItemChanged(position));
        });
    }
}
