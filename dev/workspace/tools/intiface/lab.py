#!/usr/bin/env python3
"""Real Intiface Engine, recording virtual hardware, and a fault-injection proxy."""
import argparse
import asyncio
import json
import pathlib
import re
import signal
import shutil
import subprocess
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

from websockets.asyncio.client import connect
from websockets.asyncio.server import serve
from websockets.exceptions import ConnectionClosed

WORKSPACE = pathlib.Path(__file__).resolve().parents[2]
EVENTS = []
MODE = "normal"
CLIENTS = set()
DEVICE_TASKS = {}
LAB_LOOP = None
DEVICE_PORT = None
EVENT_LOCK = threading.Lock()
EVENT_FILE = None
VALID_MODES = {"normal", "no-devices", "reject-next-scalar", "timeout-next-scalar", "disconnect-next-scalar", "delay-next-scalar"}


def record(kind, **fields):
    event = {"time": time.monotonic(), "kind": kind, **fields}
    with EVENT_LOCK:
        EVENTS.append(event)
        if len(EVENTS) > 10000:
            del EVENTS[:1000]
        if EVENT_FILE:
            EVENT_FILE.write(json.dumps(event) + "\n")
            EVENT_FILE.flush()
    if kind in {"device_command", "fault", "client_connected", "client_disconnected"}:
        print(json.dumps(event), flush=True)


class Control(BaseHTTPRequestHandler):
    def do_GET(self):
        if self.path == "/health":
            self.reply(200, {"lab": "poseguard-intiface", "mode": MODE})
        elif self.path == "/events":
            with EVENT_LOCK:
                events = list(EVENTS)
            self.reply(200, events)
        else:
            self.reply(404, {"error": "unknown endpoint"})

    def do_POST(self):
        global MODE
        if self.path == "/reset":
            asyncio.run_coroutine_threadsafe(restore_devices(), LAB_LOOP).result(timeout=5)
            with EVENT_LOCK:
                EVENTS.clear()
            MODE = "normal"
        elif self.path == "/fault":
            try:
                data = json.loads(self.rfile.read(int(self.headers.get("Content-Length", "0"))))
                mode = data["mode"]
                if mode not in VALID_MODES:
                    raise ValueError("unknown fault mode")
            except (ValueError, KeyError) as error:
                self.reply(400, {"error": str(error)})
                return
            MODE = mode
            record("fault", mode=MODE)
        elif self.path == "/device":
            try:
                data = json.loads(self.rfile.read(int(self.headers.get("Content-Length", "0"))))
                if data["device"] not in {"A", "B"} or not isinstance(data["connected"], bool):
                    raise ValueError("device A/B and boolean connected required")
                asyncio.run_coroutine_threadsafe(set_device(data["device"], data["connected"]), LAB_LOOP).result(timeout=5)
            except (ValueError, KeyError) as error:
                self.reply(400, {"error": str(error)})
                return
        else:
            self.reply(404, {"error": "unknown endpoint"})
            return
        self.reply(200, {"mode": MODE})

    def reply(self, status, value):
        payload = json.dumps(value).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(payload)))
        self.end_headers()
        self.wfile.write(payload)

    def log_message(self, *args):
        pass


async def virtual_device(device_port, device):
    async with connect(f"ws://127.0.0.1:{device_port}", ping_interval=None) as socket:
        await socket.send(json.dumps({"identifier": f"poseguard-test-{device.lower()}", "address": f"poseguard-virtual-{device}", "version": 0}))
        record("device_connected", device=device)
        async for payload in socket:
            text = payload.decode() if isinstance(payload, bytes) else payload
            record("device_rx", device=device, payload=text)
            for line in text.splitlines():
                match = re.fullmatch(r"V(\d)(\d+)", line)
                if match:
                    record("device_command", device=device, motor=int(match[1]), value=int(match[2]))


async def set_device(device, connected):
    task = DEVICE_TASKS.get(device)
    if connected and (task is None or task.done()):
        DEVICE_TASKS[device] = asyncio.create_task(virtual_device(DEVICE_PORT, device))
        await asyncio.sleep(0.2)
        if DEVICE_TASKS[device].done():
            await DEVICE_TASKS[device]
    elif not connected and task is not None:
        task.cancel()
        await asyncio.gather(task, return_exceptions=True)
        DEVICE_TASKS.pop(device, None)
        record("device_disconnected", device=device)


async def restore_devices():
    for device in ("A", "B"):
        await set_device(device, True)


async def proxy(client, backend_port):
    global MODE
    CLIENTS.add(client)
    record("client_connected")
    try:
        async with connect(f"ws://127.0.0.1:{backend_port}", ping_interval=None) as upstream:
            async def to_engine():
                global MODE
                async for payload in client:
                    messages = json.loads(payload)
                    record("client_rx", messages=messages)
                    for message in messages:
                        scalar = message.get("ScalarCmd")
                        if scalar and MODE in {"reject-next-scalar", "timeout-next-scalar", "disconnect-next-scalar", "delay-next-scalar"}:
                            mode, MODE = MODE, "normal"
                            record("fault_applied", mode=mode, id=scalar["Id"])
                            if mode == "reject-next-scalar":
                                await client.send(json.dumps([{"Error": {"Id": scalar["Id"], "ErrorCode": 2, "ErrorMessage": "PoseGuard injected command rejection"}}]))
                            elif mode == "disconnect-next-scalar":
                                await client.close(code=1011, reason="PoseGuard injected disconnect")
                            elif mode == "delay-next-scalar":
                                await asyncio.sleep(0.4)
                                await upstream.send(payload)
                            break
                    else:
                        await upstream.send(payload)

            async def to_client():
                async for payload in upstream:
                    messages = json.loads(payload)
                    record("server_rx", messages=messages)
                    if MODE == "no-devices":
                        messages = [message for message in messages if "DeviceAdded" not in message]
                        for message in messages:
                            if "DeviceList" in message:
                                message["DeviceList"]["Devices"] = []
                    if messages:
                        await client.send(json.dumps(messages))

            tasks = [asyncio.create_task(to_engine()), asyncio.create_task(to_client())]
            try:
                done, pending = await asyncio.wait(tasks, return_when=asyncio.FIRST_COMPLETED)
                for task in pending:
                    task.cancel()
                await asyncio.gather(*tasks, return_exceptions=True)
            finally:
                for task in tasks:
                    task.cancel()
    except (ConnectionClosed, OSError) as error:
        record("connection_closed", detail=str(error))
    finally:
        CLIENTS.discard(client)
        record("client_disconnected")


