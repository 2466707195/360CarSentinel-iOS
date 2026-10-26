#!/usr/bin/env node
/**
 * 车载哨兵 · 远程观看中继服务
 * =============================================================================
 * 作用：让手机在任何网络下都能看到车机画面，而车机不需要公网 IP、不需要端口映射。
 *
 *   ┌────────┐   WSS（车机主动外连）   ┌──────────┐   HTTPS   ┌────────┐
 *   │  车机  │ ────────────────────► │  中继服务 │ ◄──────── │  手机  │
 *   └────────┘   房间号 + 密钥         └──────────┘           └────────┘
 *
 * 手机拿到的就是一个普通 HTTPS 地址，AVPlayer 可以直接拉 m3u8/ts —— 播放器那侧
 * 一行代码都不用改。这比推 RTMP/WebRTC 简单得多，也不需要转码和额外带宽
 * （视频字节流原样透传，中继只做转发）。
 *
 * 用法：
 *   node server.js                       # 默认 8787
 *   PORT=9000 RELAY_TOKEN=xxx node server.js
 *
 * 反向代理（Nginx / Caddy / Cloudflare）把 wss 和 https 都指到这个端口即可。
 * =============================================================================
 */

'use strict';

const http = require('http');
const crypto = require('crypto');

const PORT = parseInt(process.env.PORT || '8787', 10);
const REQUEST_TIMEOUT_MS = 30_000;
const MAX_BODY_BYTES = 32 * 1024 * 1024;
const PING_INTERVAL_MS = 25_000;

/** room -> { socket, key, pending: Map<id, {res, chunks, size, timer}>, nextId } */
const rooms = new Map();

// ---------------------------------------------------------------------------
// WebSocket 基础（RFC 6455，只实现需要的部分，不引任何依赖）
// ---------------------------------------------------------------------------

const WS_GUID = '258EAFA5-E914-47DA-95CA-C5AB0DC85B11';

function wsAccept(key) {
  return crypto.createHash('sha1').update(key + WS_GUID).digest('base64');
}

function encodeFrame(payload, opcode = 0x2) {
  const len = payload.length;
  let header;

  if (len < 126) {
    header = Buffer.alloc(2);
    header[1] = len;
  } else if (len < 65536) {
    header = Buffer.alloc(4);
    header[1] = 126;
    header.writeUInt16BE(len, 2);
  } else {
    header = Buffer.alloc(10);
    header[1] = 127;
    header.writeBigUInt64BE(BigInt(len), 2);
  }
  header[0] = 0x80 | opcode;
  return Buffer.concat([header, payload]);
}

/** 增量解析客户端发来的帧（客户端必须加掩码） */
function createParser(handlers) {
  let buf = Buffer.alloc(0);

  return function feed(chunk) {
    buf = Buffer.concat([buf, chunk]);

    for (;;) {
      if (buf.length < 2) return;

      const b0 = buf[0];
      const b1 = buf[1];
      const opcode = b0 & 0x0f;
      const masked = (b1 & 0x80) !== 0;
      let len = b1 & 0x7f;
      let offset = 2;

      if (len === 126) {
        if (buf.length < 4) return;
        len = buf.readUInt16BE(2);
        offset = 4;
      } else if (len === 127) {
        if (buf.length < 10) return;
        len = Number(buf.readBigUInt64BE(2));
        offset = 10;
      }

      let maskKey = null;
      if (masked) {
        if (buf.length < offset + 4) return;
        maskKey = buf.subarray(offset, offset + 4);
        offset += 4;
      }

      if (buf.length < offset + len) return;

      const payload = Buffer.from(buf.subarray(offset, offset + len));
      if (maskKey) {
        for (let i = 0; i < payload.length; i++) {
          payload[i] ^= maskKey[i % 4];
        }
      }
      buf = buf.subarray(offset + len);

      if (opcode === 0x8) {
        handlers.onClose();
        return;
      }
      if (opcode === 0x9) {
        handlers.onPing(payload);
        continue;
      }
      if (opcode === 0xa) continue;

      handlers.onData(payload);
    }
  };
}

// ---------------------------------------------------------------------------
// 帧类型（与 Android 端 RelayTunnel.kt 一一对应）
// ---------------------------------------------------------------------------

const T_REQ_HEAD = 0x01;
const T_REQ_BODY = 0x02;
const T_REQ_END = 0x03;
const T_RESP_HEAD = 0x04;
const T_RESP_BODY = 0x05;
const T_RESP_END = 0x06;
const T_ERROR = 0x07;

