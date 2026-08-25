package com.xianyu.vediodownload;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;

import com.google.android.material.button.MaterialButton;
import com.xianyu.vediodownload.util.CookieManager;

/**
 * 设置 Tab Fragment
 */
public class SettingsFragment extends Fragment {

    private CookieManager cookieManager;

    private static SettingsFragment instance;

    public static SettingsFragment getInstance() {
        if (instance == null) instance = new SettingsFragment();
        return instance;
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        cookieManager = new CookieManager(requireContext());
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_settings, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        MaterialButton btnCookie = view.findViewById(R.id.btnCookie);
        MaterialButton btnAutoLogin = view.findViewById(R.id.btnAutoLogin);
        TextView tvVersion = view.findViewById(R.id.tvVersion);

        btnCookie.setOnClickListener(v -> showCookieDialog());
        btnAutoLogin.setOnClickListener(v -> {
            Intent i = new Intent(requireContext(), DouyinLoginActivity.class);
            startActivity(i);
        });

        try {
            android.content.pm.PackageInfo pInfo = requireContext().getPackageManager()
                    .getPackageInfo(requireContext().getPackageName(), 0);
            tvVersion.setText(getString(R.string.app_name) + " v" + pInfo.versionName);
        } catch (Exception e) {
            tvVersion.setText(getString(R.string.app_name));
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        updateCookieButtonText();
    }

    private void updateCookieButtonText() {
        // btnCookie is re-bound in onViewCreated, no persistent ref needed here
    }

    private void showCookieDialog() {
        View view = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_cookie, null);
        EditText etCookie = view.findViewById(R.id.etCookie);
        etCookie.setText(cookieManager.getCookie());
        etCookie.setSelection(etCookie.getText().length());

        new AlertDialog.Builder(requireContext())
                .setTitle(R.string.cookie_dialog_title)
                .setView(view)
                .setPositiveButton(R.string.cookie_dialog_save, (d, w) -> {
                    String cookie = etCookie.getText().toString().trim();
                    cookieManager.setCookie(cookie);
                    Toast.makeText(requireContext(), R.string.cookie_status_set, Toast.LENGTH_SHORT).show();
                })
                .setNeutralButton(R.string.cookie_dialog_clear, (d, w) -> {
                    cookieManager.clearCookie();
                    Toast.makeText(requireContext(), R.string.cookie_status_empty, Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton(R.string.cookie_dialog_cancel, null)
                .show();
    }
}
