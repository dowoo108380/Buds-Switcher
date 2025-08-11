# odyssey_ultimate.py
import socket
import threading
import time
import json
import subprocess
import pyautogui

# --- 설정 ---
TARGET_DEVICE_NAME = "재훈의 Buds FE"
HOST = '0.0.0.0'
PORT = 65432

# --- 전역 변수 ---
android_status = "idle"


# --- 헬퍼 함수 및 네트워크 모듈 (이전과 동일) ---
def connect_buds_to_pc_with_powershell():
    print("\n--- [액션 시작: PowerShell 네이티브 연결] ---")
    script = f"""
    $ErrorActionPreference = "SilentlyContinue"
    $headphonesName = "{TARGET_DEVICE_NAME}"
    $headphonePnpDevices = Get-PnpDevice -Class Bluetooth | Where-Object {{ $_.Name -like "*$headphonesName*" }}
    if ($headphonePnpDevices) {{
        $headphonePnpDevices | Disable-PnpDevice -Confirm:$false
        Start-Sleep -Milliseconds 500
        $headphonePnpDevices | Enable-PnpDevice -Confirm:$false
        Write-Host "성공: 연결 명령을 모두 전송했습니다."
    }} else {{ Write-Host "오류: '$headphonesName' 관련 장치를 찾지 못했습니다." }}
    """
    try:
        result = subprocess.run(["powershell", "-Command", script], capture_output=True, text=True, check=False,
                                creationflags=subprocess.CREATE_NO_WINDOW)
        # print(f"[PowerShell 출력]:\n{result.stdout.strip()}") # 디버깅 시 주석 해제
        print("--- [액션 완료] ---")
    except Exception as e:
        print(f"--- [액션 실패: {e}] ---")


def get_pc_audio_state():
    try:
        from pycaw.pycaw import AudioUtilities, IAudioMeterInformation
        sessions = AudioUtilities.GetAllSessions()
        for session in sessions:
            try:
                meter = session._ctl.QueryInterface(IAudioMeterInformation)
                if not session.SimpleAudioVolume.GetMute() and meter.GetPeakValue() > 0.001:
                    if session.Process: return "playing"
            except:
                continue
        return "idle"
    except:
        return "idle"


def start_server():
    server = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    server.bind((HOST, PORT))
    server.listen()
    while True:
        conn, addr = server.accept()
        threading.Thread(target=handle_client, args=(conn, addr), daemon=True).start()


def handle_client(conn, addr):
    global android_status
    while True:
        try:
            data = conn.recv(1024)
            if not data: break
            message = json.loads(data.decode('utf-8'))
            if message.get('source') == 'android': android_status = message.get('status', 'idle')
        except:
            break
    android_status = "idle"


# --- 최종 메인 로직 (상태 전환 기반) ---
def main():
    threading.Thread(target=start_server, daemon=True).start()
    print("===== Project Odyssey (Ultimate): Buds Switcher Agent 시작 =====")

    # 각 디바이스의 '이전' 상태를 저장
    last_pc_state = 'idle'
    last_android_state = 'idle'

    # 마지막으로 '재생 시작'된 시간을 기록
    pc_last_started_time = 0.0
    android_last_started_time = 0.0

    current_controller = 'none'

    while True:
        try:
            # 1. 현재 상태 가져오기
            current_pc_state = get_pc_audio_state()
            current_android_state = android_status

            # 2. '재생 시작' 순간을 감지하고 타임스탬프 기록
            if current_pc_state == 'playing' and last_pc_state == 'idle':
                print("[상태 전환 감지] PC가 미디어 재생을 시작했습니다.")
                pc_last_started_time = time.time()

            if current_android_state != 'idle' and last_android_state == 'idle':
                print(f"[상태 전환 감지] 안드로이드가 활동을 시작했습니다. (상태: {current_android_state})")
                android_last_started_time = time.time()

            # 3. 우선순위 결정
            # [PC가 우선순위를 가져야 할 때]
            if pc_last_started_time > android_last_started_time and current_controller != 'pc':
                print("\n[우선순위 결정] PC가 제어권을 가져옵니다.")
                connect_buds_to_pc_with_powershell()
                current_controller = 'pc'
                time.sleep(10)  # 연결 안정화 대기

            # [안드로이드가 우선순위를 가져야 할 때]
            elif android_last_started_time > pc_last_started_time and current_controller != 'android':
                print(f"\n[우선순위 결정] 안드로이드가 제어권을 가져옵니다.")
                if current_controller == 'pc':
                    pyautogui.press('playpause')
                    print("[액션] PC 미디어 '재생/일시정지' 키를 전송했습니다.")
                current_controller = 'android'
                time.sleep(3)

            # 4. 다음 루프를 위해 현재 상태를 '이전' 상태로 저장
            last_pc_state = current_pc_state
            last_android_state = current_android_state

            time.sleep(1)
        except KeyboardInterrupt:
            break

    print("\n프로그램을 종료합니다.")


if __name__ == "__main__":
    main()