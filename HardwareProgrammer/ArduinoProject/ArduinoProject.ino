#include <BTAddress.h>
#include <BTAdvertisedDevice.h>
#include <BTScan.h>
#include <BluetoothSerial.h>
#include <Preferences.h>  // ESP32内置FLASH存储

// 引脚定义
#define RELAY_PIN 26

// 蓝牙配置
#if !defined(CONFIG_BT_ENABLED) || !defined(CONFIG_BLUEDROID_ENABLED)
#error 蓝牙未启用，请在menuconfig中启用
#endif
BluetoothSerial SerialBT;

// 数据帧定义
#define FRAME_HEADER_1 0xAA
#define FRAME_HEADER_2 0x55
#define MAX_DATA_LENGTH 64
#define MAX_ALARMS 10    // 最大闹钟数量

// FLASH存储命名空间
Preferences preferences;

// ========== 任务定义 ==========

typedef enum {
    TASK_NONE = 0,
    TASK_RELAY_ON,       // 打开继电器
    TASK_RELAY_OFF,      // 关闭继电器
    TASK_RELAY_TOGGLE,   // 切换继电器状态
    TASK_RESTART,        // 重启设备
    TASK_TIME_SEND,      // 发送设备时间
    TASK_TIME_RECV       // 获取主机时间
} TaskType;

// ========== 函数声明 ==========

// RTC函数
void initSimpleRTC();
void updateSimpleRTC();
String getSimpleTimeString();
uint32_t getTotalSeconds();
uint8_t getRelativeDay();
bool setRTCTime(uint8_t hour, uint8_t minute, uint8_t second);

// 闹钟函数
void initAlarmList();
int addAlarmTask(uint8_t hour, uint8_t minute, uint8_t second, TaskType taskType, bool saveToFlash = true);
bool setAlarmEnabled(uint8_t alarmId, bool enabled, bool saveToFlash = true);
bool deleteAlarm(uint8_t alarmId, bool saveToFlash = true);
void clearAllAlarms(bool saveToFlash = true);
uint8_t getAlarmCount();
void checkAndExecuteAlarms();
void executeTask(TaskType taskType, uint8_t alarmId = 255);
String taskTypeToString(TaskType taskType);
String getAlarmListString();

// FLASH存储函数
void saveAlarmsToFlash();
void loadAlarmsFromFlash();
void resetAlarmsState();

// 任务处理函数
void handleTaskRun(uint8_t* data, uint8_t length);
void sendResponse(String message);
void sendErrorResponse(String error);
void sendDataFrame(const uint8_t* data, uint8_t length);

// 具体任务实现函数
void executeRelayOn();
void executeRelayOff();
void executeRelayToggle();
void executeGetStatus();
void executeTimeSend();
void executeTimeRecv();
void executeSetTime(String command);
void executeGetTime();
void executeAddAlarm(String command);
void executeGetAlarms();
void executeEnableAlarm(String command);
void executeDeleteAlarm(String command);
void executeClearAlarms();
void executeRestart();

// 接收处理函数
bool processReceivedData(uint8_t byte);

// ========== RTC结构 ==========

// RTC时间结构体
typedef struct {
    uint32_t startMillis;  // 设备启动时的毫秒数
    uint8_t hour;          // 时 0-23 (从启动开始计算)
    uint8_t minute;        // 分 0-59
    uint8_t second;        // 秒 0-59
    bool isRunning;        // RTC是否正在运行
} SimpleRTC;

// ========== 闹钟任务结构 ==========

// 闹钟任务项
typedef struct {
    uint8_t id;            // 闹钟ID (0-9)
    bool enabled;          // 是否启用
    uint8_t hour;          // 时 0-23
    uint8_t minute;        // 分 0-59
    uint8_t second;        // 秒 0-59
    TaskType taskType;     // 任务类型
    bool executedToday;    // 今天是否已执行
    uint8_t lastExecDay;   // 上次执行的日期（相对日期）
} AlarmTask;

// ========== 全局变量 ==========

bool relayState = false;
SimpleRTC rtc;
AlarmTask alarmList[MAX_ALARMS];
uint8_t nextAlarmId = 0;

// 接收缓冲区
uint8_t rx_buffer[128];
uint8_t rx_index = 0;
bool frame_started = false;
uint8_t expected_length = 0;

// ========== RTC函数实现 ==========

