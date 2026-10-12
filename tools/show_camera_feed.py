"""从电脑摄像头采集完整画面，经 adb 转发的长连接送入模拟器 Debug 分析器，并在电脑上独立预览。

测距焦距由应用按固定视场角估算（16:9 取 65°，4:3 取约 51°），无需标定。

传输：adb forward tcp:N localabstract:eyeproject_camera_feed，一条长连接持续发送
「4 字节大端长度 + JPEG」帧（应用侧见 core/camera/DebugCameraFeed.kt）。
相比旧方案（每帧启动两个 adb 进程并写文件），电脑与模拟器两端的 CPU 占用都大幅下降。
"""

import argparse
import io
import socket
import struct
import subprocess
import sys
import threading
import time
import tkinter as tk
from collections import deque
from tkinter import ttk

import cv2
from PIL import Image, ImageOps, ImageTk


PACKAGE = "com.zkyjss.eyeproject"
SOCKET_NAME = "eyeproject_camera_feed"
SEND_FPS = 15
JPEG_QUALITY = 85


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--adb", required=True)
    parser.add_argument("--serial", required=True)
    args = parser.parse_args()
    prefix = [args.adb, "-s", args.serial]

    def adb(*command: str, input_bytes: bytes | None = None, timeout: int = 4) -> subprocess.CompletedProcess[bytes]:
        return subprocess.run(prefix + list(command), input=input_bytes, capture_output=True, timeout=timeout)

    forward = adb("forward", "tcp:0", f"localabstract:{SOCKET_NAME}")
    if forward.returncode:
        raise RuntimeError(forward.stderr.decode(errors="replace") or "无法建立 adb 端口转发")
    port = int(forward.stdout.decode().strip())

    camera = cv2.VideoCapture(0, cv2.CAP_DSHOW if sys.platform == "win32" else cv2.CAP_ANY)
    if not camera.isOpened():
        adb("forward", "--remove", f"tcp:{port}")
        raise RuntimeError("无法打开电脑摄像头；请检查是否被其他程序占用")
    # 更高分辨率让虹膜占更多像素，测距更稳；摄像头不支持时自动回落
    camera.set(cv2.CAP_PROP_FRAME_WIDTH, 1280)
    camera.set(cv2.CAP_PROP_FRAME_HEIGHT, 720)
    camera.set(cv2.CAP_PROP_BUFFERSIZE, 1)

    root = tk.Tk()
    root.title("EyeProject 前摄输入 · 调试预览")
    root.geometry("820x620")
    root.minsize(480, 360)
    root.configure(bg="#111827")

    picture = ttk.Label(root, anchor="center", text="等待电脑摄像头画面…")
    picture.pack(fill="both", expand=True, padx=10, pady=(10, 0))
    footer = ttk.Frame(root)
    footer.pack(fill="x", padx=10, pady=10)
    mirrored = tk.BooleanVar(value=True)
    ttk.Checkbutton(footer, text="镜像显示（前摄预览）", variable=mirrored).pack(side="left")
    status = ttk.Label(footer, text="正在连接模拟器…", anchor="w")
    status.pack(side="left", padx=(14, 0), fill="x", expand=True)
    lock = threading.Lock()
    stop = threading.Event()
    latest_frame: bytes | None = None
    latest_sequence = 0
    stream_error = ""
    frame_size = ""
    frame_times = deque(maxlen=30)

    def connect() -> socket.socket | None:
        try:
            sock = socket.create_connection(("127.0.0.1", port), timeout=3)
            sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
            return sock
        except OSError:
            return None

    def stream_camera() -> None:
        nonlocal latest_frame, latest_sequence, stream_error, frame_size
        sock: socket.socket | None = None
        interval = 1.0 / SEND_FPS
        next_send = 0.0
        try:
            while not stop.is_set():
                ok, frame = camera.read()
                if not ok:
                    with lock:
                        stream_error = "电脑摄像头读取失败"
                    time.sleep(0.1)
                    continue
                now = time.monotonic()
                if now < next_send:
                    continue  # 只取最新画面，按发送帧率节流
                next_send = now + interval
                encoded_ok, encoded = cv2.imencode(".jpg", frame, [cv2.IMWRITE_JPEG_QUALITY, JPEG_QUALITY])
                if not encoded_ok:
                    continue
                jpeg = encoded.tobytes()
                if sock is None:
                    sock = connect()
                    if sock is None:
                        with lock:
                                stream_error = "等待应用打开相机（进入首页开始监测或任一测试）"
                        time.sleep(0.5)
                        continue
                try:
                    sock.sendall(struct.pack(">I", len(jpeg)) + jpeg)
                except OSError:
                    # 应用还没开始监听或已关闭相机：稍后重连
                    sock.close()
                    sock = None
                    with lock:
                        stream_error = "等待应用打开相机（进入首页开始监测或任一测试）"
                    time.sleep(0.5)
                    continue
                with lock:
                    latest_frame = jpeg
                    latest_sequence += 1
                    frame_size = f"{frame.shape[1]}×{frame.shape[0]}"
                    frame_times.append(time.monotonic())
                    stream_error = ""
        finally:
            if sock is not None:
                sock.close()
            camera.release()

    worker = threading.Thread(target=stream_camera, name="camera-debug-stream", daemon=True)
    worker.start()
    photo = None
    shown_sequence = 0
    closed = False

    def refresh() -> None:
        nonlocal photo, shown_sequence
        if closed:
            return
        with lock:
            jpeg = latest_frame if latest_sequence != shown_sequence else None
            sequence = latest_sequence
            error = stream_error
            times = tuple(frame_times)
            size = frame_size
        if jpeg is not None:
            image = Image.open(io.BytesIO(jpeg))
            if mirrored.get():
                image = ImageOps.mirror(image)
            image.thumbnail((max(picture.winfo_width() - 20, 1), max(picture.winfo_height() - 20, 1)))
            photo = ImageTk.PhotoImage(image)
            picture.configure(image=photo, text="")
            shown_sequence = sequence
            fps = (len(times) - 1) / (times[-1] - times[0]) if len(times) > 1 else 0.0
            status.configure(text=f"已送入模拟器  {size}  ·  {fps:.1f} 帧/秒")
        elif error:
            status.configure(text=error)
        root.after(50, refresh)

    def cleanup() -> None:
        stop.set()
        worker.join(timeout=4)
        adb("forward", "--remove", f"tcp:{port}")

    def close() -> None:
        nonlocal closed
        closed = True
        try:
            cleanup()
        finally:
            root.destroy()

    root.protocol("WM_DELETE_WINDOW", close)
    root.after(50, refresh)
    try:
        root.mainloop()
    finally:
        if not closed:
            cleanup()


if __name__ == "__main__":
    main()
