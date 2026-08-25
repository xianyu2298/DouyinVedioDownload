package com.xianyu.vediodownload;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.google.android.material.textfield.TextInputEditText;
import com.xianyu.vediodownload.douyin.AwemeProfile;
import com.xianyu.vediodownload.douyin.DouyinDownloader;
import com.xianyu.vediodownload.douyin.MediaType;
import com.xianyu.vediodownload.douyin.exception.AwemeDetailFetchException;
import com.xianyu.vediodownload.douyin.exception.DouyinDownloaderException;
import com.xianyu.vediodownload.douyin.exception.FileDownloadException;
import com.xianyu.vediodownload.douyin.exception.WorkListFetchException;
import com.xianyu.vediodownload.util.CookieManager;
import com.xianyu.vediodownload.util.DownloaderBridge;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 视频下载 Tab Fragment
 */
public class VideoFragment extends Fragment {

    private static final String TAG = "VideoFragment";

    private final ActivityResultLauncher<String[]> permissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(), result -> {
                boolean allGranted = true;
                for (Boolean granted : result.values()) {
                    if (granted == null || !granted) { allGranted = false; break; }
                }
                if (!allGranted) {
                    Toast.makeText(requireContext(), getString(R.string.error_storage_permission), Toast.LENGTH_LONG).show();
                }
            });

    private TextInputEditText etUrl;
    private MaterialButton btnParse;
    private MaterialCardView cardError;
    private android.widget.TextView tvError;
    private LinearLayout layoutLoading;
    private android.widget.TextView tvLoadingStatus;
    private MaterialCardView cardInfo;
    private android.widget.TextView tvType;
    private android.widget.TextView tvAuthor;
    private android.widget.TextView tvDesc;
    private MaterialButton btnDownloadVideo;
    private MaterialButton btnDownloadAudio;
    private LinearLayout layoutProgress;
    private LinearProgressIndicator progressDownload;
    private android.widget.TextView tvProgress;

    private CookieManager cookieManager;
    private DownloaderBridge downloaderBridge;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService parseExecutor = Executors.newSingleThreadExecutor();
    private AwemeProfile currentProfile;

    private static VideoFragment instance;

    public static VideoFragment getInstance() {
        if (instance == null) instance = new VideoFragment();
        return instance;
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        cookieManager = new CookieManager(requireContext());
        downloaderBridge = new DownloaderBridge(requireContext());
        requestStoragePermissionIfNeeded();
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_video, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        bindViews(view);
        btnParse.setOnClickListener(v -> {
            String url = etUrl.getText().toString().trim();
            if (TextUtils.isEmpty(url)) {
                showError(getString(R.string.error_empty_url));
                return;
            }
            parseShareLink(url);
        });
        btnDownloadVideo.setOnClickListener(v -> triggerDownload(0));
        btnDownloadAudio.setOnClickListener(v -> triggerDownload(0));
    }

    private void bindViews(View view) {
        etUrl = view.findViewById(R.id.etUrl);
        btnParse = view.findViewById(R.id.btnParse);
        cardError = view.findViewById(R.id.cardError);
        tvError = view.findViewById(R.id.tvError);
        layoutLoading = view.findViewById(R.id.layoutLoading);
        tvLoadingStatus = view.findViewById(R.id.tvLoadingStatus);
        cardInfo = view.findViewById(R.id.cardInfo);
        tvType = view.findViewById(R.id.tvType);
        tvAuthor = view.findViewById(R.id.tvAuthor);
        tvDesc = view.findViewById(R.id.tvDesc);
        btnDownloadVideo = view.findViewById(R.id.btnDownloadVideo);
        btnDownloadAudio = view.findViewById(R.id.btnDownloadAudio);
        layoutProgress = view.findViewById(R.id.layoutProgress);
        progressDownload = view.findViewById(R.id.progressDownload);
        tvProgress = view.findViewById(R.id.tvProgress);
    }

    private void parseShareLink(String shareLink) {
        showLoading(getString(R.string.status_parsing));
        hideError();
        hideInfo();
        final String cookie = cookieManager.getCookie();
        parseExecutor.execute(() -> {
            DouyinDownloader downloader = new DouyinDownloader(cookie);
            try {
                AwemeProfile profile = downloader.collectWorkInfo(shareLink);
                mainHandler.post(() -> onParseSuccess(profile));
            } catch (AwemeDetailFetchException e) {
                mainHandler.post(() -> onParseError(getString(R.string.error_private_video) + "\n" + safeMsg(e)));
            } catch (Exception e) {
                mainHandler.post(() -> onParseError(getString(R.string.error_parse_failed) + "\n" + safeMsg(e)));
            } catch (Throwable t) {
                mainHandler.post(() -> onParseError(getString(R.string.error_network) + "\n" + safeMsg(t)));
            }
        });
    }

    private String safeMsg(Throwable t) {
        String m = t.getMessage();
        return (m == null || m.isEmpty()) ? t.getClass().getSimpleName() : (m.length() > 200 ? m.substring(0, 200) + "..." : m);
    }

    private void onParseSuccess(AwemeProfile profile) {
        hideLoading();
        if (profile == null || profile.downloadUrls().isEmpty()) {
            showError(getString(R.string.error_parse_failed));
            return;
        }
        if (profile.mediaType() != MediaType.VIDEO) {
            showError(getString(R.string.error_no_video) + "\n该链接不是视频类型，请在「图片」或「Live」Tab 中打开");
            return;
        }
        currentProfile = profile;
        showInfo(profile);
    }

    private void onParseError(String msg) {
        hideLoading();
        showError(msg);
    }

    private void showInfo(AwemeProfile profile) {
        cardInfo.setVisibility(View.VISIBLE);
        tvAuthor.setText(getString(R.string.label_author,
                profile.authorNickname().isEmpty() ? "(匿名)" : profile.authorNickname()));
        tvDesc.setText(profile.desc().isEmpty() ? "(无描述)" : profile.desc());
        tvType.setText(R.string.label_type_video);
        btnDownloadAudio.setEnabled(false);
        btnDownloadAudio.setText(R.string.btn_download_audio + " (暂不可用)");
    }

    private void hideInfo() {
        cardInfo.setVisibility(View.GONE);
    }

    private void triggerDownload(int urlIndex) {
        if (currentProfile == null) return;
        showProgress();
        String cookie = cookieManager.getCookie();
        downloaderBridge.download(currentProfile, urlIndex, cookie,
                new DownloaderBridge.ProgressListener() {
                    @Override public void onProgress(int percent, long downloaded, long total) {
                        mainHandler.post(() -> {
                            progressDownload.setProgressCompat(percent, true);
                            tvProgress.setText("下载进度: " + percent + "%");
                        });
                    }
                },
                new DownloaderBridge.CompleteListener() {
                    @Override public void onSuccess(String savedPath) {
                        mainHandler.post(() -> {
                            hideProgress();
                            Toast.makeText(requireContext(), getString(R.string.status_download_success, savedPath), Toast.LENGTH_LONG).show();
                        });
                    }
                    @Override public void onError(Throwable error) {
                        mainHandler.post(() -> {
                            hideProgress();
                            String msg = error.getMessage();
                            Toast.makeText(requireContext(), getString(R.string.status_download_failed, msg == null ? error.getClass().getSimpleName() : msg), Toast.LENGTH_LONG).show();
                        });
                    }
                });
    }

    private void showProgress() {
        layoutProgress.setVisibility(View.VISIBLE);
        progressDownload.setProgressCompat(0, true);
        tvProgress.setText("下载进度: 0%");
    }

    private void hideProgress() {
        layoutProgress.setVisibility(View.GONE);
    }

    private void showLoading(String message) {
        layoutLoading.setVisibility(View.VISIBLE);
        tvLoadingStatus.setText(message);
        btnParse.setEnabled(false);
    }

    private void hideLoading() {
        layoutLoading.setVisibility(View.GONE);
        btnParse.setEnabled(true);
    }

    private void showError(String message) {
        cardError.setVisibility(View.VISIBLE);
        tvError.setText(message);
    }

    private void hideError() {
        cardError.setVisibility(View.GONE);
    }

    private void requestStoragePermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.READ_MEDIA_VIDEO) != PackageManager.PERMISSION_GRANTED) {
                permissionLauncher.launch(new String[]{Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_AUDIO});
            }
        } else if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                permissionLauncher.launch(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE, Manifest.permission.READ_EXTERNAL_STORAGE});
            }
        }
    }
}