// 初始化RTC
void initSimpleRTC() {
    rtc.startMillis = millis();
    rtc.hour = 0;
    rtc.minute = 0;
    rtc.second = 0;
    rtc.isRunning = true;
    
    Serial.println("RTC初始化完成");
    Serial.println("时钟已启动：从00:00:00开始");
}

// 更新RTC时间
void updateSimpleRTC() {
    if (!rtc.isRunning) return;
    
    uint32_t elapsedMillis = millis() - rtc.startMillis;
    uint32_t totalSeconds = elapsedMillis / 1000;
    
    // 计算时、分、秒
    rtc.hour = totalSeconds / 3600;
    rtc.minute = (totalSeconds % 3600) / 60;
    rtc.second = totalSeconds % 60;
}

// 获取格式化时间字符串
String getSimpleTimeString() {
    char timeStr[16];
    snprintf(timeStr, sizeof(timeStr), "%02d:%02d:%02d", 
             rtc.hour, rtc.minute, rtc.second);
    return String(timeStr);
}

// 获取总运行秒数
uint32_t getTotalSeconds() {
    return millis() / 1000;
}

// 获取当前相对日期（从启动开始的天数）
uint8_t getRelativeDay() {
    return millis() / (24 * 3600 * 1000UL);  // 每天86400000毫秒
}

// 设置时间
bool setRTCTime(uint8_t hour, uint8_t minute, uint8_t second) {
    if (hour > 23 || minute > 59 || second > 59) {
        return false;
    }
    
    // 计算新的起始时间
    uint32_t targetSeconds = hour * 3600UL + minute * 60UL + second;
    rtc.startMillis = millis() - targetSeconds * 1000UL;
    rtc.hour = hour;
    rtc.minute = minute;
    rtc.second = second;
    
    Serial.print("RTC时间已设置为: ");
    Serial.print(rtc.hour);
    Serial.print(":");
    Serial.print(rtc.minute);
    Serial.print(":");
    Serial.println(rtc.second);
    
    // 设置时间后重置闹钟状态为开启
    resetAlarmsState();
    
    return true;
}

// ========== FLASH存储函数实现 ==========

void saveAlarmsToFlash() {
    Serial.println("保存闹钟到FLASH...");
    
    // 打开preferences命名空间
    preferences.begin("alarms", false);
    
    // 保存闹钟数量
    uint8_t activeCount = 0;
    for (int i = 0; i < MAX_ALARMS; i++) {
        if (alarmList[i].enabled && alarmList[i].taskType != TASK_NONE) {
            activeCount++;
        }
    }
    preferences.putUChar("count", activeCount);
    
    // 保存每个闹钟
    int savedIndex = 0;
    for (int i = 0; i < MAX_ALARMS; i++) {
        if (alarmList[i].enabled && alarmList[i].taskType != TASK_NONE) {
            String keyBase = "alarm" + String(savedIndex);
            
            preferences.putUChar((keyBase + "_enabled").c_str(), alarmList[i].enabled);
            preferences.putUChar((keyBase + "_hour").c_str(), alarmList[i].hour);
            preferences.putUChar((keyBase + "_minute").c_str(), alarmList[i].minute);
            preferences.putUChar((keyBase + "_second").c_str(), alarmList[i].second);
            preferences.putUChar((keyBase + "_task").c_str(), (uint8_t)alarmList[i].taskType);
            
            savedIndex++;
            Serial.print("保存闹钟 #");
            Serial.print(i);
            Serial.print(" -> FLASH索引 ");
            Serial.println(savedIndex - 1);
        }
    }
    
    preferences.end();
    Serial.println("闹钟保存完成");
}

