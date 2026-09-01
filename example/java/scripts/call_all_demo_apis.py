from __future__ import annotations

import argparse
import json
import sys
import urllib.error
import urllib.request


CALLS = [
    ("GET /api/demo/ping", "GET", "/api/demo/ping", None),
    ("POST /api/demo/initialize - VNA slot 1", "POST", "/api/demo/initialize", {"instType": "VNA", "slotId": 1}),
    ("POST /api/demo/initialize - VNA slot 2", "POST", "/api/demo/initialize", {"instType": "VNA", "slotId": 2}),
    ("POST /api/demo/initialize - SA slot 1", "POST", "/api/demo/initialize", {"instType": "SA", "slotId": 1}),
    ("POST /api/demo/initialize - SA slot 2", "POST", "/api/demo/initialize", {"instType": "SA", "slotId": 2}),
    ("POST /api/demo/initialize - DMM slot 2", "POST", "/api/demo/initialize", {"instType": "DMM", "slotId": 2}),
    ("POST /api/demo/initialize - DMM slot 3", "POST", "/api/demo/initialize", {"instType": "DMM", "slotId": 3}),
    ("POST /api/demo/initialize - PSU slot 4", "POST", "/api/demo/initialize", {"instType": "PSU", "slotId": 4}),
    ("POST /api/demo/initialize - OSC slot 5", "POST", "/api/demo/initialize", {"instType": "OSC", "slotId": 5}),
    (
        "POST /api/demo/control - VNA start sample 1",
        "POST",
        "/api/demo/control",
        {"instType": "VNA", "cmdName": "start", "slotId": 1, "params": {"mode": "AUTO", "durationMs": 1000, "operator": "curl-demo"}},
    ),
    (
        "POST /api/demo/control - VNA start sample 2",
        "POST",
        "/api/demo/control",
        {"instType": "VNA", "cmdName": "start", "slotId": 1, "params": {"mode": "MANUAL", "durationMs": 1500, "operator": "curl-demo", "trace": "S11"}},
    ),
    (
        "POST /api/demo/control - VNA start sample 3",
        "POST",
        "/api/demo/control",
        {"instType": "VNA", "cmdName": "start", "slotId": 2, "params": {"mode": "MANUAL", "durationMs": 1500, "operator": "curl-demo", "trace": "S11"}},
    ),
    (
        "POST /api/demo/control - VNA start sample 4",
        "POST",
        "/api/demo/control",
        {"instType": "VNA", "cmdName": "start", "slotId": 1, "params": {"mode": "AUTO", "durationMs": 2000, "operator": "curl-demo", "trace": "S21", "powerDbm": -10}},
    ),
    (
        "POST /api/demo/control - VNA calibrate",
        "POST",
        "/api/demo/control",
        {"instType": "VNA", "cmdName": "calibrate", "slotId": 1, "params": {"kit": "SOLT", "ports": [1, 2], "temperatureC": 25.4}},
    ),
    (
        "POST /api/demo/control - SA measure sample 1",
        "POST",
        "/api/demo/control",
        {"instType": "SA", "cmdName": "measure", "slotId": 2, "params": {"frequencyHz": 1000000000, "spanHz": 10000000, "points": 201}},
    ),
    (
        "POST /api/demo/control - SA measure sample 2",
        "POST",
        "/api/demo/control",
        {"instType": "SA", "cmdName": "measure", "slotId": 2, "params": {"frequencyHz": 2400000000, "spanHz": 20000000, "points": 401, "detector": "PEAK"}},
    ),
    (
        "POST /api/demo/control - SA measure sample 3",
        "POST",
        "/api/demo/control",
        {"instType": "SA", "cmdName": "measure", "slotId": 2, "params": {"frequencyHz": 5800000000, "spanHz": 40000000, "points": 801, "detector": "RMS"}},
    ),
    (
        "POST /api/demo/control - DMM readVoltage sample 1",
        "POST",
        "/api/demo/control",
        {"instType": "DMM", "cmdName": "readVoltage", "slotId": 3, "params": {"range": "10V", "samples": 5, "nplc": 1}},
    ),
    (
        "POST /api/demo/control - DMM readVoltage sample 2",
        "POST",
        "/api/demo/control",
        {"instType": "DMM", "cmdName": "readVoltage", "slotId": 3, "params": {"range": "1V", "samples": 20, "nplc": 10}},
    ),
    (
        "POST /api/demo/control - PSU setOutput sample 1",
        "POST",
        "/api/demo/control",
        {"instType": "PSU", "cmdName": "setOutput", "slotId": 4, "params": {"channel": 1, "voltage": 3.3, "currentLimit": 0.5, "enabled": True}},
    ),
    (
        "POST /api/demo/control - PSU setOutput sample 2",
        "POST",
        "/api/demo/control",
        {"instType": "PSU", "cmdName": "setOutput", "slotId": 4, "params": {"channel": 2, "voltage": 5.0, "currentLimit": 1.2, "enabled": True}},
    ),
    (
        "POST /api/demo/control - OSC capture sample 1",
        "POST",
        "/api/demo/control",
        {"instType": "OSC", "cmdName": "capture", "slotId": 5, "params": {"channel": 1, "timebaseUs": 20, "trigger": "rising", "sampleRateHz": 1000000000}},
    ),
    (
        "POST /api/demo/control - OSC capture sample 2",
        "POST",
        "/api/demo/control",
        {"instType": "OSC", "cmdName": "capture", "slotId": 5, "params": {"channel": 2, "timebaseUs": 50, "trigger": "falling", "sampleRateHz": 500000000}},
    ),
    (
        "POST /api/demo/control - VNA stop",
        "POST",
        "/api/demo/control",
        {"instType": "VNA", "cmdName": "stop", "slotId": 1, "params": {"reason": "script-finished"}},
    ),
]


def invoke(base_url: str, title: str, method: str, path: str, body: object) -> None:
    print(f"\n=== {title} ===")
    payload = None
    headers = {}
    if body is not None:
        payload = json.dumps(body, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
        headers["Content-Type"] = "application/json"
    request = urllib.request.Request(base_url + path, data=payload, headers=headers, method=method)
    with urllib.request.urlopen(request, timeout=30) as response:
        sys.stdout.buffer.write(response.read())
        sys.stdout.write("\n")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("-BaseUrl", "--base-url", default="http://127.0.0.1:18622")
    args = parser.parse_args()
    base_url = args.base_url.rstrip("/")
    calls = list(CALLS)
    large_text = "breakhub-large-text-" * 4096
    calls.insert(
        14,
        (
            "POST /api/demo/control - VNA large text payload",
            "POST",
            "/api/demo/control",
            {
                "instType": "VNA",
                "cmdName": "largeText",
                "slotId": 1,
                "params": {
                    "scenario": "large-text-payload",
                    "text": large_text,
                    "repeatCount": 4096,
                    "expectedChars": len(large_text),
                },
            },
        ),
    )
    try:
        for call in calls:
            invoke(base_url, *call)
    except (OSError, urllib.error.URLError) as exc:
        print(f"[BreakHub] API call failed: {exc}", file=sys.stderr)
        return 1
    print("\nAll java-demo API calls finished.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
