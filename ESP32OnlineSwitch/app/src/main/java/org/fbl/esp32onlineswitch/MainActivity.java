// MainActivity.java
package org.fbl.esp32onlineswitch;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity implements BluetoothSerialService.ConnectionCallback{

    private static final int REQUEST_BLUETOOTH_PERMISSIONS = 100;
    private BluetoothSerialService bluetoothService;

    private TextView MainActivityConnectStatu;
    private Button MainActivityButtonOpenLight;
    private Button MainActivityButtonCloseLight;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        MainActivityConnectStatu = findViewById(R.id.main_activity_connect_status);
        MainActivityButtonOpenLight = findViewById(R.id.main_activity_button_open_light);
        MainActivityButtonCloseLight = findViewById(R.id.main_activity_button_close_light);

        ButtonOnClickTaskCreate();

        // 检查并请求权限
        if (!checkBluetoothPermissions()) {
            requestBluetoothPermissions();
        }

        // 初始化蓝牙服务
        bluetoothService = new BluetoothSerialService(this);
        bluetoothService.setConnectionCallback(this);

        // 测试连接函数
        testConnectionFunctions();
    }

    private boolean checkBluetoothPermissions() {
        List<String> permissionsNeeded = new ArrayList<>();

        // 基础蓝牙权限
        if (ContextCompat.checkSelfPermission(this,
                Manifest.permission.BLUETOOTH) != PackageManager.PERMISSION_GRANTED) {
            permissionsNeeded.add(Manifest.permission.BLUETOOTH);
        }

        if (ContextCompat.checkSelfPermission(this,
                Manifest.permission.BLUETOOTH_ADMIN) != PackageManager.PERMISSION_GRANTED) {
            permissionsNeeded.add(Manifest.permission.BLUETOOTH_ADMIN);
        }

        // Android 12+ 需要新的权限
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this,
                    Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                permissionsNeeded.add(Manifest.permission.BLUETOOTH_CONNECT);
            }

            if (ContextCompat.checkSelfPermission(this,
                    Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                permissionsNeeded.add(Manifest.permission.BLUETOOTH_SCAN);
            }
        }
        // Android 6.0 到 Android 11 需要位置权限
        else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (ContextCompat.checkSelfPermission(this,
                    Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                permissionsNeeded.add(Manifest.permission.ACCESS_FINE_LOCATION);
            }
        }

        return permissionsNeeded.isEmpty();
    }

    private void requestBluetoothPermissions() {
        List<String> permissionsToRequest = new ArrayList<>();

        // 基础权限
        if (ContextCompat.checkSelfPermission(this,
                Manifest.permission.BLUETOOTH) != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(Manifest.permission.BLUETOOTH);
        }

        if (ContextCompat.checkSelfPermission(this,
                Manifest.permission.BLUETOOTH_ADMIN) != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(Manifest.permission.BLUETOOTH_ADMIN);
        }

        // 根据Android版本请求不同权限
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this,
                    Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.BLUETOOTH_CONNECT);
            }

            if (ContextCompat.checkSelfPermission(this,
                    Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.BLUETOOTH_SCAN);
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (ContextCompat.checkSelfPermission(this,
                    Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.ACCESS_FINE_LOCATION);
            }
        }

        if (!permissionsToRequest.isEmpty()) {
            ActivityCompat.requestPermissions(this,
                    permissionsToRequest.toArray(new String[0]),
                    REQUEST_BLUETOOTH_PERMISSIONS);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           String[] permissions,
                                           int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode == REQUEST_BLUETOOTH_PERMISSIONS) {
            boolean allGranted = true;
            for (int result : grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) {
                    allGranted = false;
                    break;
                }
            }

            if (allGranted) {
                Toast.makeText(this, "权限已授予", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "需要权限才能使用蓝牙功能", Toast.LENGTH_LONG).show();
            }
        }
    }

    /**
     * 测试连接功能的示例方法
     */
    private void testConnectionFunctions() {
        // 示例MAC地址
        String testMac = "78:42:1C:18:E8:0A";

        // 1. 使用简单的connect函数
        boolean isConnected = connectToDeviceSimple(testMac);
        Log.d("BluetoothTest", "简单连接结果: " + isConnected);

        // 2. 使用带超时的同步连接
        boolean isConnectedSync = connectToDeviceWithTimeout(testMac, 5000);
        Log.d("BluetoothTest", "同步连接结果: " + isConnectedSync);

        // 3. 使用异步连接
        connectToDeviceAsync(testMac, new BluetoothSerialService.ConnectionResultCallback() {
            @Override
            public void onConnectionResult(boolean isSuccess) {
                Log.d("BluetoothTest", "异步连接结果: " + isSuccess);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        Toast.makeText(MainActivity.this,
                                "连接" + (isSuccess ? "成功" : "失败"),
                                Toast.LENGTH_SHORT).show();
                    }
                });
            }
        });
    }

    /**
     * 简单的连接函数 - 您要的主要函数
     * @param macAddress 目标设备的MAC地址
     * @return true: 连接成功 | false: 连接失败
     */
    public boolean connectToDeviceSimple(String macAddress) {
        if (bluetoothService == null) {
            bluetoothService = new BluetoothSerialService(this);
        }

        // 使用内置的简单连接方法
        return bluetoothService.connect(macAddress);
    }

    /**
     * 带超时的连接函数
     * @param macAddress 目标设备的MAC地址
     * @param timeoutMs 超时时间（毫秒）
     * @return true: 连接成功 | false: 连接失败
     */
    public boolean connectToDeviceWithTimeout(String macAddress, long timeoutMs) {
        if (bluetoothService == null) {
            bluetoothService = new BluetoothSerialService(this);
        }

        return bluetoothService.connectToDeviceSync(macAddress, timeoutMs);
    }

    /**
     * 异步连接函数
     * @param macAddress 目标设备的MAC地址
     * @param callback 连接结果回调
     */
    public void connectToDeviceAsync(String macAddress,
                                     BluetoothSerialService.ConnectionResultCallback callback) {
        if (bluetoothService == null) {
            bluetoothService = new BluetoothSerialService(this);
        }

        bluetoothService.setConnectionResultCallback(callback);
        bluetoothService.connectToDevice(macAddress);
    }

    /**
     * 检查设备是否已连接
     * @return true: 已连接 | false: 未连接
     */
    public boolean isDeviceConnected() {
        return bluetoothService != null && bluetoothService.isConnected();
    }

    /**
     * 获取已连接设备的MAC地址
     * @return MAC地址字符串，如果未连接则返回null
     */
    public String getConnectedDeviceAddress() {
        if (bluetoothService != null) {
            return bluetoothService.getConnectedDeviceAddress();
        }
        return null;
    }

    /**
     * 断开当前连接
     */
    public void disconnectDevice() {
        if (bluetoothService != null) {
            bluetoothService.disconnect();
        }
    }

    /**
     * 发送数据到已连接的设备
     * @param data 要发送的数据
     * @return true: 发送成功 | false: 发送失败
     */
    public boolean sendData(String data) {
        if (bluetoothService != null && bluetoothService.isConnected()) {
            return bluetoothService.write(data);
        }
        return false;
    }

    // 实现ConnectionCallback接口
    @Override
    public void onConnectionStateChanged(int state) {
        String stateText = "";
        switch (state) {
            case BluetoothSerialService.STATE_CONNECTING:
                MainActivityConnectStatu.setText(getString(R.string.main_activity_connect_status_CON));
                stateText = "正在连接...";
                break;
            case BluetoothSerialService.STATE_CONNECTED:
                MainActivityConnectStatu.setText(getString(R.string.main_activity_connect_status_OK));
                stateText = "已连接";
                break;
            case BluetoothSerialService.STATE_DISCONNECTED:
                MainActivityConnectStatu.setText(getString(R.string.main_activity_connect_status_NO));
                stateText = "已断开";
                break;
            case BluetoothSerialService.STATE_ERROR:
                MainActivityConnectStatu.setText(getString(R.string.main_activity_connect_status_NO));
                stateText = "连接错误";
                break;
        }

        final String finalStateText = stateText;
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                Toast.makeText(MainActivity.this, "连接状态: " + finalStateText,
                        Toast.LENGTH_SHORT).show();
            }
        });
    }

    @Override
    public void onDataReceived(byte[] data) {
        String receivedText = new String(data);
        Log.d("BluetoothData", "收到数据: " + receivedText);

        // 在UI线程更新接收到的数据
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                // 这里可以更新UI显示接收到的数据
            }
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        disconnectDevice();
    }

    private void ButtonOnClickTaskCreate(){
        MainActivityButtonOpenLight.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                MainActivityButtonOpenLight_onClick(view);
            }
        });

        MainActivityButtonCloseLight.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                MainActivityButtonCloseLight_onClick(view);
            }
        });
    }

    private void MainActivityButtonOpenLight_onClick(View v){
        if (bluetoothService == null || !bluetoothService.isConnected()) {
            Toast.makeText(MainActivity.this, "请先连接蓝牙设备", Toast.LENGTH_SHORT).show();
            return;
        }

        try {
            // 示例：发送十六进制数据 0x01 0x02 0x03
            byte[] hexData = new byte[] {
                    (byte) 0xAA,  // 协议头
                    (byte) 0x55,
                    (byte) 0x08,
                    (byte) 0x52,  // 指令类型：开灯
                    (byte) 0x45,
                    (byte) 0x4C,
                    (byte) 0x41,
                    (byte) 0x59,
                    (byte) 0x5F,
                    (byte) 0x4F,
                    (byte) 0x4E,
                    (byte) 0x79   // 校验位
            };

            boolean success = bluetoothService.write(hexData);

            if (success) {
                Toast.makeText(MainActivity.this, "开灯指令发送成功", Toast.LENGTH_SHORT).show();
                Log.d("BluetoothSend", "发送十六进制开灯指令");
            } else {
                Toast.makeText(MainActivity.this, "指令发送失败", Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            Toast.makeText(MainActivity.this, "指令发送异常: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            Log.e("BluetoothSend", "发送指令异常", e);
        }
    }

    private void MainActivityButtonCloseLight_onClick(View v){
        if (bluetoothService == null || !bluetoothService.isConnected()) {
            Toast.makeText(MainActivity.this, "请先连接蓝牙设备", Toast.LENGTH_SHORT).show();
            return;
        }

        try {
            // 示例：发送十六进制数据 0x01 0x02 0x03
            byte[] hexData = new byte[] {
                    (byte) 0xAA,  // 协议头
                    (byte) 0x55,
                    (byte) 0x09,
                    (byte) 0x52,  // 指令类型：关灯
                    (byte) 0x45,
                    (byte) 0x4C,
                    (byte) 0x41,
                    (byte) 0x59,
                    (byte) 0x5F,
                    (byte) 0x4F,
                    (byte) 0x46,
                    (byte) 0x46,
                    (byte) 0xB7   // 校验位
            };

            boolean success = bluetoothService.write(hexData);

            if (success) {
                Toast.makeText(MainActivity.this, "关灯指令发送成功", Toast.LENGTH_SHORT).show();
                Log.d("BluetoothSend", "发送十六进制关灯指令");
            } else {
                Toast.makeText(MainActivity.this, "指令发送失败", Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            Toast.makeText(MainActivity.this, "指令发送异常: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            Log.e("BluetoothSend", "发送指令异常", e);
        }
    }

}