void loadAlarmsFromFlash() {
    Serial.println("从FLASH加载闹钟...");
    
    // 打开preferences命名空间
    preferences.begin("alarms", true);  // 只读模式
    
    // 获取闹钟数量
    uint8_t savedCount = preferences.getUChar("count", 0);
    
    if (savedCount == 0) {
        Serial.println("FLASH中没有保存的闹钟");
        preferences.end();
        return;
    }
    
    Serial.print("找到 ");
    Serial.print(savedCount);
    Serial.println(" 个保存的闹钟");
    
    // 加载每个闹钟
    for (int i = 0; i < savedCount && i < MAX_ALARMS; i++) {
        String keyBase = "alarm" + String(i);
        
        bool enabled = preferences.getUChar((keyBase + "_enabled").c_str(), 0);
        uint8_t hour = preferences.getUChar((keyBase + "_hour").c_str(), 0);
        uint8_t minute = preferences.getUChar((keyBase + "_minute").c_str(), 0);
        uint8_t second = preferences.getUChar((keyBase + "_second").c_str(), 0);
        TaskType taskType = (TaskType)preferences.getUChar((keyBase + "_task").c_str(), TASK_NONE);
        
        if (taskType != TASK_NONE) {
            // 添加到内存中的闹钟列表
            addAlarmTask(hour, minute, second, taskType, false);
            
            Serial.print("加载闹钟 #");
            Serial.print(i);
            Serial.print(": ");
            Serial.print(hour);
            Serial.print(":");
            Serial.print(minute);
            Serial.print(":");
            Serial.print(second);
            Serial.print(" - ");
            Serial.println(taskTypeToString(taskType));
        }
    }
    
    preferences.end();
    Serial.println("闹钟加载完成");
}

void resetAlarmsState() {
    Serial.println("重置所有闹钟状态为开启");
    
    for (int i = 0; i < MAX_ALARMS; i++) {
        if (alarmList[i].enabled && alarmList[i].taskType != TASK_NONE) {
            alarmList[i].executedToday = false;
            alarmList[i].lastExecDay = getRelativeDay();
        }
    }
    
    Serial.println("闹钟状态已重置");
}

// ========== 闹钟任务列表函数 ==========

// 初始化闹钟列表
void initAlarmList() {
    for (int i = 0; i < MAX_ALARMS; i++) {
        alarmList[i].id = i;
        alarmList[i].enabled = false;
        alarmList[i].hour = 0;
        alarmList[i].minute = 0;
        alarmList[i].second = 0;
        alarmList[i].taskType = TASK_NONE;
        alarmList[i].executedToday = false;
        alarmList[i].lastExecDay = 0;
    }
    nextAlarmId = 0;
    
    // 从FLASH加载保存的闹钟
    loadAlarmsFromFlash();
    
    // 重置闹钟状态为开启
    resetAlarmsState();
    
    Serial.println("闹钟任务列表初始化完成");
}

// 添加闹钟任务
int addAlarmTask(uint8_t hour, uint8_t minute, uint8_t second, TaskType taskType, bool saveToFlash) {
    if (nextAlarmId >= MAX_ALARMS) {
        Serial.println("错误：闹钟列表已满");
        return -1;
    }
    
    if (hour > 23 || minute > 59 || second > 59) {
        Serial.println("错误：时间格式无效");
        return -1;
    }
    
    alarmList[nextAlarmId].enabled = true;
    alarmList[nextAlarmId].hour = hour;
    alarmList[nextAlarmId].minute = minute;
    alarmList[nextAlarmId].second = second;
    alarmList[nextAlarmId].taskType = taskType;
    alarmList[nextAlarmId].executedToday = false;
    alarmList[nextAlarmId].lastExecDay = getRelativeDay();
    
    Serial.print("添加闹钟任务 #");
    Serial.print(nextAlarmId);
    Serial.print(": ");
    Serial.print(hour);
    Serial.print(":");
    Serial.print(minute);
    Serial.print(":");
    Serial.print(second);
    Serial.print(" - ");
    Serial.println(taskTypeToString(taskType));
    
    int addedId = nextAlarmId;
    nextAlarmId++;
    
    // 保存到FLASH
    if (saveToFlash) {
        saveAlarmsToFlash();
    }
    
    return addedId;
}

// 启用/禁用闹钟
bool setAlarmEnabled(uint8_t alarmId, bool enabled, bool saveToFlash) {
    if (alarmId >= MAX_ALARMS) {
        return false;
    }
    
    alarmList[alarmId].enabled = enabled;
    Serial.print("闹钟 #");
    Serial.print(alarmId);
    Serial.print(enabled ? " 已启用" : " 已禁用");
    Serial.print(" - 时间: ");
    Serial.print(alarmList[alarmId].hour);
    Serial.print(":");
    Serial.print(alarmList[alarmId].minute);
    Serial.print(":");
    Serial.print(alarmList[alarmId].second);
    Serial.print(" - 任务: ");
    Serial.println(taskTypeToString(alarmList[alarmId].taskType));
    
    // 保存到FLASH
    if (saveToFlash) {
        saveAlarmsToFlash();
    }
    
    return true;
}

