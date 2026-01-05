// BluetoothSerialService.java
package org.fbl.esp32onlineswitch;

import android.Manifest;
import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.UUID;

public class BluetoothSerialService {
    private static final String TAG = "BluetoothSerialService";

    // 串口服务UUID
    private static final UUID SERIAL_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");

    // 状态常量
    public static final int STATE_NONE = 0;
    public static final int STATE_CONNECTING = 1;
    public static final int STATE_CONNECTED = 2;
    public static final int STATE_DISCONNECTED = 3;
    public static final int STATE_ERROR = 4;

    private Context context;
    private String macAddress;
    private BluetoothAdapter bluetoothAdapter;
    private BluetoothDevice bluetoothDevice;
    private BluetoothSocket bluetoothSocket;
    private ConnectedThread connectedThread;
    private ConnectionCallback connectionCallback;
    private ConnectionResultCallback connectionResultCallback;

    private int connectionState = STATE_NONE;
    private Handler handler;

    public interface ConnectionCallback {
        void onConnectionStateChanged(int state);
        void onDataReceived(byte[] data);
    }

    public interface ConnectionResultCallback {
        void onConnectionResult(boolean isSuccess);
    }

    public BluetoothSerialService(Context context) {
        this.context = context;
        this.handler = new Handler(Looper.getMainLooper());
        this.bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
    }

    // 设置连接结果回调
    public void setConnectionResultCallback(ConnectionResultCallback callback) {
        this.connectionResultCallback = callback;
    }

    // 设置数据回调
    public void setConnectionCallback(ConnectionCallback callback) {
        this.connectionCallback = callback;
    }

    /**
     * 连接指定MAC地址的设备
     * @param macAddress 设备的MAC地址
     * @return true: 开始连接尝试 | false: 连接失败
     */
    @SuppressLint("MissingPermission")
    public boolean connectToDevice(String macAddress) {
        this.macAddress = macAddress;

        if (bluetoothAdapter == null) {
            Log.e(TAG, "设备不支持蓝牙");
            updateConnectionState(STATE_ERROR);
            return false;
        }

        if (!bluetoothAdapter.isEnabled()) {
            Log.e(TAG, "蓝牙未开启");
            updateConnectionState(STATE_ERROR);
            return false;
        }

        // 如果有正在进行的连接，先断开
        if (isConnected() || connectionState == STATE_CONNECTING) {
            disconnect();
        }

        // 在新线程中执行连接操作，避免阻塞UI
        new Thread(new Runnable() {
            @Override
            public void run() {
                performConnection();
            }
        }).start();

        return true; // 表示连接尝试已开始
    }

