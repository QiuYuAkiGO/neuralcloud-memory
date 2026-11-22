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
                    assert command["action"] == "send_private_msg" and command["params"]["user_id"] == 67890
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

    def ask(self, text, message_id):
        assert self.ready.wait(4), "OneBot login did not finish"
        self.socket.send(json.dumps({
            "post_type":"message", "message_type":"private", "sub_type":"friend",
            "self_id":12345, "user_id":67890, "message_id":message_id,
            "message":[{"type":"text","data":{"text":text}}]
        }, ensure_ascii=False))
        return self.outbound.get(timeout=6)

    def close(self):
        self.server.shutdown()