// 删除闹钟
bool deleteAlarm(uint8_t alarmId, bool saveToFlash) {
    if (alarmId >= MAX_ALARMS) {
        return false;
    }
    
    Serial.print("删除闹钟 #");
    Serial.print(alarmId);
    Serial.print(" - 时间: ");
    Serial.print(alarmList[alarmId].hour);
    Serial.print(":");
    Serial.print(alarmList[alarmId].minute);
    Serial.print(":");
    Serial.print(alarmList[alarmId].second);
    Serial.print(" - 任务: ");
    Serial.println(taskTypeToString(alarmList[alarmId].taskType));
    
    alarmList[alarmId].enabled = false;
    alarmList[alarmId].taskType = TASK_NONE;
    
    // 如果删除的是最后一个闹钟，调整nextAlarmId
    if (alarmId == nextAlarmId - 1) {
        while (nextAlarmId > 0 && !alarmList[nextAlarmId - 1].enabled) {
            nextAlarmId--;
        }
    }
    
    // 保存到FLASH
    if (saveToFlash) {
        saveAlarmsToFlash();
    }
    
    return true;
}

// 清除所有闹钟
void clearAllAlarms(bool saveToFlash) {
    for (int i = 0; i < MAX_ALARMS; i++) {
        alarmList[i].enabled = false;
        alarmList[i].taskType = TASK_NONE;
    }
    nextAlarmId = 0;
    
    // 保存到FLASH
    if (saveToFlash) {
        preferences.begin("alarms", false);
        preferences.clear();  // 清除所有保存的闹钟
        preferences.end();
    }
    
    Serial.println("所有闹钟已清除");
}

// 获取闹钟数量
uint8_t getAlarmCount() {
    uint8_t count = 0;
    for (int i = 0; i < MAX_ALARMS; i++) {
        if (alarmList[i].enabled) {
            count++;
        }
    }
    return count;
}

// 检查并执行闹钟任务
void checkAndExecuteAlarms() {
    if (!rtc.isRunning) return;
    
    uint8_t currentDay = getRelativeDay();
    
    for (int i = 0; i < MAX_ALARMS; i++) {
        if (!alarmList[i].enabled || alarmList[i].taskType == TASK_NONE) {
            continue;
        }
        
        // 检查是否今天已经执行过
        if (alarmList[i].executedToday && alarmList[i].lastExecDay == currentDay) {
            continue;
        }
        
        // 检查时间是否匹配
        if (alarmList[i].hour == rtc.hour &&
            alarmList[i].minute == rtc.minute &&
            alarmList[i].second == rtc.second) {
            
            // 执行任务
            executeTask(alarmList[i].taskType, i);
            
            // 标记已执行
            alarmList[i].executedToday = true;
            alarmList[i].lastExecDay = currentDay;
            
            Serial.print("闹钟 #");
            Serial.print(i);
            Serial.print(" 触发 - 时间: ");
            Serial.print(rtc.hour);
            Serial.print(":");
            Serial.print(rtc.minute);
            Serial.print(":");
            Serial.print(rtc.second);
            Serial.print(" - 任务: ");
            Serial.println(taskTypeToString(alarmList[i].taskType));
        }
    }
    
    // 每天0点重置执行标记
    if (rtc.hour == 0 && rtc.minute == 0 && rtc.second == 1) {
        for (int i = 0; i < MAX_ALARMS; i++) {
            alarmList[i].executedToday = false;
        }
        Serial.println("已重置所有闹钟的执行标记");
    }
}

// 执行任务
void executeTask(TaskType taskType, uint8_t alarmId) {
    switch (taskType) {
        case TASK_RELAY_ON:
            executeRelayOn();
            break;
            
        case TASK_RELAY_OFF:
            executeRelayOff();
            break;
            
        case TASK_RELAY_TOGGLE:
            executeRelayToggle();
            break;
            
        case TASK_RESTART:
            executeRestart();
            break;
            
        case TASK_TIME_SEND:
            // 发送当前时间（通过蓝牙响应）
            if (alarmId != 255) {
                String response = "ALARM_TIME_SEND:" + getSimpleTimeString();
                sendResponse(response);
            }
            Serial.println("执行任务: 发送设备时间");
            break;
            
        case TASK_TIME_RECV:
            // 请求主机时间（通过蓝牙响应）
            if (alarmId != 255) {
                String response = "ALARM_TIME_RECV:Request host time";
                sendResponse(response);
            }
            Serial.println("执行任务: 获取主机时间");
            break;
            
        default:
            Serial.println("未知任务类型");
            break;
    }
}