async def wait_port(port, engine):
    for _ in range(100):
        if engine.poll() is not None:
            raise RuntimeError("Intiface Engine exited; check engine.log")
        try:
            reader, writer = await asyncio.open_connection("127.0.0.1", port)
            writer.close()
            await writer.wait_closed()
            return
        except OSError:
            await asyncio.sleep(0.05)
    raise TimeoutError(f"Intiface port {port} did not become ready")


async def run_lab(args):
    global EVENT_FILE, LAB_LOOP, DEVICE_PORT
    output = pathlib.Path(args.output)
    output.mkdir(parents=True, exist_ok=True)
    stop = asyncio.Event()
    loop = asyncio.get_running_loop()
    LAB_LOOP, DEVICE_PORT = loop, args.device_port
    for sig in (signal.SIGTERM, signal.SIGINT):
        loop.add_signal_handler(sig, stop.set)
    control = ThreadingHTTPServer(("127.0.0.1", args.control_port), Control)
    engine_log = (output / "engine.log").open("w")
    EVENT_FILE = (output / "events.jsonl").open("w")
    config_path = output / "device-config.json"
    shutil.copyfile(pathlib.Path(__file__).with_name("device-config.json"), config_path)
    engine = subprocess.Popen([
        str(WORKSPACE / ".tools/intiface-engine/intiface-engine"),
        "--websocket-port", str(args.backend_port),
        "--use-device-websocket-server", "--device-websocket-server-port", str(args.device_port),
        "--user-device-config-file", str(config_path),
        "--server-name", "PoseGuard Test Intiface", "--log", "info",
    ], stdout=engine_log, stderr=subprocess.STDOUT)
    control_started = False
    try:
        await wait_port(args.backend_port, engine)
        await wait_port(args.device_port, engine)
        await restore_devices()
        async with serve(lambda socket: proxy(socket, args.backend_port), "127.0.0.1", args.port, ping_interval=None):
            threading.Thread(target=control.serve_forever, daemon=True).start()
            control_started = True
            print(f"READY: ws://127.0.0.1:{args.port}, control http://127.0.0.1:{args.control_port}", flush=True)
            await stop.wait()
    finally:
        for task in DEVICE_TASKS.values():
            task.cancel()
        await asyncio.gather(*DEVICE_TASKS.values(), return_exceptions=True)
        if control_started:
            control.shutdown()
        control.server_close()
        engine.terminate()
        try:
            engine.wait(timeout=5)
        except subprocess.TimeoutExpired:
            engine.kill()
            engine.wait()
        EVENT_FILE.close()
        engine_log.close()


async def smoke(args):
    async with connect(f"ws://127.0.0.1:{args.port}") as socket:
        message_id = 0

        async def request(name, **fields):
            nonlocal message_id
            message_id += 1
            await socket.send(json.dumps([{name: {"Id": message_id, **fields}}]))
            while True:
                messages = json.loads(await asyncio.wait_for(socket.recv(), 5))
                for message in messages:
                    key, body = next(iter(message.items()))
                    if body.get("Id") == message_id:
                        assert key != "Error", body
                        return key, body

        key, info = await request("RequestServerInfo", ClientName="PoseGuard Lab Smoke", MessageVersion=3)
        assert key == "ServerInfo", (key, info)
        await request("StartScanning")
        key, devices = await request("RequestDeviceList")
        devices = [item for item in devices["Devices"] if item["DeviceName"].startswith("PoseGuard Test")]
        assert len(devices) == 2, devices
        for device in devices:
            motors = len(device["DeviceMessages"]["ScalarCmd"])
            assert motors == (2 if " A " in device["DeviceName"] else 1), device
            index = device["DeviceIndex"]
            await request("ScalarCmd", DeviceIndex=index, Scalars=[{"Index": motor, "Scalar": 0.6, "ActuatorType": "Vibrate"} for motor in range(motors)])
            await asyncio.sleep(0.1)
            await request("StopDeviceCmd", DeviceIndex=index)
            await asyncio.sleep(0.1)
        await request("StopScanning")
        print("PASS: protocol v3 handshake, two devices (2+1 motors), ScalarCmd, StopDeviceCmd")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("command", choices=["serve", "smoke"])
    parser.add_argument("--port", type=int, default=12345)
    parser.add_argument("--backend-port", type=int, default=12346)
    parser.add_argument("--device-port", type=int, default=54817)
    parser.add_argument("--control-port", type=int, default=8787)
    parser.add_argument("--output", default=str(WORKSPACE / "artifacts/intiface"))
    args = parser.parse_args()
    try:
        asyncio.run(run_lab(args) if args.command == "serve" else smoke(args))
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
