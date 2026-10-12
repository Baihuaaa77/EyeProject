"""从电脑摄像头采集完整画面，送入模拟器 Debug 分析器并独立预览。"""

import argparse
import io
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
FRAME = "files/camera_debug_frame.jpg"
INPUT = "files/camera_debug_input.jpg"
INPUT_TEMP = "files/camera_debug_input.tmp"
FOCAL_SCALE = "files/camera_debug_focal_scale.txt"
SWITCH = "files/camera_debug.enable"


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--adb", required=True)
    parser.add_argument("--serial", required=True)
    args = parser.parse_args()
    prefix = [args.adb, "-s", args.serial]

    def adb(*command: str, input_bytes: bytes | None = None, timeout: int = 4) -> subprocess.CompletedProcess[bytes]:
        return subprocess.run(prefix + list(command), input=input_bytes, capture_output=True, timeout=timeout)

    setup = adb("shell", "run-as", PACKAGE, "mkdir", "-p", "files")
    if setup.returncode:
        raise RuntimeError(setup.stderr.decode(errors="replace") or "无法访问 Debug 应用数据目录")
    adb("shell", "run-as", PACKAGE, "rm", "-f", FRAME, INPUT, INPUT_TEMP)
    enabled = adb("shell", "run-as", PACKAGE, "touch", SWITCH)
    if enabled.returncode:
        raise RuntimeError(enabled.stderr.decode(errors="replace") or "无法开启电脑摄像头输入")

    camera = cv2.VideoCapture(0, cv2.CAP_DSHOW if sys.platform == "win32" else cv2.CAP_ANY)
    if not camera.isOpened():
        adb("shell", "run-as", PACKAGE, "rm", "-f", SWITCH)
        raise RuntimeError("无法打开电脑摄像头；请检查是否被其他程序占用")
    camera.set(cv2.CAP_PROP_FRAME_WIDTH, 640)
    camera.set(cv2.CAP_PROP_FRAME_HEIGHT, 480)
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
    status = ttk.Label(footer, text="正在向模拟器发送电脑摄像头画面", anchor="w")
    status.pack(side="left", padx=(14, 0), fill="x", expand=True)
    calibration = ttk.Frame(root)
    calibration.pack(fill="x", padx=10, pady=(0, 10))
    ttk.Label(calibration, text="眼睛到镜头实测").pack(side="left")
    actual_text = tk.StringVar()
    ttk.Entry(calibration, textvariable=actual_text, width=6).pack(side="left", padx=(5, 2))
    ttk.Label(calibration, text="cm；App 显示").pack(side="left")
    shown_text = tk.StringVar()
    ttk.Entry(calibration, textvariable=shown_text, width=6).pack(side="left", padx=(5, 2))
    ttk.Label(calibration, text="cm").pack(side="left")
    calibration_note = ttk.Label(calibration, text="电脑摄像头距离需要一次实测标定")
    calibration_note.pack(side="right")

    def calibrate() -> None:
        try:
            actual = float(actual_text.get())
            shown = float(shown_text.get())
            if not (5 <= actual <= 300 and 5 <= shown <= 300):
                raise ValueError("距离须在 5–300 cm")
            current = adb("exec-out", "run-as", PACKAGE, "cat", FOCAL_SCALE)
            old_scale = float(current.stdout.decode().strip()) if current.returncode == 0 else 1.0
            scale = old_scale * actual / shown
            if not 0.2 <= scale <= 5:
                raise ValueError("校正倍数超出 0.2–5 倍，请复核数值")
            saved = adb("exec-in", "run-as", PACKAGE, "sh", "-c", f"cat > {FOCAL_SCALE}",
                        input_bytes=f"{scale:.6f}\n".encode(), timeout=3)
            if saved.returncode:
                raise RuntimeError(saved.stderr.decode(errors="replace") or "保存失败")
            calibration_note.configure(text=f"已保存校正倍数 ×{scale:.3f}")
        except (ValueError, OSError, subprocess.TimeoutExpired, RuntimeError) as error:
            calibration_note.configure(text=f"标定失败：{error}")

    ttk.Button(calibration, text="校准距离", command=calibrate).pack(side="left", padx=(8, 0))
    previous = adb("exec-out", "run-as", PACKAGE, "cat", FOCAL_SCALE)
    if previous.returncode == 0:
        calibration_note.configure(text=f"已有校正倍数 ×{previous.stdout.decode(errors='replace').strip()}")

    lock = threading.Lock()
    stop = threading.Event()
    latest_frame: bytes | None = None
    latest_sequence = 0
    stream_error = ""
    frame_times = deque(maxlen=30)

    def stream_camera() -> None:
        nonlocal latest_frame, latest_sequence, stream_error
        try:
            while not stop.is_set():
                ok, frame = camera.read()
                if not ok:
                    with lock:
                        stream_error = "电脑摄像头读取失败"
                    time.sleep(0.1)
                    continue
                encoded_ok, encoded = cv2.imencode(".jpg", frame, [cv2.IMWRITE_JPEG_QUALITY, 80])
                if not encoded_ok:
                    continue
                jpeg = encoded.tobytes()
                sent = adb("exec-in", "run-as", PACKAGE, "sh", "-c",
                           f"cat > {INPUT_TEMP}", input_bytes=jpeg, timeout=3)
                if sent.returncode == 0:
                    sent = adb("shell", "run-as", PACKAGE, "mv", INPUT_TEMP, INPUT, timeout=3)
                if sent.returncode:
                    with lock:
                        stream_error = sent.stderr.decode(errors="replace") or "无法把画面送入模拟器"
                    time.sleep(0.1)
                    continue
                with lock:
                    latest_frame = jpeg
                    latest_sequence += 1
                    frame_times.append(time.monotonic())
                    stream_error = ""
        except (OSError, subprocess.TimeoutExpired) as error:
            with lock:
                stream_error = str(error)
        finally:
            camera.release()

    worker = threading.Thread(target=stream_camera, name="camera-debug-stream", daemon=True)
    worker.start()
    photo = None
    shown_sequence = 0
    last_heartbeat = time.monotonic()
    closed = False

    def refresh() -> None:
        nonlocal photo, shown_sequence, last_heartbeat
        if closed:
            return
        if time.monotonic() - last_heartbeat > 2:
            adb("shell", "run-as", PACKAGE, "touch", SWITCH)
            last_heartbeat = time.monotonic()
        with lock:
            jpeg = latest_frame if latest_sequence != shown_sequence else None
            sequence = latest_sequence
            error = stream_error
            times = tuple(frame_times)
        if jpeg is not None:
            image = Image.open(io.BytesIO(jpeg))
            if mirrored.get():
                image = ImageOps.mirror(image)
            image.thumbnail((max(picture.winfo_width() - 20, 1), max(picture.winfo_height() - 20, 1)))
            photo = ImageTk.PhotoImage(image)
            picture.configure(image=photo, text="")
            shown_sequence = sequence
            fps = (len(times) - 1) / (times[-1] - times[0]) if len(times) > 1 else 0.0
            status.configure(text=f"已送入模拟器  {image.width}×{image.height}  ·  {fps:.1f} 帧/秒")
        elif error:
            status.configure(text=f"摄像头输入失败：{error}")
        root.after(40, refresh)

    def close() -> None:
        nonlocal closed
        closed = True
        stop.set()
        worker.join(timeout=4)
        try:
            adb("shell", "run-as", PACKAGE, "rm", "-f", SWITCH, FRAME, INPUT, INPUT_TEMP)
        finally:
            root.destroy()

    root.protocol("WM_DELETE_WINDOW", close)
    root.after(40, refresh)
    try:
        root.mainloop()
    finally:
        if not closed:
            stop.set()
            worker.join(timeout=4)
            adb("shell", "run-as", PACKAGE, "rm", "-f", SWITCH, FRAME, INPUT, INPUT_TEMP)


if __name__ == "__main__":
    main()
