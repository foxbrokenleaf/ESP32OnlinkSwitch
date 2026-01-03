#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
ESP32蓝牙数据帧生成器
只生成数据帧，不包含其他功能
"""

def calculate_checksum(data_bytes):
    """计算校验和（只对数据部分求和取模256）"""
    return sum(data_bytes) % 256

def create_frame(command_str):
    """
    创建数据帧
    格式: AA 55 [长度] [数据...] [校验和]
    """
    data_bytes = command_str.encode('utf-8')
    length = len(data_bytes)
    checksum = calculate_checksum(data_bytes)
    
    frame = bytearray()
    frame.append(0xAA)      # 帧头1
    frame.append(0x55)      # 帧头2
    frame.append(length)    # 数据长度
    frame.extend(data_bytes) # 数据
    frame.append(checksum)  # 校验和
    
    return frame

def format_hex(frame_bytes):
    """格式化帧为十六进制字符串"""
    return ' '.join([f'{b:02X}' for b in frame_bytes])

def format_bytes(frame_bytes):
    """格式化帧为Python bytes格式"""
    return ''.join([f'\\x{b:02X}' for b in frame_bytes])

# 所有命令定义
COMMANDS = {
    # 基础命令
    "RELAY_ON": "RELAY_ON",
    "RELAY_OFF": "RELAY_OFF",
    "RELAY_TOGGLE": "RELAY_TOGGLE",
    "GET_STATUS": "GET_STATUS",
    "RESTART": "RESTART",
    
    # 时间命令
    "TIME_SEND": "TIME_SEND",   #废弃
    "TIME_RECV": "TIME_RECV",   #废弃
    "SET_TIME_XX_XX_XX": "SET_TIME=03,07,00",
    "GET_TIME": "GET_TIME",
    
    # 闹钟命令
    "ADD_ALARM_XX_XX_XX_TASK": "ADD_ALARM=00,10,00,RELAY_ON",
    "GET_ALARMS": "GET_ALARMS",
    "ENABLE_ALARM_X_ENABLE/DISABLE": "ENABLE_ALARM=0,DISABLE",
    "DELETE_ALARM_X": "DELETE_ALARM=0",
    "CLEAR_ALARMS": "CLEAR_ALARMS",
}

# 生成所有数据帧
print("ESP32蓝牙数据帧列表")
print("=" * 80)

for cmd_name, cmd_str in COMMANDS.items():
    frame = create_frame(cmd_str)
    hex_str = format_hex(frame)
    bytes_str = format_bytes(frame)
    
    print(f"\n{cmd_name}:")
    print(f"  命令: '{cmd_str}'")
    print(f"  十六进制: {hex_str}")
    print(f"  Python bytes: b\"{bytes_str}\"")
    
    # 显示校验和计算
    data_bytes = cmd_str.encode('utf-8')
    checksum = calculate_checksum(data_bytes)
    print(f"  数据: {data_bytes.hex().upper()}")
    print(f"  校验和: 0x{checksum:02X}")

# 输出简洁版本
print("\n" + "=" * 80)
print("简洁版本（仅十六进制）：")
print("-" * 80)

for cmd_name, cmd_str in COMMANDS.items():
    frame = create_frame(cmd_str)
    hex_str = format_hex(frame)
    print(f"{cmd_name}: {hex_str}")

print("\n" + "=" * 80)
print("Python字典格式：")
print("-" * 80)

print("FRAMES = {")
for cmd_name, cmd_str in COMMANDS.items():
    frame = create_frame(cmd_str)
    bytes_str = format_bytes(frame)
    print(f'    "{cmd_name}": b"{bytes_str}",')
print("}")