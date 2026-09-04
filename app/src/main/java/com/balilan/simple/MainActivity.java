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

        btnConnect.setOnClickListener(v -> doConnect());

        // 扫码框：监听回车（或IME actionSend）
        etScan.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEND ||
                    actionId == EditorInfo.IME_ACTION_DONE ||
                    actionId == EditorInfo.IME_ACTION_GO ||
                    (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER
                            && event.getAction() == KeyEvent.ACTION_DOWN)) {
                onScanEntered();
                return true;
            }
            return false;
        });

        // 物理回车兜底（部分PDA扫码枪不走IME action）
        etScan.setOnKeyListener((v, keyCode, event) -> {
            if (event.getAction() == KeyEvent.ACTION_DOWN
                    && keyCode == KeyEvent.KEYCODE_ENTER) {
                onScanEntered();
                return true;
            }
            return false;
        });

        // 进入即聚焦扫码框
        etScan.requestFocus();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        disconnect();
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

        // 先断开旧连接
        disconnect();
        setStatus("连接中...", false);

        new Thread(() -> {
            try {
                Socket s = new Socket();
                s.connect(new InetSocketAddress(ip, port), 3000);
                s.setTcpNoDelay(true);
                synchronized (lock) {
                    socket = s;
                    outputStream = s.getOutputStream();
                }
                mainHandler.post(() -> {
                    setStatus("已连接 " + ip + ":" + port, true);
                    btnConnect.setText(R.string.btn_disconnect);
                    etScan.requestFocus();
                });
            } catch (Exception e) {
                mainHandler.post(() -> {
                    setStatus("错误：" + e.getMessage(), false);
                    btnConnect.setText(R.string.btn_connect);
                });
            }
        }).start();
    }

    private void onScanEntered() {
        String text = etScan.getText().toString();
        if (text.isEmpty()) return;
        etScan.setText("");
        etScan.requestFocus();

        // 没连接就先尝试连一次
        synchronized (lock) {
            if (socket == null || socket.isClosed()) {
                setStatus("未连接，先连接...", false);
                doConnect();
            }
        }

        new Thread(() -> {
            boolean ok = send(text + "\n");
            String now = timeFmt.format(new Date());
            mainHandler.post(() -> {
                if (ok) {
                    tvLog.setText("[" + now + "] " + text + "\n" + tvLog.getText());
                } else {
                    tvLog.setText("[" + now + "] [失败] " + text + "\n" + tvLog.getText());
                }
            });
        }).start();
    }

    private boolean send(String line) {
        synchronized (lock) {
            if (outputStream == null) return false;
            try {
                outputStream.write(line.getBytes(StandardCharsets.UTF_8));
                outputStream.flush();
                return true;
            } catch (Exception e) {
                // 断开重连
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