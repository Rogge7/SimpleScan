package com.balilan.simple;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.method.ScrollingMovementMethod;
import android.view.KeyEvent;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 极简扫码枪客户端
 * - 输入框接收扫码枪键盘输入（扫码枪会自动追加回车）
 * - 回车即把当前文本通过 Socket 发到电脑端，末尾加 \n
 * - 长连接保持，断开自动重连下次发送
 * - 配置（IP+端口）持久化到 SharedPreferences
 * - 焦点策略：仅在扫码处理完成后聚焦扫码框，不干扰用户编辑 IP/端口
 */
public class MainActivity extends AppCompatActivity {

    private static final String PREF = "simple_scan";
    private static final String KEY_IP = "server_ip";
    private static final String KEY_PORT = "server_port";

    private EditText etServerIp, etServerPort, etScan;
    private Button btnConnect;
    private TextView tvStatus, tvLog;

    private Socket socket;
    private OutputStream outputStream;
    private final Object lock = new Object();

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final SimpleDateFormat timeFmt = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());

    // 防重入：一次扫码只处理一次
    private volatile boolean isProcessing = false;
    // 日志最多保留 50 行（防止无限增长）
    private static final int LOG_MAX_LINES = 50;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        etServerIp = findViewById(R.id.etServerIp);
        etServerPort = findViewById(R.id.etServerPort);
        etScan = findViewById(R.id.etScan);
        btnConnect = findViewById(R.id.btnConnect);
        tvStatus = findViewById(R.id.tvStatus);
        tvLog = findViewById(R.id.tvLog);
        tvLog.setMovementMethod(new ScrollingMovementMethod());

        // 载入上次保存的 IP/端口
        SharedPreferences sp = getSharedPreferences(PREF, Context.MODE_PRIVATE);
        etServerIp.setText(sp.getString(KEY_IP, "192.168.1.100"));
        etServerPort.setText(String.valueOf(sp.getInt(KEY_PORT, 9800)));

        btnConnect.setOnClickListener(v -> onConnectButton());

        // 扫码框：物理回车键按下触发（消费事件防止失焦）
        etScan.setOnKeyListener((v, keyCode, event) -> {
            if (event.getAction() == KeyEvent.ACTION_DOWN
                    && keyCode == KeyEvent.KEYCODE_ENTER) {
                onScanEntered();
                return true; // 消费, 防止冒泡导致焦点丢失
            }
            return false;
        });

        // 兼容 IME action 回车（部分虚拟键盘/PDA）
        etScan.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE
                    || actionId == EditorInfo.IME_ACTION_GO
                    || actionId == EditorInfo.IME_ACTION_SEND
                    || actionId == EditorInfo.IME_ACTION_NEXT
                    || (event != null && event.getAction() == KeyEvent.ACTION_DOWN
                            && event.getKeyCode() == KeyEvent.KEYCODE_ENTER)) {
                onScanEntered();
                return true;
            }
            return false;
        });

        // 首次进入聚焦扫码框（仅这一次，不抢用户后续编辑焦点）
        etScan.requestFocus();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        disconnect();
    }

    /**
     * 连接按钮：根据当前连接状态决定是连接还是断开
     */
    private void onConnectButton() {
        synchronized (lock) {
            // 已连接 → 点击即断开
            if (socket != null && !socket.isClosed()) {
                disconnect();
                return;
            }
        }
        // 未连接 → 发起连接
        doConnect();
    }

    private void doConnect() {
        String ip = etServerIp.getText().toString().trim();
        String portStr = etServerPort.getText().toString().trim();
        if (ip.isEmpty() || portStr.isEmpty()) {
            toast("请填写 IP 和 端口");
            return;
        }
        int port;
        try {
            port = Integer.parseInt(portStr);
        } catch (NumberFormatException e) {
            toast("端口必须是数字");
            return;
        }

        // 保存
        SharedPreferences sp = getSharedPreferences(PREF, Context.MODE_PRIVATE);
        sp.edit().putString(KEY_IP, ip).putInt(KEY_PORT, port).apply();

        // 先清掉旧连接状态
        synchronized (lock) {
            if (socket != null) {
                try { socket.close(); } catch (Exception ignored) {}
                socket = null;
                outputStream = null;
            }
        }
        setStatus("连接中...", false);
        btnConnect.setEnabled(false);  // 防止点击期间重复点

        final String finalIp = ip;
        final int finalPort = port;
        new Thread(() -> {
            try {
                Socket s = new Socket();
                s.connect(new InetSocketAddress(finalIp, finalPort), 3000);
                s.setTcpNoDelay(true);
                synchronized (lock) {
                    socket = s;
                    outputStream = s.getOutputStream();
                }
                mainHandler.post(() -> {
                    setStatus("已连接 " + finalIp + ":" + finalPort, true);
                    btnConnect.setText(R.string.btn_disconnect);
                    btnConnect.setEnabled(true);
                    // 连接成功后聚焦扫码框（合理：连接好就准备扫码）
                    etScan.requestFocus();
                });
            } catch (Exception e) {
                mainHandler.post(() -> {
                    setStatus("错误：" + e.getMessage(), false);
                    btnConnect.setText(R.string.btn_connect);
                    btnConnect.setEnabled(true);
                });
            }
        }).start();
    }

    private void onScanEntered() {
        if (isProcessing) return;
        isProcessing = true;

        String text = etScan.getText().toString();
        if (text.isEmpty()) {
            isProcessing = false;
            return;
        }

        // 先聚焦再清空，避免清空时焦点飘走
        etScan.requestFocus();
        etScan.setText("");

        // 没连接就先尝试连一次
        synchronized (lock) {
            if (socket == null || socket.isClosed()) {
                setStatus("未连接，先连接...", false);
                doConnect();
            }
        }

        final String code = text;
        new Thread(() -> {
            boolean ok = send(code + "\n");
            String now = timeFmt.format(new Date());
            mainHandler.post(() -> {
                appendLog(code, now, ok);
                // 扫码处理完聚焦扫码框（用户还在连续扫码）
                etScan.requestFocus();
                isProcessing = false;
            });
        }).start();
    }

    /**
     * 追加日志：新行插在最前（最新在顶），超过 LOG_MAX_LINES 自动截断旧的
     */
    private void appendLog(String code, String time, boolean ok) {
        String line = (ok ? "" : "[失败] ") + "[" + time + "] " + code;
        String oldLog = tvLog.getText().toString();
        String newVal;
        if ("(无)".equals(oldLog)) {
            newVal = line;
        } else {
            newVal = line + "\n" + oldLog;
        }
        // 截断：只保留最新的 LOG_MAX_LINES 行
        String[] lines = newVal.split("\n");
        if (lines.length > LOG_MAX_LINES) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < LOG_MAX_LINES; i++) {
                if (i > 0) sb.append("\n");
                sb.append(lines[i]);
            }
            newVal = sb.toString();
        }
        tvLog.setText(newVal);
    }

    private boolean send(String line) {
        synchronized (lock) {
            if (outputStream == null) return false;
            try {
                outputStream.write(line.getBytes(StandardCharsets.UTF_8));
                outputStream.flush();
                return true;
            } catch (Exception e) {
                disconnect();
                return false;
            }
        }
    }

    private void disconnect() {
        synchronized (lock) {
            if (outputStream != null) {
                try { outputStream.close(); } catch (Exception ignored) {}
                outputStream = null;
            }
            if (socket != null) {
                try { socket.close(); } catch (Exception ignored) {}
                socket = null;
            }
        }
        runOnUiThread(() -> {
            setStatus("未连接", false);
            btnConnect.setText(R.string.btn_connect);
        });
    }

    private void setStatus(String text, boolean connected) {
        tvStatus.setText(text);
        tvStatus.setTextColor(getColor(connected
                ? R.color.status_connected
                : R.color.status_disconnected));
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }
}