function packFrame(type, id, payload) {
  const head = Buffer.alloc(5 + payload.length);
  head[0] = type;
  head.writeUInt32BE(id >>> 0, 1);
  payload.copy(head, 5);
  return head;
}

function log(...args) {
  const ts = new Date().toISOString().replace('T', ' ').slice(0, 19);
  console.log(`[${ts}]`, ...args);
}

// ---------------------------------------------------------------------------
// 车机连接
// ---------------------------------------------------------------------------

const server = http.createServer((req, res) => handleHttp(req, res));

server.on('upgrade', (req, socket) => {
  let url;
  try {
    url = new URL(req.url, 'http://placeholder');
  } catch (e) {
    socket.destroy();
    return;
  }

  if (url.pathname !== '/relay') {
    socket.write('HTTP/1.1 404 Not Found\r\n\r\n');
    socket.destroy();
    return;
  }

  const room = (url.searchParams.get('room') || '').trim();
  const key = (url.searchParams.get('key') || '').trim();
  const role = url.searchParams.get('role') || 'car';
  const wsKey = req.headers['sec-websocket-key'];

  if (!room || !key || !wsKey || role !== 'car') {
    socket.write('HTTP/1.1 400 Bad Request\r\n\r\n');
    socket.destroy();
    return;
  }

  socket.write(
    'HTTP/1.1 101 Switching Protocols\r\n' +
      'Upgrade: websocket\r\n' +
      'Connection: Upgrade\r\n' +
      `Sec-WebSocket-Accept: ${wsAccept(wsKey)}\r\n` +
      '\r\n'
  );

  const previous = rooms.get(room);
  if (previous) {
    log(`room ${room} 重新连接，关掉旧连接`);
    try {
      previous.socket.destroy();
    } catch (e) {
      /* ignore */
    }
  }

  const conn = {
    room,
    key,
    socket,
    pending: new Map(),
    nextId: 1,
    connectedAt: Date.now(),
  };
  rooms.set(room, conn);
  log(`车机上线 room=${room}，当前在线 ${rooms.size}`);

  const pingTimer = setInterval(() => {
    try {
      socket.write(encodeFrame(Buffer.alloc(0), 0x9));
    } catch (e) {
      clearInterval(pingTimer);
    }
  }, PING_INTERVAL_MS);

  const cleanup = () => {
    clearInterval(pingTimer);
    if (rooms.get(room) === conn) rooms.delete(room);
    for (const [, pending] of conn.pending) {
      clearTimeout(pending.timer);
      try {
        pending.res.writeHead(504);
        pending.res.end('car disconnected');
      } catch (e) {
        /* ignore */
      }
    }
    conn.pending.clear();
    log(`车机下线 room=${room}，当前在线 ${rooms.size}`);
  };

  const parser = createParser({
    onData(payload) {
      handleCarFrame(conn, payload);
    },
    onPing(p) {
      try {
        socket.write(encodeFrame(p, 0xa));
      } catch (e) {
        /* ignore */
      }
    },
    onClose() {
      cleanup();
      try {
        socket.destroy();
      } catch (e) {
        /* ignore */
      }
    },
  });

  socket.on('data', (chunk) => {
    try {
      parser(chunk);
    } catch (e) {
      log('frame parse error', e.message);
      cleanup();
      socket.destroy();
    }
  });

  socket.on('error', (e) => {
    log('socket error', e.message);
    cleanup();
  });

  socket.on('close', cleanup);
});

function handleCarFrame(conn, frame) {
  if (frame.length < 5) return;

  const type = frame[0];
  const id = frame.readUInt32BE(1);
  const body = frame.subarray(5);
  const pending = conn.pending.get(id);
  if (!pending) return;

  if (type === T_RESP_HEAD) {
    let head;
    try {
      head = JSON.parse(body.toString('utf8'));
    } catch (e) {
      finishWithError(pending, 502, 'bad head from car');
      conn.pending.delete(id);
      return;
    }

    pending.status = head.status || 502;
    pending.headers = head.headers || {};
  } else if (type === T_RESP_BODY) {
    if (pending.size + body.length > MAX_BODY_BYTES) {
      finishWithError(pending, 502, 'response too large');
      conn.pending.delete(id);
      return;
    }
    pending.chunks.push(Buffer.from(body));
    pending.size += body.length;
  } else if (type === T_RESP_END) {
    clearTimeout(pending.timer);
    conn.pending.delete(id);
    writeResponse(pending);
  } else if (type === T_ERROR) {
    clearTimeout(pending.timer);
    conn.pending.delete(id);
    finishWithError(pending, 502, body.toString('utf8') || 'car error');
  }
}

