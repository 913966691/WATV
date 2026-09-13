package com.github.tvbox.osc.dlna;

import android.app.Activity;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;

import com.github.tvbox.osc.R;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.ArrayList;
import java.util.List;

/**
 * DLNA 投屏设备选择对话框
 */
public class DlnaCastDialog {
    private final Activity mActivity;
    private final DlnaManager mManager;
    private AlertDialog mDialog;
    private LinearLayout mDeviceListContainer;
    private ProgressBar mSearchingProgress;
    private TextView mStatusText;
    private Button mStopButton;
    private DlnaDevice mConnectedDevice;
    private OnCastListener mListener;

    public interface OnCastListener {
        void onDeviceSelected(DlnaDevice device);
        void onStopRequested(DlnaDevice device);
        String getPlayUrl();
        String getVideoTitle();
    }

    public DlnaCastDialog(Activity activity, OnCastListener listener) {
        mActivity = activity;
        mListener = listener;
        mManager = new DlnaManager(activity);
    }

    public void show() {
        View view = LayoutInflater.from(mActivity).inflate(R.layout.dialog_dlna_cast, null);
        mDeviceListContainer = view.findViewById(R.id.device_list_container);
        mSearchingProgress = view.findViewById(R.id.searching_progress);
        mStatusText = view.findViewById(R.id.status_text);
        mStopButton = view.findViewById(R.id.btn_stop);

        mStopButton.setOnClickListener(v -> {
            if (mConnectedDevice != null && mListener != null) {
                mListener.onStopRequested(mConnectedDevice);
                mDialog.dismiss();
            }
        });

        mDialog = new MaterialAlertDialogBuilder(mActivity)
                .setTitle("投屏")
                .setView(view)
                .setNegativeButton("取消", (d, w) -> {
                    mManager.stopSearch();
                    d.dismiss();
                })
                .setOnDismissListener(d -> mManager.stopSearch())
                .create();

        mDialog.show();

        startDeviceSearch();
    }

    private void startDeviceSearch() {
        mStatusText.setText("正在搜索设备...");
        mSearchingProgress.setVisibility(View.VISIBLE);
        mDeviceListContainer.removeAllViews();

        mManager.startSearch(device -> {
            if (mDialog != null && mDialog.isShowing()) {
                addDeviceView(device);
            }
        });
    }

    private void addDeviceView(DlnaDevice device) {
        if (!device.isValid()) return;

        mSearchingProgress.setVisibility(View.GONE);
        mStatusText.setText("选择投屏设备");

        TextView deviceView = new TextView(mActivity);
        deviceView.setText(device.friendlyName + (device.manufacturer != null ? " (" + device.manufacturer + ")" : ""));
        deviceView.setTextSize(16);
        deviceView.setPadding(40, 30, 40, 30);
        deviceView.setOnClickListener(v -> onDeviceClicked(device));

        mDeviceListContainer.addView(deviceView);
    }

    private void onDeviceClicked(DlnaDevice device) {
        mConnectedDevice = device;
        mStatusText.setText("正在连接到 " + device.friendlyName + "...");
        mSearchingProgress.setVisibility(View.VISIBLE);
        mStopButton.setVisibility(View.VISIBLE);
        mStopButton.setText("断开连接");

        if (mListener != null) {
            String url = mListener.getPlayUrl();
            String title = mListener.getVideoTitle();
            
            if (url == null || url.isEmpty()) {
                mStatusText.setText("无法获取播放地址");
                mSearchingProgress.setVisibility(View.GONE);
                return;
            }

            mManager.cast(device, url, title, new DlnaManager.CastCallback() {
                @Override
                public void onSuccess() {
                    if (mDialog != null && mDialog.isShowing()) {
                        mStatusText.setText("已连接到 " + device.friendlyName);
                        mSearchingProgress.setVisibility(View.GONE);
                    }
                }

                @Override
                public void onError(String message) {
                    if (mDialog != null && mDialog.isShowing()) {
                        mStatusText.setText("连接失败: " + message);
                        mSearchingProgress.setVisibility(View.GONE);
                    }
                }
            });
        }
    }

    public void dismiss() {
        mManager.stopSearch();
        if (mDialog != null && mDialog.isShowing()) {
            mDialog.dismiss();
        }
    }
}
