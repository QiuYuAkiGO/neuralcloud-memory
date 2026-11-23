"""面向正式 Harness OpenQQ 渠道的本地回环 OneBot WebSocket 挡板。

用于端到端验收私聊与群聊（@机器人）消息入站、登录握手（get_login_info）以及外发回复确认，
不依赖外部真实 QQ 网关，同时保持协议鉴权、机器人账号和发件人校验与真实渠道一致。
"""
import json
import queue
import threading

from websockets.exceptions import ConnectionClosed
from websockets.sync.server import serve


class OneBot:
    """本地 OneBot v11 回环服务端，在后台守护线程中接受 Harness 的 WebSocket 连接。"""

    def __init__(self, token):
        self.token = token
        self.socket = None
        # 登录握手（get_login_info 返回）完成后置位，外部用例据此等待渠道就绪。
        self.ready = threading.Event()
        self.outbound = queue.Queue()
        self.next_id = 7000
        self.peer = 67890
        self.expected_peer = self.peer
        self.deliveries = []
        self.server = serve(self.handle, "127.0.0.1", 0)
        self.port = self.server.socket.getsockname()[1]
        threading.Thread(target=self.server.serve_forever, daemon=True).start()

    def handle(self, socket):
        """校验 Bearer 凭据并循环应答 Harness 发来的 OneBot 动作帧（登录信息/私聊/群聊发送）。"""
        if socket.request.headers.get("Authorization") != "Bearer " + self.token:
            return
        try:
            for frame in socket:
                command = json.loads(frame)
                if command["action"] == "get_login_info":
                    data = {"user_id": 12345}
                else:
                    # 校验外发目标必须与当前测试期望的私聊对象或白名单测试群匹配，防止跨会话误投。
                    if command["action"] == "send_private_msg":
                        assert command["params"]["user_id"] == self.expected_peer
                    else:
                        assert command["action"] == "send_group_msg" and command["params"]["group_id"] == 23456
                    self.deliveries.append(command)
                    self.next_id += 1
                    data = {"message_id": self.next_id}
                socket.send(json.dumps({"status":"ok", "retcode":0, "data":data, "echo":command["echo"]}))
                if command["action"] == "get_login_info":
                    self.socket = socket
                    self.ready.set()
                else:
                    self.outbound.put(command["params"]["message"][0]["data"]["text"])
        except ConnectionClosed:
            pass
        finally:
            if self.socket is socket:
                self.ready.clear()

    def ask(self, text, message_id, *, group=False, sender=None, timeout=6):
        """向 Harness 注入一条私聊或群聊 @机器人 消息，并阻塞等待本轮外发回复正文。"""
        assert self.ready.wait(4), "OneBot login did not finish"
        self.expected_peer = sender or self.peer
        event = dict(post_type="message", message_type="group" if group else "private", sub_type="normal" if group else "friend",
                     self_id=12345, user_id=sender or self.peer, message_id=message_id,
                     message=[{"type":"text","data":{"text":text}}])
        if group:
            # 群聊消息须在首段附带 @机器人，才会被 Harness 的 OpenQQ 适配器纳入会话。
            event["group_id"] = 23456
            event["message"].insert(0, {"type":"at","data":{"qq":"12345"}})
        self.socket.send(json.dumps(event, ensure_ascii=False))
        return self.outbound.get(timeout=timeout)

    def close(self):
        """关闭本地 OneBot WebSocket 服务。"""
        self.server.shutdown()