function writeResponse(pending) {
  const { res } = pending;
  if (res.writableEnded) return;

  const body = Buffer.concat(pending.chunks);

  // 只透传播放器关心的头，其余（Date/Server/Connection）由中继自己生成
  const allow = ['content-type', 'content-range', 'accept-ranges', 'cache-control'];
  const headers = {};

  for (const [name, value] of Object.entries(pending.headers || {})) {
    const lower = name.toLowerCase();
    if (allow.includes(lower)) headers[name] = value;
  }
  headers['Content-Length'] = body.length;
  headers['Access-Control-Allow-Origin'] = '*';

  try {
    res.writeHead(pending.status || 200, headers);
    res.end(body);
  } catch (e) {
    log('write response failed', e.message);
  }
}

function finishWithError(pending, status, message) {
  if (pending.res.writableEnded) return;
  clearTimeout(pending.timer);
  try {
    pending.res.writeHead(status, {
      'Content-Type': 'application/json; charset=utf-8',
      'Access-Control-Allow-Origin': '*',
    });
    pending.res.end(JSON.stringify({ error: message }));
  } catch (e) {
    /* ignore */
  }
}

// ---------------------------------------------------------------------------
// 手机侧 HTTP
// ---------------------------------------------------------------------------

function handleHttp(req, res) {
  const url = new URL(req.url, 'http://placeholder');

  if (url.pathname === '/' || url.pathname === '/health') {
    res.writeHead(200, { 'Content-Type': 'application/json; charset=utf-8' });
    res.end(
      JSON.stringify({
        ok: true,
        service: 'car-sentinel-relay',
        rooms: rooms.size,
        uptimeSec: Math.round(process.uptime()),
      })
    );
    return;
  }

  const parts = url.pathname.split('/').filter(Boolean);
  // /v1/{room}/{key}/{...carPath}
  if (parts[0] !== 'v1' || parts.length < 4) {
    res.writeHead(404, { 'Content-Type': 'application/json; charset=utf-8' });
    res.end(JSON.stringify({ error: 'not found' }));
    return;
  }

  const room = decodeURIComponent(parts[1]);
  const key = decodeURIComponent(parts[2]);
  const conn = rooms.get(room);

  if (!conn) {
    res.writeHead(503, { 'Content-Type': 'application/json; charset=utf-8' });
    res.end(JSON.stringify({ error: '车机不在线' }));
    return;
  }

  if (conn.key !== key) {
    res.writeHead(403, { 'Content-Type': 'application/json; charset=utf-8' });
    res.end(JSON.stringify({ error: '密钥不对' }));
    return;
  }

  const carPath = '/' + parts.slice(3).join('/') + (url.search || '');
  proxyToCar(conn, req, res, carPath);
}

function proxyToCar(conn, req, res, carPath) {
  const id = conn.nextId++;

  const headers = {};
  for (const name of ['range', 'accept', 'user-agent']) {
    if (req.headers[name]) headers[name] = req.headers[name];
  }

  const pending = {
    res,
    chunks: [],
    size: 0,
    status: 502,
    headers: {},
    timer: setTimeout(() => {
      conn.pending.delete(id);
      finishWithError(pending, 504, '车机响应超时');
    }, REQUEST_TIMEOUT_MS),
  };
  conn.pending.set(id, pending);

  try {
    conn.socket.write(
      encodeFrame(
        packFrame(
          T_REQ_HEAD,
          id,
          Buffer.from(JSON.stringify({ method: req.method, path: carPath, headers }), 'utf8')
        )
      )
    );

    req.on('data', (chunk) => {
      conn.socket.write(encodeFrame(packFrame(T_REQ_BODY, id, chunk)));
    });

    req.on('end', () => {
      conn.socket.write(encodeFrame(packFrame(T_REQ_END, id, Buffer.alloc(0))));
    });

    req.on('error', () => {
      conn.pending.delete(id);
      clearTimeout(pending.timer);
    });
  } catch (e) {
    conn.pending.delete(id);
    clearTimeout(pending.timer);
    finishWithError(pending, 502, '转发失败：' + e.message);
  }
}

// ---------------------------------------------------------------------------

server.listen(PORT, () => {
  log(`车载哨兵中继已启动：0.0.0.0:${PORT}`);
  log(`车机连接：wss://<你的域名>/relay?room=<房间号>&key=<密钥>&role=car`);
  log(`手机地址：https://<你的域名>/v1/<房间号>/<密钥>`);
});

process.on('SIGTERM', () => {
  log('收到 SIGTERM，关闭中继');
  server.close(() => process.exit(0));
  setTimeout(() => process.exit(0), 2000);
});
