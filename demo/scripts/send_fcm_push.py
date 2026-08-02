#!/usr/bin/env python3
"""
向 demo 设备发送 FCM data 消息驱动 LiveKit 实时活动。

用法：
  pip install google-auth requests
  python send_fcm_push.py <FCM_TOKEN>

前置：把你的 Firebase 项目服务账号 JSON 路径设到环境变量
  export GOOGLE_APPLICATION_CREDENTIALS=/path/to/service-account.json
（服务账号在 Firebase 控制台 → 项目设置 → 服务账号 → 生成新私钥）

消息体走 data 的 "livekit" 键，内容是标准 LiveKit JSON 外壳。
默认演示：发送一个 enroute 阶段的外卖 UPDATE，触发卡片更新与交互按钮。
"""
import json
import os
import sys
import time

import google.auth.transport.requests
import google.oauth2.service_account
import requests

SCOPES = ["https://www.googleapis.com/auth/cloud-platform"]
FCM_ENDPOINT = "https://fcm.googleapis.com/v1/projects/{}/messages:send"


def get_access_token():
    creds = google.oauth2.service_account.Credentials.from_service_account_file(
        os.environ["GOOGLE_APPLICATION_CREDENTIALS"], scopes=SCOPES
    )
    creds.refresh(google.auth.transport.requests.Request())
    return creds.token, creds.project_id


def send(token, project_id, livekit_payload):
    body = {
        "message": {
            "token": token,
            "data": {"livekit": json.dumps(livekit_payload, ensure_ascii=False)},
        }
    }
    url = FCM_ENDPOINT.format(project_id)
    resp = requests.post(
        url,
        headers={
            "Authorization": f"Bearer {get_access_token()[0]}",
            "Content-Type": "application/json; UTF-8",
        },
        data=json.dumps(body),
    )
    print(f"HTTP {resp.status_code}: {resp.text}")
    return resp.ok


if __name__ == "__main__":
    if len(sys.argv) < 2:
        print(__doc__)
        sys.exit(1)

    fcm_token = sys.argv[1]
    # 演示用：骑手出发阶段。改 stage/progress 可推进进度流。
    payload = {
        "protocol_version": 1,
        "biz_type": "food",
        "activity_id": "fcm-1",
        "action": "START",
        "template_id": "delivery-live",
        "seq_id": int(time.time()),
        "timestamp": int(time.time() * 1000),
        "payload": {"stage": "enroute", "progress": 50},
    }
    ok = send(fcm_token, get_access_token()[1], payload)
    sys.exit(0 if ok else 1)