// 任务类型转字符串
String taskTypeToString(TaskType taskType) {
    switch (taskType) {
        case TASK_NONE: return "NONE";
        case TASK_RELAY_ON: return "RELAY_ON";
        case TASK_RELAY_OFF: return "RELAY_OFF";
        case TASK_RELAY_TOGGLE: return "RELAY_TOGGLE";
        case TASK_RESTART: return "RESTART";
        case TASK_TIME_SEND: return "TIME_SEND";
        case TASK_TIME_RECV: return "TIME_RECV";
        default: return "UNKNOWN";
    }
}

// 获取闹钟列表字符串
String getAlarmListString() {
    String listStr = "ALARM_LIST:";
    bool first = true;
    
    for (int i = 0; i < MAX_ALARMS; i++) {
        if (alarmList[i].enabled && alarmList[i].taskType != TASK_NONE) {
            if (!first) listStr += ";";
            first = false;
            
            listStr += "#";
            listStr += String(i);
            listStr += "=";
            listStr += String(alarmList[i].hour);
            listStr += ":";
            listStr += String(alarmList[i].minute);
            listStr += ":";
            listStr += String(alarmList[i].second);
            listStr += "-";
            listStr += taskTypeToString(alarmList[i].taskType);
            listStr += "-";
            listStr += alarmList[i].enabled ? "ENABLED" : "DISABLED";
        }
    }
    
    if (first) {
        listStr += "EMPTY";
    }
    
    return listStr;
}

// ========== 任务处理函数 ==========

void handleTaskRun(uint8_t* data, uint8_t length) {
    if (length == 0) {
        Serial.println("【错误】任务数据为空");
        sendErrorResponse("Empty task data");
        return;
    }
    
    // 将数据转换为字符串
    String command = "";
    for (uint8_t i = 0; i < length; i++) {
        command += (char)data[i];
    }
    
    Serial.print("【任务执行】命令: ");
    Serial.println(command);
    
    // 解析命令
    if (command == "RELAY_ON") {
        executeRelayOn();
    } else if (command == "RELAY_OFF") {
        executeRelayOff();
    } else if (command == "RELAY_TOGGLE") {
        executeRelayToggle();
    } else if (command == "GET_STATUS") {
        executeGetStatus();
    } else if (command == "TIME_SEND") {
        executeTimeSend();
    } else if (command == "TIME_RECV") {
        executeTimeRecv();
    } else if (command.startsWith("SET_TIME=")) {
        executeSetTime(command);
    } else if (command == "GET_TIME") {
        executeGetTime();
    } else if (command.startsWith("ADD_ALARM=")) {
        executeAddAlarm(command);
    } else if (command == "GET_ALARMS") {
        executeGetAlarms();
    } else if (command.startsWith("ENABLE_ALARM=")) {
        executeEnableAlarm(command);
    } else if (command.startsWith("DELETE_ALARM=")) {
        executeDeleteAlarm(command);
    } else if (command == "CLEAR_ALARMS") {
        executeClearAlarms();
    } else if (command == "RESTART") {
        executeRestart();
    } else {
        Serial.print("【错误】未知命令: ");
        Serial.println(command);
        sendErrorResponse("Unknown command: " + command);
    }
}

// ========== 闹钟相关任务实现 ==========

