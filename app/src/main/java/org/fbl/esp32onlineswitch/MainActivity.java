// MainActivity.java
package org.fbl.esp32onlineswitch;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HexFormat;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MainActivity extends AppCompatActivity implements BluetoothSerialService.ConnectionCallback{

    private static final int REQUEST_BLUETOOTH_PERMISSIONS = 100;
    private BluetoothSerialService bluetoothService;

    private String DrivesMacAddress;

    private final Timer timer = new Timer();
    private TimerTask timerTask_get_driver_status;

    private String DriverTime;
    private short DriverAlarms;

    private ImageButton MainActivityConnectStatu;
    private TextView MainActivityConnectDriverName;
    private ImageButton MainActivityButtonLight;
    private boolean MainActivityButtonLightStatu;
    private ImageButton MainActivityReboot;
    private ImageButton MainActivityClearAlarm;
    private ImageButton MainActivityAddAlarm;

    private LinearLayout MainActivityScrollViewAlarmList;


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        DrivesMacAddress = "78:42:1C:18:E8:0A";
        DriverTime = "00:00:00";
        DriverAlarms = 0;


        MainActivityConnectStatu = findViewById(R.id.btn_BTConnect);
        MainActivityConnectStatu.setImageResource(R.drawable.ic_bluetooth_pink_disconnected);
        MainActivityConnectDriverName = findViewById(R.id.tv_ConnectDriverName);
        MainActivityConnectDriverName.setText("");
        MainActivityButtonLight = findViewById(R.id.btn_LightSwitch);
        MainActivityButtonLight.setImageResource(R.drawable.ic_switch_off);
        MainActivityButtonLightStatu = false;
        MainActivityReboot = findViewById(R.id.btn_Reboot);
        MainActivityReboot.setImageResource(R.drawable.ic_reboot);
        MainActivityClearAlarm = findViewById(R.id.btn_clear_alarm);
        MainActivityClearAlarm.setImageResource(R.drawable.ic_clear_alarms);
        MainActivityAddAlarm = findViewById(R.id.btn_add_alarm);
        MainActivityAddAlarm.setImageResource(R.drawable.ic_add_alarm_task);
        MainActivityScrollViewAlarmList = findViewById(R.id.layout_AlarmList);

        ButtonOnClickTaskCreate();

        // 检查并请求权限
        if (!checkBluetoothPermissions()) {
            requestBluetoothPermissions();
        }

        // 初始化蓝牙服务
        bluetoothService = new BluetoothSerialService(this);
        bluetoothService.setConnectionCallback(this);

        // 测试连接函数
        testConnectionFunctions(DrivesMacAddress);
    }

    private byte CRC_Cal(byte[] bytes){
        byte res = 0x00;

        for(int i = 0; i < bytes.length;i++){
            if(i > 2 && i < bytes.length - 1) res += bytes[i];
            Log.d("CRC_Test", HexFormat.of().toHexDigits(bytes[i]));
        }
        Log.d("CRC_Test", HexFormat.of().toHexDigits(res));
        return res;
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
    private void testConnectionFunctions(String MacAddress) {

        // 1. 使用简单的connect函数
        boolean isConnected = connectToDeviceSimple(MacAddress);
        Log.d("BluetoothTest", "简单连接结果: " + isConnected);

        // 2. 使用带超时的同步连接
        boolean isConnectedSync = connectToDeviceWithTimeout(MacAddress, 5000);
        Log.d("BluetoothTest", "同步连接结果: " + isConnectedSync);

        // 3. 使用异步连接
        connectToDeviceAsync(MacAddress, new BluetoothSerialService.ConnectionResultCallback() {
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
                MainActivityConnectStatu.setImageResource(R.drawable.ic_bluetooth_pink_connecting);
                MainActivityConnectDriverName.setText("");
                stateText = "正在连接...";
                break;
            case BluetoothSerialService.STATE_CONNECTED:
                MainActivityConnectStatu.setImageResource(R.drawable.ic_bluetooth_pink_connected);
                MainActivityConnectDriverName.setText(bluetoothService.getConnectedDeviceName());
                GetDriverStatus();
                SetDriverTime();
                stateText = "已连接";
                break;
            case BluetoothSerialService.STATE_DISCONNECTED:
                MainActivityConnectStatu.setImageResource(R.drawable.ic_bluetooth_pink_disconnected);
                MainActivityConnectDriverName.setText("");
                stateText = "已断开";
                break;
            case BluetoothSerialService.STATE_ERROR:
                MainActivityConnectStatu.setImageResource(R.drawable.ic_bluetooth_pink_disconnected);
                MainActivityConnectDriverName.setText("");
                stateText = "连接错误";
                break;
        }

        final String finalStateText = stateText;
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                Toast.makeText(MainActivity.this, "连接状态: " + finalStateText,
                        Toast.LENGTH_SHORT).show();
//                if(state == BluetoothSerialService.STATE_CONNECTED) GetDriverStatus();
            }
        });
    }

    private boolean GetLightStatus(String data){
        Pattern pattern = Pattern.compile("STATUS:RELAY=[A-Z]+");
        Matcher matcher = pattern.matcher(data);
        while (matcher.find()){
            String findString = matcher.group();
            Pattern pattern_off = Pattern.compile("OFF");
            Pattern pattern_on = Pattern.compile("ON");
            Matcher matcher_1 = pattern_off.matcher(findString);
            while(matcher_1.find()) {
                Log.d("STATUS:RELAY=", matcher_1.group());
                return false;
            }
            matcher_1 = pattern_on.matcher(findString);
            while(matcher_1.find()){
                Log.d("STATUS:RELAY=", matcher_1.group());
                return true;
            }
        }
        return false;
    }

    private String GetDriverTime(String data){
        String res = "";
        Pattern pattern = Pattern.compile("TIME=[0-9]+:[0-9]+:[0-9]+");
        Matcher matcher = pattern.matcher(data);
        while (matcher.find()) {
            String findString = matcher.group();
            Pattern pattern_time = Pattern.compile("[0-9]+:[0-9]+:[0-9]+");
            Matcher matcher_time = pattern_time.matcher(findString);
            while(matcher_time.find()){
                res = matcher_time.group();
                Log.d("STATUS:TIME=", res);

            }
        }
        return res;
    }

    private short GetDriverAlarms(String data){
        short res = 0;
        Pattern pattern = Pattern.compile("ALARMS=[0-9]+");
        Matcher matcher = pattern.matcher(data);
        while (matcher.find()) {
            String findString = matcher.group();
            Pattern pattern_number = Pattern.compile("[0-9]+");
            Matcher matcher_number = pattern_number.matcher(findString);
            while(matcher_number.find()){
                res = Short.parseShort(matcher_number.group());
                Log.d("STATUS:ALARMS=", Short.toString(res));

            }
        }
        return res;
    }

    private void DecodeReceivedData(String data){
        MainActivityButtonLightStatu = GetLightStatus(data);
        DriverTime = GetDriverTime(data);
        DriverAlarms = GetDriverAlarms(data);
    }

    @Override
    public void onDataReceived(byte[] data) {
        String receivedText = new String(data);
        Log.d("BluetoothData", "收到数据: " + receivedText);
        DecodeReceivedData(receivedText);

        // 在UI线程更新接收到的数据
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if(MainActivityButtonLightStatu) MainActivityButtonLight.setImageResource(R.drawable.ic_switch_on);
                else MainActivityButtonLight.setImageResource(R.drawable.ic_switch_off);
                // 这里可以更新UI显示接收到的数据
            }
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        disconnectDevice();
    }

    //GET_STATUS:
    // AA 55
    // 0A
    // 47 45 54 5F 53 54 41 54 55 53
    // 23
    private void GetDriverStatus(){
        if (bluetoothService == null || !bluetoothService.isConnected()) {
            Toast.makeText(MainActivity.this, "请先连接蓝牙设备", Toast.LENGTH_SHORT).show();
            return;
        }

        try {

            byte[] hexData = new byte[] {
                    (byte) 0xAA,  // 协议头
                    (byte) 0x55,
                    (byte) 0x0A,
                    (byte) 0x47, (byte) 0x45, (byte) 0x54, (byte) 0x5F, (byte) 0x53, (byte) 0x54, (byte) 0x41, (byte) 0x54, (byte) 0x55, (byte) 0x53,
                    (byte) 0x23   // 校验位
            };
            boolean success = bluetoothService.write(hexData);

//            if (success) {
//                Toast.makeText(MainActivity.this, "开灯指令发送成功", Toast.LENGTH_SHORT).show();
//                Log.d("BluetoothSend", "发送十六进制开灯指令");
//            } else {
//                Toast.makeText(MainActivity.this, "指令发送失败", Toast.LENGTH_SHORT).show();
//            }
        } catch (Exception e) {
            Toast.makeText(MainActivity.this, "指令发送异常: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            Log.e("BluetoothSend", "发送指令异常", e);
        }
    }

    private void ButtonOnClickTaskCreate(){

        MainActivityConnectStatu.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                MainActivityConnectStatuOnClickListener(view);
            }
        });

        MainActivityButtonLight.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                MainActivityButtonLightOnClickListener(view);
            }
        });

        MainActivityReboot.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                MainActivityRebootOnClickListener(view);
            }
        });

        MainActivityAddAlarm.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                MainActivityAddAlarmOnClickListener(view);
            }
        });

        MainActivityClearAlarm.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                MainActivityClearAlarmOnClickListener(view);
            }
        });
    }

    private void MainActivityConnectStatuOnClickListener(View v){
        testConnectionFunctions(DrivesMacAddress);
    }

    private void MainActivityButtonLightOnClickListener(View v){
        MainActivityButtonLightStatu = !MainActivityButtonLightStatu;
        if(MainActivityButtonLightStatu){
            MainActivityButtonLight.setImageResource(R.drawable.ic_switch_on);
            MainActivityButtonOpenLight_onClick(v);
        }else{
            MainActivityButtonLight.setImageResource(R.drawable.ic_switch_off);
            MainActivityButtonCloseLight_onClick(v);
        }
    }

    private void MainActivityButtonOpenLight_onClick(View v){
        if (bluetoothService == null || !bluetoothService.isConnected()) {
            Toast.makeText(MainActivity.this, "请先连接蓝牙设备", Toast.LENGTH_SHORT).show();
            return;
        }

        try {

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

//            if (success) {
//                Toast.makeText(MainActivity.this, "开灯指令发送成功", Toast.LENGTH_SHORT).show();
//                Log.d("BluetoothSend", "发送十六进制开灯指令");
//            } else {
//                Toast.makeText(MainActivity.this, "指令发送失败", Toast.LENGTH_SHORT).show();
//            }
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

//            if (success) {
//                Toast.makeText(MainActivity.this, "关灯指令发送成功", Toast.LENGTH_SHORT).show();
//                Log.d("BluetoothSend", "发送十六进制关灯指令");
//            } else {
//                Toast.makeText(MainActivity.this, "指令发送失败", Toast.LENGTH_SHORT).show();
//            }
        } catch (Exception e) {
            Toast.makeText(MainActivity.this, "指令发送异常: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            Log.e("BluetoothSend", "发送指令异常", e);
        }
    }

    private void MainActivityRebootOnClickListener(View v){
        if (bluetoothService == null || !bluetoothService.isConnected()) {
            Toast.makeText(MainActivity.this, "请先连接蓝牙设备", Toast.LENGTH_SHORT).show();
            return;
        }

        try {

            byte[] hexData = new byte[] {
                    (byte) 0xAA, (byte) 0x55,
                    (byte) 0x07,
                    (byte) 0x52, (byte) 0x45, (byte) 0x53, (byte) 0x54, (byte) 0x41, (byte) 0x52, (byte) 0x54,
                    (byte) 0x25
            };

            boolean success = bluetoothService.write(hexData);

//            if (success) {
//                Toast.makeText(MainActivity.this, "重启指令发送成功", Toast.LENGTH_SHORT).show();
//                Log.d("BluetoothSend", "发送十六进制重启指令");
//            } else {
//                Toast.makeText(MainActivity.this, "指令发送失败", Toast.LENGTH_SHORT).show();
//            }
        } catch (Exception e) {
            Toast.makeText(MainActivity.this, "指令发送异常: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            Log.e("BluetoothSend", "发送指令异常", e);
        }
    }

    private void MainActivityAddAlarmOnClickListener(View v){

    }

    private void MainActivityClearAlarmOnClickListener(View v){
        if (bluetoothService == null || !bluetoothService.isConnected()) {
            Toast.makeText(MainActivity.this, "请先连接蓝牙设备", Toast.LENGTH_SHORT).show();
            return;
        }

        try {

            byte[] hexData = new byte[] {
                    (byte) 0xAA, (byte) 0x55,
                    (byte) 0x0C,
                    (byte) 0x43, (byte) 0x4C, (byte) 0x45, (byte) 0x41, (byte) 0x52, (byte) 0x5F, (byte) 0x41, (byte) 0x4C, (byte) 0x41, (byte) 0x52, (byte) 0x4D, (byte) 0x53,
                    (byte) 0x86
            };

            boolean success = bluetoothService.write(hexData);

//            if (success) {
//                Toast.makeText(MainActivity.this, "清除闹钟任务指令发送成功", Toast.LENGTH_SHORT).show();
//                Log.d("BluetoothSend", "发送十六进制清除闹钟任务指令");
//            } else {
//                Toast.makeText(MainActivity.this, "指令发送失败", Toast.LENGTH_SHORT).show();
//            }
        } catch (Exception e) {
            Toast.makeText(MainActivity.this, "指令发送异常: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            Log.e("BluetoothSend", "发送指令异常", e);
        }
    }

    //SET_TIME_XX_XX_XX:
    // AA 55
    // 11
    // 53 45 54 5F 54 49 4D 45 3D 30 33 2C 30 37 2C 30 30
    // 39
    private void SetDriverTime(){
        if (bluetoothService == null || !bluetoothService.isConnected()) {
            Toast.makeText(MainActivity.this, "请先连接蓝牙设备", Toast.LENGTH_SHORT).show();
            return;
        }

        SimpleDateFormat hour = new SimpleDateFormat();
        hour.applyPattern("HH");
        SimpleDateFormat min = new SimpleDateFormat();
        min.applyPattern("mm");
        SimpleDateFormat sec = new SimpleDateFormat();
        sec.applyPattern("ss");

        Date date = new Date();

        try {

            byte[] hexData = new byte[] {
                    (byte) 0xAA, (byte) 0x55,
                    (byte) 0x11,
                    (byte) 0x53, (byte) 0x45, (byte) 0x54, (byte) 0x5F, (byte) 0x54, (byte) 0x49, (byte) 0x4D, (byte) 0x45, (byte) 0x3D,
                    (byte) (hour.format(date).charAt(0)), (byte) (hour.format(date).charAt(1)),
                    (byte) 0x2C,
                    (byte) (min.format(date).charAt(0)), (byte) (min.format(date).charAt(1)),
                    (byte) 0x2C,
                    (byte) (sec.format(date).charAt(0)), (byte) (sec.format(date).charAt(1)),
                    (byte) 0x00
            };
            hexData[hexData.length - 1] = CRC_Cal(hexData);
            boolean success = bluetoothService.write(hexData);

//            if (success) {
//                Toast.makeText(MainActivity.this, "设置设备时间指令发送成功", Toast.LENGTH_SHORT).show();
//                Log.d("BluetoothSend", "发送十六进制设置设备时间指令");
//            } else {
//                Toast.makeText(MainActivity.this, "指令发送失败", Toast.LENGTH_SHORT).show();
//            }
        } catch (Exception e) {
            Toast.makeText(MainActivity.this, "指令发送异常: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            Log.e("BluetoothSend", "发送指令异常", e);
        }
    }

    //ADD_ALARM_XX_XX_XX_TASK:
    // AA 55
    // 1B
    // 41 44 44 5F 41 4C 41 52 4D 3D 30 30 2C 31 30 2C 30 30 2C 52 45 4C 41 59 5F 4F 4E
    // F0



    //ENABLE_ALARM_X_ENABLE/DISABLE:
    // AA 55
    // 16
    // 45 4E 41 42 4C 45 5F 41 4C 41 52 4D 3D 30 2C 44 49 53 41 42 4C 45
    // 00



    //DELETE_ALARM_X:
    // AA 55
    // 0E
    // 44 45 4C 45 54 45 5F 41 4C 41 52 4D 3D 30
    // EC



}