    @SuppressLint("MissingPermission")
    private void performConnection() {
        try {
            updateConnectionState(STATE_CONNECTING);

            // 通过MAC地址获取设备
            bluetoothDevice = bluetoothAdapter.getRemoteDevice(macAddress);

            if (bluetoothDevice == null) {
                Log.e(TAG, "找不到指定MAC地址的设备: " + macAddress);
                updateConnectionState(STATE_ERROR);
                return;
            }

            Log.d(TAG, "正在连接设备: " + macAddress);

            // 使用串口服务UUID创建Socket
            bluetoothSocket = bluetoothDevice.createRfcommSocketToServiceRecord(SERIAL_UUID);

            // 取消发现以加快连接速度
            bluetoothAdapter.cancelDiscovery();

            // 设置连接超时
            try {
                // 尝试连接，设置超时时间
                bluetoothSocket.connect();

                // 连接成功
                Log.d(TAG, "蓝牙连接成功: " + macAddress);
                updateConnectionState(STATE_CONNECTED);

                // 启动数据接收线程
                connectedThread = new ConnectedThread(bluetoothSocket);
                connectedThread.start();

                // 回调连接成功
                if (connectionResultCallback != null) {
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            connectionResultCallback.onConnectionResult(true);
                        }
                    });
                }

            } catch (IOException e) {
                Log.e(TAG, "连接超时或失败: " + e.getMessage());
                updateConnectionState(STATE_ERROR);

                // 回调连接失败
                if (connectionResultCallback != null) {
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            connectionResultCallback.onConnectionResult(false);
                        }
                    });
                }
            }

        } catch (Exception e) {
            Log.e(TAG, "连接过程中发生异常: " + e.getMessage(), e);
            cleanup();
            updateConnectionState(STATE_ERROR);

            // 回调连接失败
            if (connectionResultCallback != null) {
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        connectionResultCallback.onConnectionResult(false);
                    }
                });
            }
        }
    }

    /**
     * 同步连接方法（阻塞直到连接完成或超时）
     * @param macAddress 设备的MAC地址
     * @param timeoutMs 超时时间（毫秒）
     * @return true: 连接成功 | false: 连接失败
     */
    public boolean connectToDeviceSync(String macAddress, long timeoutMs) {
        final Object lock = new Object();
        final boolean[] result = {false};
        final boolean[] completed = {false};

        // 设置连接结果回调
        setConnectionResultCallback(new ConnectionResultCallback() {
            @Override
            public void onConnectionResult(boolean isSuccess) {
                synchronized (lock) {
                    result[0] = isSuccess;
                    completed[0] = true;
                    lock.notifyAll();
                }
            }
        });

        // 开始连接
        if (!connectToDevice(macAddress)) {
            return false;
        }

        // 等待连接结果或超时
        synchronized (lock) {
            try {
                lock.wait(timeoutMs);
            } catch (InterruptedException e) {
                Log.e(TAG, "连接等待被中断: " + e.getMessage());
            }
        }

        // 如果超时未完成，断开连接并返回失败
        if (!completed[0]) {
            disconnect();
            return false;
        }

        return result[0];
    }

    /**
     * 异步连接方法（使用回调）
     * @param macAddress 设备的MAC地址
     * @param callback 连接结果回调
     * @return true: 开始连接尝试 | false: 连接失败
     */
    public boolean connectToDeviceAsync(String macAddress, final ConnectionResultCallback callback) {
        // 保存回调
        this.connectionResultCallback = callback;

        // 开始连接
        return connectToDevice(macAddress);
    }

    /**
     * 简单连接方法 - 您要的主要函数
     * @param macAddress 设备的MAC地址
     * @return true: 连接成功 | false: 连接失败
     */
    public boolean connect(final String macAddress) {
        // 使用默认3秒超时
        return connectToDeviceSync(macAddress, 3000);
    }

    public void disconnect() {
        try {
            if (connectedThread != null) {
                connectedThread.cancel();
                connectedThread = null;
            }

            if (bluetoothSocket != null) {
                bluetoothSocket.close();
                bluetoothSocket = null;
            }
        } catch (Exception e) {
            Log.e(TAG, "断开连接时出错", e);
        } finally {
            cleanup();
            updateConnectionState(STATE_DISCONNECTED);
        }
    }

    public boolean write(byte[] data) {
        if (connectedThread != null) {
            return connectedThread.write(data);
        }
        return false;
    }

    public boolean write(String text) {
        return write(text.getBytes());
    }

    public boolean isConnected() {
        return connectionState == STATE_CONNECTED &&
                bluetoothSocket != null &&
                bluetoothSocket.isConnected();
    }

    public String getConnectedDeviceAddress() {
        if (isConnected() && bluetoothDevice != null) {
            return bluetoothDevice.getAddress();
        }
        return null;
    }

    public String getConnectedDeviceName() {
        if (isConnected() && bluetoothDevice != null) {
            return bluetoothDevice.getName();
        }
        return null;
    }

    private void cleanup() {
        bluetoothDevice = null;
    }

    private void updateConnectionState(final int state) {
        connectionState = state;

        if (connectionCallback != null) {
            handler.post(new Runnable() {
                @Override
                public void run() {
                    connectionCallback.onConnectionStateChanged(state);
                }
            });
        }
    }

    // ConnectedThread 类保持不变
    private class ConnectedThread extends Thread {
        private final BluetoothSocket socket;
        private final InputStream inputStream;
        private final OutputStream outputStream;

        public ConnectedThread(BluetoothSocket socket) {
            this.socket = socket;
            InputStream tmpIn = null;
            OutputStream tmpOut = null;

            try {
                tmpIn = socket.getInputStream();
                tmpOut = socket.getOutputStream();
            } catch (IOException e) {
                Log.e(TAG, "创建流失败", e);
            }

            inputStream = tmpIn;
            outputStream = tmpOut;
        }

        @Override
        public void run() {
            byte[] buffer = new byte[1024];
            int bytes;

            while (socket.isConnected()) {
                try {
                    // 读取数据
                    bytes = inputStream.read(buffer);

                    if (bytes > 0) {
                        byte[] data = new byte[bytes];
                        System.arraycopy(buffer, 0, data, 0, bytes);

                        // 回调数据
                        if (connectionCallback != null) {
                            final byte[] finalData = data;
                            handler.post(new Runnable() {
                                @Override
                                public void run() {
                                    connectionCallback.onDataReceived(finalData);
                                }
                            });
                        }
                    }
                } catch (IOException e) {
                    Log.e(TAG, "读取数据时连接断开", e);
                    break;
                }
            }

            disconnect();
        }

        public boolean write(byte[] data) {
            try {
                outputStream.write(data);
                outputStream.flush();
                return true;
            } catch (IOException e) {
                Log.e(TAG, "写入数据失败", e);
                return false;
            }
        }

        public void cancel() {
            try {
                socket.close();
            } catch (IOException e) {
                Log.e(TAG, "关闭Socket失败", e);
            }
        }
    }
}