// 任务: 添加闹钟
void executeAddAlarm(String command) {
    Serial.println("【执行】添加闹钟");
    
    // 命令格式: ADD_ALARM=HH,MM,SS,TASK
    // TASK可以是: RELAY_ON, RELAY_OFF, RELAY_TOGGLE, RESTART, TIME_SEND, TIME_RECV
    // 例如: ADD_ALARM=08,00,00,RELAY_ON
    
    String params = command.substring(10);  // 跳过 "ADD_ALARM="
    
    int comma1 = params.indexOf(',');
    int comma2 = params.indexOf(',', comma1 + 1);
    int comma3 = params.indexOf(',', comma2 + 1);
    
    if (comma1 != -1 && comma2 != -1 && comma3 != -1) {
        uint8_t hour = params.substring(0, comma1).toInt();
        uint8_t minute = params.substring(comma1 + 1, comma2).toInt();
        uint8_t second = params.substring(comma2 + 1, comma3).toInt();
        String taskStr = params.substring(comma3 + 1);
        
        // 转换任务字符串为TaskType
        TaskType taskType = TASK_NONE;
        if (taskStr == "RELAY_ON") taskType = TASK_RELAY_ON;
        else if (taskStr == "RELAY_OFF") taskType = TASK_RELAY_OFF;
        else if (taskStr == "RELAY_TOGGLE") taskType = TASK_RELAY_TOGGLE;
        else if (taskStr == "RESTART") taskType = TASK_RESTART;
        else if (taskStr == "TIME_SEND") taskType = TASK_TIME_SEND;
        else if (taskStr == "TIME_RECV") taskType = TASK_TIME_RECV;
        
        if (taskType != TASK_NONE) {
            int alarmId = addAlarmTask(hour, minute, second, taskType, true);
            if (alarmId >= 0) {
                String response = "ADD_ALARM_OK:ID=";
                response += String(alarmId);
                response += ",TIME=";
                response += String(hour);
                response += ":";
                response += String(minute);
                response += ":";
                response += String(second);
                response += ",TASK=";
                response += taskStr;
                sendResponse(response);
            } else {
                sendErrorResponse("Failed to add alarm: list full");
            }
        } else {
            sendErrorResponse("Invalid task type. Use: RELAY_ON, RELAY_OFF, RELAY_TOGGLE, RESTART, TIME_SEND, TIME_RECV");
        }
    } else {
        sendErrorResponse("Invalid format. Use: ADD_ALARM=HH,MM,SS,TASK");
    }
}

// 任务: 获取闹钟列表
void executeGetAlarms() {
    Serial.println("【执行】获取闹钟列表");
    
    uint8_t alarmCount = getAlarmCount();
    String response = "ALARMS_COUNT:" + String(alarmCount);
    response += " LIST:" + getAlarmListString();
    
    sendResponse(response);
}

// 任务: 启用/禁用闹钟
void executeEnableAlarm(String command) {
    Serial.println("【执行】设置闹钟状态");
    
    // 命令格式: ENABLE_ALARM=ID,STATE
    // STATE可以是: ENABLE 或 DISABLE
    // 例如: ENABLE_ALARM=0,ENABLE
    
    String params = command.substring(13);  // 跳过 "ENABLE_ALARM="
    
    int comma = params.indexOf(',');
    if (comma != -1) {
        uint8_t alarmId = params.substring(0, comma).toInt();
        String stateStr = params.substring(comma + 1);
        
        bool enable = (stateStr == "ENABLE");
        
        if (setAlarmEnabled(alarmId, enable, true)) {
            String response = enable ? "ALARM_ENABLED_OK" : "ALARM_DISABLED_OK";
            response += ":ID=" + String(alarmId);
            sendResponse(response);
        } else {
            sendErrorResponse("Invalid alarm ID");
        }
    } else {
        sendErrorResponse("Invalid format. Use: ENABLE_ALARM=ID,STATE");
    }
}

// 任务: 删除闹钟
void executeDeleteAlarm(String command) {
    Serial.println("【执行】删除闹钟");
    
    // 命令格式: DELETE_ALARM=ID
    // 例如: DELETE_ALARM=0
    
    String params = command.substring(13);  // 跳过 "DELETE_ALARM="
    
    uint8_t alarmId = params.toInt();
    
    if (deleteAlarm(alarmId, true)) {
        String response = "DELETE_ALARM_OK:ID=" + String(alarmId);
        sendResponse(response);
    } else {
        sendErrorResponse("Invalid alarm ID");
    }
}

// 任务: 清除所有闹钟
void executeClearAlarms() {
    Serial.println("【执行】清除所有闹钟");
    clearAllAlarms(true);
    
    String response = "CLEAR_ALARMS_OK";
    sendResponse(response);
}

// ========== 其他任务实现 ==========

// 任务: 发送当前时间
void executeTimeSend() {
    Serial.println("【执行】发送RTC时间");
    
    String response = "RTC_TIME:" + getSimpleTimeString();
    response += " TOTAL_SEC:" + String(getTotalSeconds());
    
    sendResponse(response);
}

