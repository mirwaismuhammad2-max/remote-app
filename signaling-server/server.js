/**
 * Minimal signaling relay for RemoteApp.
 *
 * It does NOT touch video/control data itself — once the phone and the
 * viewer have exchanged offer/answer/ICE candidates through here, the
 * actual screen stream and control commands flow directly between them
 * over WebRTC (peer-to-peer, or via a TURN server if either side is
 * behind a strict NAT). This server only ever sees signaling messages
 * and a room code.
 *
 * Rooms: a room is just a pairing code shared by exactly one "host"
 * (the phone) and one "viewer" (the remote browser). Messages sent by
 * one side are relayed to the other side in the same room.
 *
 * Run: npm install && node server.js
 * Deploy anywhere that can hold a persistent WebSocket connection
 * (Render, Fly.io, a small VPS, etc. — not classic shared PHP hosting).
 */

const WebSocket = require('ws');

const PORT = process.env.PORT || 8080;
const wss = new WebSocket.Server({ port: PORT });

// room code -> { host: ws|null, viewer: ws|null }
const rooms = new Map();

function getOrCreateRoom(code) {
  if (!rooms.has(code)) {
    rooms.set(code, { host: null, viewer: null });
  }
  return rooms.get(code);
}

function otherRole(role) {
  return role === 'host' ? 'viewer' : 'host';
}

wss.on('connection', (ws) => {
  ws.room = null;
  ws.role = null;

  ws.on('message', (raw) => {
    let msg;
    try {
      msg = JSON.parse(raw.toString());
    } catch (e) {
      ws.send(JSON.stringify({ type: 'error', message: 'invalid JSON' }));
      return;
    }

    if (msg.type === 'join') {
      const code = String(msg.room || '').trim();
      const role = msg.role === 'host' ? 'host' : 'viewer';
      if (!code) {
        ws.send(JSON.stringify({ type: 'error', message: 'room code required' }));
        return;
      }

      const room = getOrCreateRoom(code);
      if (room[role]) {
        // Someone is already in that slot; refuse the duplicate.
        ws.send(JSON.stringify({ type: 'error', message: `${role} already connected for this code` }));
        return;
      }

      room[role] = ws;
      ws.room = code;
      ws.role = role;

      const peer = room[otherRole(role)];
      if (peer && peer.readyState === WebSocket.OPEN) {
        peer.send(JSON.stringify({ type: 'peer-joined' }));
        ws.send(JSON.stringify({ type: 'peer-joined' }));
      }
      return;
    }

    // Every other message type is just relayed to the other side of the room.
    if (!ws.room || !ws.role) return;
    const room = rooms.get(ws.room);
    if (!room) return;
    const peer = room[otherRole(ws.role)];
    if (peer && peer.readyState === WebSocket.OPEN) {
      peer.send(JSON.stringify(msg));
    }
  });

  ws.on('close', () => {
    if (ws.room && rooms.has(ws.room)) {
      const room = rooms.get(ws.room);
      if (room[ws.role] === ws) room[ws.role] = null;
      if (!room.host && !room.viewer) rooms.delete(ws.room);
    }
  });
});

console.log(`Signaling server listening on port ${PORT}`);
