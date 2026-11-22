"""A loopback OneBot peer for the official Harness private-message channel."""
import json
import queue
import threading

from websockets.exceptions import ConnectionClosed
from websockets.sync.server import serve


class OneBot:
    def __init__(self, token):
        self.token = token
        self.socket = None
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
        if socket.request.headers.get("Authorization") != "Bearer " + self.token:
            return
        try:
            for frame in socket:
                command = json.loads(frame)
                if command["action"] == "get_login_info":
                    data = {"user_id": 12345}
                else:
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
        assert self.ready.wait(4), "OneBot login did not finish"
        self.expected_peer = sender or self.peer
        event = dict(post_type="message", message_type="group" if group else "private", sub_type="normal" if group else "friend",
                     self_id=12345, user_id=sender or self.peer, message_id=message_id,
                     message=[{"type":"text","data":{"text":text}}])
        if group:
            event["group_id"] = 23456
            event["message"].insert(0, {"type":"at","data":{"qq":"12345"}})
        self.socket.send(json.dumps(event, ensure_ascii=False))
        return self.outbound.get(timeout=timeout)

    def close(self):
        self.server.shutdown()