// 任务: 获取主机时间
void executeTimeRecv() {
    Serial.println("【执行】请求主机时间");
    
    String response = "REQUEST_HOST_TIME:Send SET_TIME=HH,MM,SS";
    sendResponse(response);
}

// 任务: 设置时间
void executeSetTime(String command) {
    Serial.println("【执行】设置RTC时间");
    
    // 命令格式: SET_TIME=HH,MM,SS
    String params = command.substring(9);  // 跳过 "SET_TIME="
    
    int comma1 = params.indexOf(',');
    int comma2 = params.indexOf(',', comma1 + 1);
    
    if (comma1 != -1 && comma2 != -1) {
        uint8_t hour = params.substring(0, comma1).toInt();
        uint8_t minute = params.substring(comma1 + 1, comma2).toInt();
        uint8_t second = params.substring(comma2 + 1).toInt();
        
        if (setRTCTime(hour, minute, second)) {
            String response = "SET_TIME_OK:" + getSimpleTimeString();
            sendResponse(response);
        } else {
            sendErrorResponse("Invalid time. Format: SET_TIME=HH,MM,SS (HH:0-23, MM:0-59, SS:0-59)");
        }
    } else {
        sendErrorResponse("Invalid format. Use: SET_TIME=HH,MM,SS");
    }
}

// 任务: 获取时间
void executeGetTime() {
    Serial.println("【执行】获取RTC时间");
    
    String response = "TIME:" + getSimpleTimeString();
    sendResponse(response);
}

// 任务: 打开继电器
void executeRelayOn() {
    Serial.println("【执行】打开继电器");
    digitalWrite(RELAY_PIN, LOW);
    relayState = true;
    
    String response = "RELAY_ON_OK";
    sendResponse(response);
}

// 任务: 关闭继电器
void executeRelayOff() {
    Serial.println("【执行】关闭继电器");
    digitalWrite(RELAY_PIN, HIGH);
    relayState = false;
    
    String response = "RELAY_OFF_OK";
    sendResponse(response);
}

// 任务: 切换继电器
void executeRelayToggle() {
    Serial.println("【执行】切换继电器");
    relayState = !relayState;
    digitalWrite(RELAY_PIN, relayState ? HIGH : LOW);
    
    String response = "RELAY_TOGGLE_OK:" + String(relayState ? "ON" : "OFF");
    sendResponse(response);
}

// 任务: 获取状态
void executeGetStatus() {
    Serial.println("【执行】获取设备状态");
    
    String response = "STATUS:";
    response += "RELAY=" + String(relayState ? "ON" : "OFF") + ",";
    response += "TIME=" + getSimpleTimeString() + ",";
    response += "ALARMS=" + String(getAlarmCount()) + ",";
    response += "UPTIME=" + String(getTotalSeconds()) + "s";
    
    sendResponse(response);
}

// 任务: 重启设备
void executeRestart() {
    Serial.println("【执行】重启设备");
    
    String response = "RESTART_OK:Device will restart in 1s";
    sendResponse(response);
    
    delay(1000);
    ESP.restart();
}

// ========== 辅助函数 ==========

// 发送响应
void sendResponse(String message) {
    uint8_t response_data[MAX_DATA_LENGTH];
    uint8_t response_len = min(message.length(), (unsigned int)MAX_DATA_LENGTH);
    
    for (uint8_t i = 0; i < response_len; i++) {
        response_data[i] = message[i];
    }
    
    sendDataFrame(response_data, response_len);
}

// 发送错误响应
void sendErrorResponse(String error) {
    Serial.print("【错误】");
    Serial.println(error);
    
    String response = "ERROR:" + error;
    sendResponse(response);
}

// 发送数据帧
void sendDataFrame(const uint8_t* data, uint8_t length) {
    uint8_t frame[4 + MAX_DATA_LENGTH + 1];
    uint8_t idx = 0;
    
    // 帧头
    frame[idx++] = 0xAA;
    frame[idx++] = 0x55;
    
    // 长度
    frame[idx++] = length;
    
    // 数据
    uint8_t checksum = 0;
    for (uint8_t i = 0; i < length; i++) {
        frame[idx++] = data[i];
        checksum += data[i];
    }
    
    // 校验和
    frame[idx++] = checksum;
    
    SerialBT.write(frame, idx);
    SerialBT.flush();
}

