import asyncio
import base64
import gzip
import io
import os
import re
from datetime import datetime

from bleak import BleakScanner, BleakClient
from loguru import logger


async def scan_device() -> (str, int):
    logger.info("扫描中")
    while True:
        devices = await BleakScanner.discover()
        address = ''
        for d in devices:
            if d.name == '47L121000':
                logger.info(f"找到V3主机 {address}")
                return d.address, 3
            if d.name == 'D-LAB ESTIM01':
                logger.info(f"找到V2主机 {address}")
                return d.address, 2


def get_v3_uuid(short_uuid: str) -> str:
    return f"0000{short_uuid}-0000-1000-8000-00805f9b34fb"


def get_v2_uuid(short_uuid: str) -> str:
    return f"955a{short_uuid}-0fe2-f5aa-a094-84b8d4f3e8ad"


async def start(address: str, version, lines: list[str]):
    async with BleakClient(address) as client:
        service = lines[0].split("|")[1]
        if version == 3:
            if not service.startswith("0000"):
                logger.error("输入数据非v3主机")
            battery_level = await client.read_gatt_char(get_v3_uuid("1500"))
        else:
            if not service.startswith("955a"):
                logger.error("输入数据非v2主机")
            battery_level = await client.read_gatt_char(get_v2_uuid("1500"))
        logger.success(f"设备电量{int(battery_level[0])}%")
        for line in lines:
            logger.info(line)
            t, service, char, data = line.split("|")
            t = int(t)
            await asyncio.sleep(t / 1000.0)
            pre_t = t
            # 将Base64编码字符串解码成二进制数据
            binary_data = base64.b64decode(data)

            # 使用gzip解压缩二进制数据
            with gzip.GzipFile(fileobj=io.BytesIO(binary_data), mode='rb') as f:
                decompressed_data = f.read()

            if version == 3:
                uuid = f"{char}-0000-1000-8000-00805f9b34fb"
            else:
                uuid = f"{char}-0fe2-f5aa-a094-84b8d4f3e8ad"

            await client.write_gatt_char(uuid, decompressed_data)


def get_files(directory):
    files = os.listdir(directory)
    return files


def extract_info(filename):
    pattern = r"([a-f0-9]+)_(\d{8})_(\d{6})\.txt"
    match = re.match(pattern, filename)
    if match:
        file_id = match.group(1)
        date_str = match.group(2) + match.group(3)
        date_obj = datetime.strptime(date_str, "%Y%m%d%H%M%S")
        return file_id, date_obj
    else:
        return filename, None


def sort_files(files):
    valid_files = []
    invalid_files = []

    for file in files:
        file_info = extract_info(file)
        if file_info[1]:
            valid_files.append((file, file_info[1]))
        else:
            invalid_files.append((file, None))

    valid_files.sort(key=lambda x: x[1], reverse=True)

    # 返回文件名列表，先无效文件后有效文件
    return [file[0] for file in invalid_files + valid_files]


async def main():
    directory = "dglab-record"
    files = get_files(directory)
    sorted_files = sort_files(files)

    for index, filename in enumerate(sorted_files):
        file_info = extract_info(filename)
        if file_info[1]:
            print(f"index: {index}, accid: {file_info[0]}, 时间: {file_info[1].strftime('%Y-%m-%d %H:%M')}")
        else:
            print(f"index: {index}, 文件名: {filename}")

    user_input = int(input("输入index："))
    filename = sorted_files[user_input]

    address, version = await scan_device()
    with open(os.path.join(directory, filename), 'r') as f:
        content = f.read()
    lines = content.splitlines()
    await start(address, version, lines)


asyncio.run(main())