// ========== 主程序 ==========

void setup() {
    Serial.begin(115200);
    SerialBT.begin("FBL-BedLight-Switch");
    
    // 初始化引脚
    pinMode(RELAY_PIN, OUTPUT);
    digitalWrite(RELAY_PIN, HIGH);  // 初始状态关闭继电器
    
    // 初始化RTC
    initSimpleRTC();
    
    // 初始化闹钟列表（会自动从FLASH加载）
    initAlarmList();
    
    Serial.println("========================");
    Serial.println("ESP32 RTC闹钟系统已启动");
    Serial.println("设备名称: FBL-BedLight-Switch");
    Serial.println("支持的命令:");
    Serial.println("  基础命令:");
    Serial.println("    RELAY_ON, RELAY_OFF, RELAY_TOGGLE");
    Serial.println("    GET_STATUS, RESTART");
    Serial.println("  时间命令:");
    Serial.println("    TIME_SEND - 发送当前时间");
    Serial.println("    TIME_RECV - 请求设置时间");
    Serial.println("    SET_TIME=HH,MM,SS - 设置时间");
    Serial.println("    GET_TIME - 获取时间");
    Serial.println("  闹钟命令:");
    Serial.println("    ADD_ALARM=HH,MM,SS,TASK - 添加闹钟");
    Serial.println("    GET_ALARMS - 获取闹钟列表");
    Serial.println("    ENABLE_ALARM=ID,STATE - 启用/禁用闹钟");
    Serial.println("    DELETE_ALARM=ID - 删除闹钟");
    Serial.println("    CLEAR_ALARMS - 清除所有闹钟");
    Serial.println("========================");
}

void loop() {
    // 更新RTC时间
    updateSimpleRTC();
    
    // 检查并执行闹钟任务
    checkAndExecuteAlarms();
    
    // 从串口读取数据并发送到蓝牙
    if (Serial.available()) {
        String input = Serial.readStringUntil('\n');
        input.trim();
        
        if (input.length() > 0) {
            uint8_t data[MAX_DATA_LENGTH];
            uint8_t data_len = min(input.length(), (unsigned int)MAX_DATA_LENGTH);
            
            for (uint8_t i = 0; i < data_len; i++) {
                data[i] = input[i];
            }
            
            sendDataFrame(data, data_len);
            Serial.print("发送: ");
            Serial.println(input);
        }
    }
    
    // 从蓝牙读取数据
    while (SerialBT.available()) {
        uint8_t byte = SerialBT.read();
        
        // 简化处理：直接调用帧处理函数
        if (processReceivedData(byte)) {
            // 帧处理逻辑
            uint8_t dataLength = rx_buffer[2];
            uint8_t* data_ptr = &rx_buffer[3];
            uint8_t received_checksum = rx_buffer[3 + dataLength];
            
            // 计算校验和
            uint8_t calculated_checksum = 0;
            for (uint8_t i = 0; i < dataLength; i++) {
                calculated_checksum += data_ptr[i];
            }
            
            if (calculated_checksum == received_checksum) {
                handleTaskRun(data_ptr, dataLength);
            } else {
                Serial.println("校验和错误");
            }
            
            // 重置接收状态
            rx_index = 0;
            frame_started = false;
            expected_length = 0;
        }
    }
    
    delay(10);
}

// 处理接收数据
bool processReceivedData(uint8_t byte) {
    // 等待帧头
    if (!frame_started) {
        if (byte == FRAME_HEADER_1) {
            rx_buffer[0] = byte;
            rx_index = 1;
        } else if (rx_index == 1 && byte == FRAME_HEADER_2) {
            rx_buffer[1] = byte;
            rx_index = 2;
            frame_started = true;
        } else {
            rx_index = 0;
        }
        return false;
    }
    
    // 接收数据
    rx_buffer[rx_index++] = byte;
    
    // 如果是第3个字节，获取长度
    if (rx_index == 3) {
        expected_length = byte;
        if (expected_length > MAX_DATA_LENGTH) {
            rx_index = 0;
            frame_started = false;
            return false;
        }
    }
    
    // 检查是否完整
    if (rx_index >= 4 && expected_length > 0) {
        uint8_t total_length = 4 + expected_length;
        if (rx_index == total_length) {
            return true;
        }
    }
    
    return false;
}