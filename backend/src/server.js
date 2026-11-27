import Fastify from 'fastify';
import multipart from '@fastify/multipart';
import websocket from '@fastify/websocket';
import pg from 'pg';
import {
  createHash, randomBytes, scrypt as scryptCallback, timingSafeEqual,
  createHmac
} from 'node:crypto';
import { promisify } from 'node:util';
import { mkdir, writeFile, readFile } from 'node:fs/promises';
import path from 'node:path';

const scrypt = promisify(scryptCallback);
const PORT = Number(process.env.PORT || 8080);
const DATABASE_URL = process.env.DATABASE_URL;
const APP_SECRET = process.env.APP_SECRET;
const PUBLIC_BASE_URL = (process.env.PUBLIC_BASE_URL || '').replace(/\/$/, '');
const UPLOAD_DIR = process.env.UPLOAD_DIR || '/data/uploads';
const SESSION_DAYS = Number(process.env.SESSION_DAYS || 30);
if (!DATABASE_URL) throw new Error('DATABASE_URL is required');
if (!APP_SECRET || APP_SECRET.length < 32) throw new Error('APP_SECRET must contain at least 32 characters');

const pool = new pg.Pool({
  connectionString: DATABASE_URL,
  ssl: process.env.PGSSLMODE === 'require' ? { rejectUnauthorized: false } : undefined,
  max: Number(process.env.PGPOOL_MAX || 10)
});
const app = Fastify({ logger: true, bodyLimit: 1024 * 1024 });
await app.register(multipart, { limits: { fileSize: 25 * 1024 * 1024, files: 1 } });
await app.register(websocket);
await mkdir(UPLOAD_DIR, { recursive: true });
await pool.query(await readFile(new URL('../schema.sql', import.meta.url), 'utf8'));

const sockets = new Map();
const hash = value => createHash('sha256').update(value).digest('hex');
const nowIso = value => value ? new Date(value).toISOString() : null;
const userShape = u => ({
  id: Number(u.id), email: u.email, username: u.username,
  display_name: u.display_name, created_at: nowIso(u.created_at)
});
const attachmentShape = a => a ? ({
  id: Number(a.id), filename: a.filename, content_type: a.content_type,
  size_bytes: Number(a.size_bytes)
}) : null;
const messageShape = m => ({
  id: Number(m.id), conversation_id: Number(m.conversation_id),
  sender_id: Number(m.sender_id), content: m.content,
  created_at: nowIso(m.created_at), attachment: attachmentShape(m)
});
const readShape = r => ({
  conversation_id: Number(r.conversation_id), user_id: Number(r.user_id),
  last_read_message_id: r.last_read_message_id == null ? null : Number(r.last_read_message_id),
  updated_at: nowIso(r.updated_at)
});
const deliveryShape = d => ({
  message_id: Number(d.message_id), user_id: Number(d.user_id),
  delivered_at: nowIso(d.delivered_at)
});

function sendUser(userId, event) {
  const clients = sockets.get(Number(userId));
  if (!clients) return;
  const data = JSON.stringify(event);
  for (const socket of clients) {
    if (socket.readyState === 1) socket.send(data);
  }
}
async function broadcastConversation(conversationId, event, exceptUserId = null) {
  const { rows } = await pool.query(
    'SELECT user_id FROM conversation_members WHERE conversation_id=$1',
    [conversationId]
  );
  for (const row of rows) {
    if (exceptUserId != null && Number(row.user_id) === Number(exceptUserId)) continue;
    sendUser(row.user_id, event);
  }
}
async function requireAuth(request, reply) {
  const token = (request.headers.authorization || '').replace(/^Bearer\s+/i, '').trim();
  if (!token) return reply.code(401).send({ detail: 'Authentication required' });
  const { rows } = await pool.query(
    `SELECT s.id AS session_id, s.expires_at, u.*
       FROM sessions s JOIN users u ON u.id=s.user_id
      WHERE s.token_hash=$1 AND s.revoked_at IS NULL AND s.expires_at > now()`,
    [hash(token)]
  );
  if (!rows[0]) return reply.code(401).send({ detail: 'Invalid or expired session' });
  request.user = rows[0];
  request.sessionId = Number(rows[0].session_id);
}
async function isMember(conversationId, userId) {
  const { rowCount } = await pool.query(
    'SELECT 1 FROM conversation_members WHERE conversation_id=$1 AND user_id=$2',
    [conversationId, userId]
  );
  return rowCount > 0;
}
async function conversationShape(id, viewerId) {
  const { rows } = await pool.query(
    `SELECT c.*, array_agg(cm.user_id ORDER BY cm.user_id) AS member_ids
       FROM conversations c JOIN conversation_members cm ON cm.conversation_id=c.id
      WHERE c.id=$1 GROUP BY c.id`, [id]
  );
  const c = rows[0];
  if (!c) return null;
  const last = await pool.query(
    `SELECT m.*, a.id AS a_id, a.filename, a.content_type, a.size_bytes
       FROM messages m LEFT JOIN attachments a ON a.id=m.attachment_id
      WHERE m.conversation_id=$1 ORDER BY m.id DESC LIMIT 1`, [id]
  );
  const m = last.rows[0];
  return {
    id: Number(c.id), kind: c.kind, title: c.title,
    created_by: Number(c.created_by), member_ids: c.member_ids.map(Number),
    created_at: nowIso(c.created_at), updated_at: nowIso(c.updated_at),
    last_message: m ? messageShape({ ...m, id: m.id, attachment: m.a_id ? m : null }) : null
  };
}
async function newSession(userId) {
  const token = randomBytes(32).toString('base64url');
  const expiresAt = new Date(Date.now() + SESSION_DAYS * 86400000);
  const { rows } = await pool.query(
    'INSERT INTO sessions(user_id,token_hash,expires_at) VALUES($1,$2,$3) RETURNING id,created_at,expires_at',
    [userId, hash(token), expiresAt]
  );
  return { token, row: rows[0] };
}
async function passwordHash(password) {
  const salt = randomBytes(16).toString('hex');
  const derived = await scrypt(password, salt, 64);
  return `scrypt:${salt}:${Buffer.from(derived).toString('hex')}`;
}
async function passwordMatches(password, stored) {
  const [scheme, salt, key] = String(stored).split(':');
  if (scheme !== 'scrypt' || !salt || !key) return false;
  const derived = Buffer.from(await scrypt(password, salt, 64));
  const expected = Buffer.from(key, 'hex');
  return expected.length === derived.length && timingSafeEqual(expected, derived);
}
function signedDownload(attachmentId, expires) {
  const payload = `${attachmentId}.${expires}`;
  const sig = createHmac('sha256', APP_SECRET).update(payload).digest('hex');
  return `${payload}.${sig}`;
}
function validDownload(token, attachmentId) {
  const [id, expires, sig] = String(token || '').split('.');
  if (Number(id) !== Number(attachmentId) || !expires || Number(expires) < Date.now()) return false;
  const expected = createHmac('sha256', APP_SECRET).update(`${id}.${expires}`).digest();
  let actual;
  try { actual = Buffer.from(sig, 'hex'); } catch { return false; }
  return actual.length === expected.length && timingSafeEqual(actual, expected);
}
function publicBase(request) {
  return PUBLIC_BASE_URL || `${request.protocol}://${request.hostname}`;
}

app.get('/health', async () => ({ status: 'ok', service: 'godhead-api' }));

app.post('/v1/auth/register', async (request, reply) => {
  const { email, username, password, display_name } = request.body || {};
  if (typeof email !== 'string' || !/^\S+@\S+\.\S+$/.test(email) || email.length > 254)
    return reply.code(400).send({ detail: 'A valid email is required' });
  if (typeof username !== 'string' || !/^[a-zA-Z0-9_]{3,32}$/.test(username))
    return reply.code(400).send({ detail: 'Username must be 3-32 letters, numbers or underscores' });
  if (typeof password !== 'string' || password.length < 12 || password.length > 256)
    return reply.code(400).send({ detail: 'Password must contain 12-256 characters' });
  if (typeof display_name !== 'string' || !display_name.trim() || display_name.length > 80)
    return reply.code(400).send({ detail: 'Display name is required (max 80 characters)' });
  try {
    const { rows } = await pool.query(
      'INSERT INTO users(email,username,display_name,password_hash) VALUES($1,$2,$3,$4) RETURNING *',
      [email.trim().toLowerCase(), username.trim().toLowerCase(), display_name.trim(), await passwordHash(password)]
    );
    const session = await newSession(rows[0].id);
    return reply.code(201).send({
      access_token: session.token, token_type: 'bearer',
      expires_in: SESSION_DAYS * 86400, user: userShape(rows[0])
    });
  } catch (error) {
    if (error.code === '23505') return reply.code(409).send({ detail: 'Email or username is already registered' });
    throw error;
  }
});

app.post('/v1/auth/login', async (request, reply) => {
  const { email, password } = request.body || {};
  if (typeof email !== 'string' || typeof password !== 'string')
    return reply.code(400).send({ detail: 'Email and password are required' });
  const { rows } = await pool.query('SELECT * FROM users WHERE email=$1', [email.trim().toLowerCase()]);
  if (!rows[0] || !(await passwordMatches(password, rows[0].password_hash)))
    return reply.code(401).send({ detail: 'Incorrect email or password' });
  const session = await newSession(rows[0].id);
  return {
    access_token: session.token, token_type: 'bearer',
    expires_in: SESSION_DAYS * 86400, user: userShape(rows[0])
  };
});

app.post('/v1/auth/logout', { preHandler: requireAuth }, async request => {
  await pool.query('UPDATE sessions SET revoked_at=now() WHERE id=$1', [request.sessionId]);
  return { revoked: true };
});
app.get('/v1/auth/sessions', { preHandler: requireAuth }, async request => {
  const { rows } = await pool.query(
    'SELECT id,created_at,expires_at FROM sessions WHERE user_id=$1 AND revoked_at IS NULL AND expires_at>now() ORDER BY id DESC',
    [request.user.id]
  );
  return rows.map(s => ({ id: Number(s.id), created_at: nowIso(s.created_at), expires_at: nowIso(s.expires_at), is_current: Number(s.id) === request.sessionId }));
});
app.delete('/v1/auth/sessions/:id', { preHandler: requireAuth }, async (request, reply) => {
  const { rowCount } = await pool.query(
    'UPDATE sessions SET revoked_at=now() WHERE id=$1 AND user_id=$2 AND revoked_at IS NULL',
    [request.params.id, request.user.id]
  );
  if (!rowCount) return reply.code(404).send({ detail: 'Session not found' });
  return { revoked: true };
});

app.get('/v1/users/me', { preHandler: requireAuth }, async request => userShape(request.user));
app.get('/v1/users/search', { preHandler: requireAuth }, async (request, reply) => {
  const q = String(request.query.query || '').replace(/^@/, '').trim();
  const limit = Math.min(50, Math.max(1, Number(request.query.limit || 8)));
  if (q.length < 2 || q.length > 64) return reply.code(400).send({ detail: 'Search must contain 2-64 characters' });
  const { rows } = await pool.query(
    `SELECT id,username,display_name FROM users
      WHERE id<>$1 AND (username ILIKE $2 OR display_name ILIKE $2)
      ORDER BY username LIMIT $3`, [request.user.id, `%${q}%`, limit]
  );
  return rows.map(u => ({ id: Number(u.id), username: u.username, display_name: u.display_name }));
});

app.post('/v1/conversations/direct/:userId', { preHandler: requireAuth }, async (request, reply) => {
  const target = Number(request.params.userId);
  if (!Number.isSafeInteger(target) || target <= 0 || target === Number(request.user.id))
    return reply.code(400).send({ detail: 'Invalid target user' });
  const found = await pool.query(
    `SELECT c.id FROM conversations c
      JOIN conversation_members a ON a.conversation_id=c.id AND a.user_id=$1
      JOIN conversation_members b ON b.conversation_id=c.id AND b.user_id=$2
     WHERE c.kind='direct'
       AND (SELECT count(*) FROM conversation_members x WHERE x.conversation_id=c.id)=2
     LIMIT 1`, [request.user.id, target]
  );
  let id = found.rows[0]?.id;
  if (!id) {
    const targetUser = await pool.query('SELECT id FROM users WHERE id=$1', [target]);
    if (!targetUser.rows[0]) return reply.code(404).send({ detail: 'User not found' });
    const client = await pool.connect();
    try {
      await client.query('BEGIN');
      const created = await client.query(
        "INSERT INTO conversations(kind,created_by) VALUES('direct',$1) RETURNING id",
        [request.user.id]
      );
      id = created.rows[0].id;
      await client.query('INSERT INTO conversation_members(conversation_id,user_id) VALUES($1,$2),($1,$3)', [id, request.user.id, target]);
      await client.query('COMMIT');
    } catch (e) { await client.query('ROLLBACK'); throw e; } finally { client.release(); }
  }
  return conversationShape(id, request.user.id);
});

app.post('/v1/conversations/groups', { preHandler: requireAuth }, async (request, reply) => {
  const { title, member_ids = [] } = request.body || {};
  if (typeof title !== 'string' || !title.trim() || title.length > 100 || !Array.isArray(member_ids) || member_ids.length > 199)
    return reply.code(400).send({ detail: 'A group title and up to 199 member IDs are required' });
  const ids = [...new Set([Number(request.user.id), ...member_ids.map(Number)])];
  if (ids.some(id => !Number.isSafeInteger(id) || id <= 0)) return reply.code(400).send({ detail: 'Invalid member ID' });
  const existing = await pool.query('SELECT id FROM users WHERE id=ANY($1::bigint[])', [ids]);
  if (existing.rowCount !== ids.length) return reply.code(404).send({ detail: 'One or more users do not exist' });
  const client = await pool.connect();
  let id;
  try {
    await client.query('BEGIN');
    const created = await client.query(
      "INSERT INTO conversations(kind,title,created_by) VALUES('group',$1,$2) RETURNING id",
      [title.trim(), request.user.id]
    );
    id = created.rows[0].id;
    await client.query(
      'INSERT INTO conversation_members(conversation_id,user_id) SELECT $1, unnest($2::bigint[])',
      [id, ids]
    );
    await client.query('COMMIT');
  } catch (e) { await client.query('ROLLBACK'); throw e; } finally { client.release(); }
  return reply.code(201).send(await conversationShape(id, request.user.id));
});

app.get('/v1/conversations', { preHandler: requireAuth }, async request => {
  const limit = Math.min(100, Math.max(1, Number(request.query.limit || 50)));
  const { rows } = await pool.query(
    'SELECT conversation_id FROM conversation_members WHERE user_id=$1 ORDER BY conversation_id DESC LIMIT $2',
    [request.user.id, limit]
  );
  const result = [];
  for (const row of rows) result.push(await conversationShape(row.conversation_id, request.user.id));
  return result;
});

app.post('/v1/conversations/:conversationId/messages', { preHandler: requireAuth }, async (request, reply) => {
  const conversationId = Number(request.params.conversationId);
  if (!(await isMember(conversationId, request.user.id))) return reply.code(404).send({ detail: 'Conversation not found' });
  const content = String(request.body?.content || '').trim();
  if (!content || content.length > 4096) return reply.code(400).send({ detail: 'Message must contain 1-4096 characters' });
  const { rows } = await pool.query(
    'INSERT INTO messages(conversation_id,sender_id,content) VALUES($1,$2,$3) RETURNING *',
    [conversationId, request.user.id, content]
  );
  const message = messageShape(rows[0]);
  await pool.query('UPDATE conversations SET updated_at=now() WHERE id=$1', [conversationId]);
  await broadcastConversation(conversationId, { type: 'message.created', data: message }, request.user.id);
  return reply.code(201).send(message);
});

app.get('/v1/conversations/:conversationId/messages', { preHandler: requireAuth }, async (request, reply) => {
  const conversationId = Number(request.params.conversationId);
  if (!(await isMember(conversationId, request.user.id))) return reply.code(404).send({ detail: 'Conversation not found' });
  const limit = Math.min(100, Math.max(1, Number(request.query.limit || 50)));
  const beforeId = request.query.before_id ? Number(request.query.before_id) : null;
  const params = [conversationId];
  let cursor = '';
  if (beforeId) { params.push(beforeId); cursor = ' AND m.id<$2'; }
  params.push(limit + 1);
  const { rows } = await pool.query(
    `SELECT m.*, a.id AS a_id, a.filename, a.content_type, a.size_bytes
       FROM messages m LEFT JOIN attachments a ON a.id=m.attachment_id
      WHERE m.conversation_id=$1${cursor} ORDER BY m.id DESC LIMIT $${params.length}`, params
  );
  const hasMore = rows.length > limit;
  const page = rows.slice(0, limit).reverse().map(m => messageShape({ ...m, attachment: m.a_id ? m : null }));
  return { items: page, next_before_id: hasMore ? page[0].id : null };
});

app.put('/v1/conversations/:conversationId/read', { preHandler: requireAuth }, async (request, reply) => {
  const conversationId = Number(request.params.conversationId);
  const lastId = Number(request.body?.last_read_message_id);
  if (!(await isMember(conversationId, request.user.id))) return reply.code(404).send({ detail: 'Conversation not found' });
  const message = await pool.query('SELECT id FROM messages WHERE id=$1 AND conversation_id=$2', [lastId, conversationId]);
  if (!message.rows[0]) return reply.code(400).send({ detail: 'Message does not belong to this conversation' });
  const { rows } = await pool.query(
    `INSERT INTO read_states(conversation_id,user_id,last_read_message_id)
     VALUES($1,$2,$3) ON CONFLICT(conversation_id,user_id)
     DO UPDATE SET last_read_message_id=GREATEST(read_states.last_read_message_id,EXCLUDED.last_read_message_id),updated_at=now()
     RETURNING *`, [conversationId, request.user.id, lastId]
  );
  const state = readShape(rows[0]);
  await broadcastConversation(conversationId, { type: 'read.state.updated', data: state });
  return state;
});
app.get('/v1/conversations/:conversationId/read-states', { preHandler: requireAuth }, async (request, reply) => {
  const id = Number(request.params.conversationId);
  if (!(await isMember(id, request.user.id))) return reply.code(404).send({ detail: 'Conversation not found' });
  const { rows } = await pool.query('SELECT * FROM read_states WHERE conversation_id=$1', [id]);
  return rows.map(readShape);
});

app.post('/v1/conversations/:conversationId/messages/:messageId/delivery', { preHandler: requireAuth }, async (request, reply) => {
  const conversationId = Number(request.params.conversationId), messageId = Number(request.params.messageId);
  if (!(await isMember(conversationId, request.user.id))) return reply.code(404).send({ detail: 'Conversation not found' });
  const message = await pool.query('SELECT id,sender_id FROM messages WHERE id=$1 AND conversation_id=$2', [messageId, conversationId]);
  if (!message.rows[0]) return reply.code(404).send({ detail: 'Message not found' });
  if (Number(message.rows[0].sender_id) === Number(request.user.id)) return reply.code(400).send({ detail: 'Sender cannot acknowledge own message' });
  const { rows } = await pool.query(
    'INSERT INTO delivery_states(message_id,user_id) VALUES($1,$2) ON CONFLICT(message_id,user_id) DO UPDATE SET delivered_at=delivery_states.delivered_at RETURNING *',
    [messageId, request.user.id]
  );
  const state = deliveryShape(rows[0]);
  await broadcastConversation(conversationId, { type: 'message.delivered', data: { ...state, conversation_id: conversationId } });
  return state;
});
app.get('/v1/conversations/:conversationId/messages/:messageId/delivery-states', { preHandler: requireAuth }, async (request, reply) => {
  const conversationId = Number(request.params.conversationId), messageId = Number(request.params.messageId);
  if (!(await isMember(conversationId, request.user.id))) return reply.code(404).send({ detail: 'Conversation not found' });
  const message = await pool.query('SELECT id FROM messages WHERE id=$1 AND conversation_id=$2', [messageId, conversationId]);
  if (!message.rows[0]) return reply.code(404).send({ detail: 'Message not found' });
  const { rows } = await pool.query('SELECT * FROM delivery_states WHERE message_id=$1', [messageId]);
  return rows.map(deliveryShape);
});

app.post('/v1/conversations/:conversationId/attachments', { preHandler: requireAuth }, async (request, reply) => {
  const conversationId = Number(request.params.conversationId);
  if (!(await isMember(conversationId, request.user.id))) return reply.code(404).send({ detail: 'Conversation not found' });
  const part = await request.file();
  if (!part) return reply.code(400).send({ detail: 'Attachment file is required' });
  const bytes = await part.toBuffer();
  if (!bytes.length || bytes.length > 25 * 1024 * 1024) return reply.code(413).send({ detail: 'Attachment must be 1 byte to 25 MiB' });
  const safeName = path.basename(part.filename || 'attachment').replace(/[\r\n\0]/g, '_').slice(0, 180) || 'attachment';
  const id = randomBytes(16).toString('hex');
  const storagePath = path.join(UPLOAD_DIR, id);
  await writeFile(storagePath, bytes, { flag: 'wx', mode: 0o600 });
  const client = await pool.connect();
  try {
    await client.query('BEGIN');
    const attachment = await client.query(
      'INSERT INTO attachments(conversation_id,uploaded_by,filename,content_type,size_bytes,storage_path) VALUES($1,$2,$3,$4,$5,$6) RETURNING *',
      [conversationId, request.user.id, safeName, part.mimetype || 'application/octet-stream', bytes.length, storagePath]
    );
    const a = attachment.rows[0];
    const msg = await client.query(
      'INSERT INTO messages(conversation_id,sender_id,content,attachment_id) VALUES($1,$2,$3,$4) RETURNING *',
      [conversationId, request.user.id, String((part.fields.caption?.value || '')).slice(0,4096), a.id]
    );
    await client.query('UPDATE conversations SET updated_at=now() WHERE id=$1', [conversationId]);
    await client.query('COMMIT');
    const message = messageShape({ ...msg.rows[0], attachment: a });
    await broadcastConversation(conversationId, { type: 'message.created', data: message }, request.user.id);
    return reply.code(201).send(message);
  } catch (e) { await client.query('ROLLBACK'); throw e; } finally { client.release(); }
});
app.get('/v1/conversations/:conversationId/attachments/:attachmentId/download', { preHandler: requireAuth }, async (request, reply) => {
  const conversationId = Number(request.params.conversationId), attachmentId = Number(request.params.attachmentId);
  if (!(await isMember(conversationId, request.user.id))) return reply.code(404).send({ detail: 'Conversation not found' });
  const { rows } = await pool.query('SELECT * FROM attachments WHERE id=$1 AND conversation_id=$2', [attachmentId, conversationId]);
  if (!rows[0]) return reply.code(404).send({ detail: 'Attachment not found' });
  const expires = Date.now() + 10 * 60 * 1000;
  const token = signedDownload(attachmentId, expires);
  return { attachment: attachmentShape(rows[0]), url: `${publicBase(request)}/v1/files/${attachmentId}?token=${token}`, expires_in: 600 };
});
app.get('/v1/files/:attachmentId', async (request, reply) => {
  const id = Number(request.params.attachmentId);
  if (!validDownload(request.query.token, id)) return reply.code(403).send({ detail: 'Invalid or expired download link' });
  const { rows } = await pool.query('SELECT * FROM attachments WHERE id=$1', [id]);
  if (!rows[0]) return reply.code(404).send({ detail: 'File not found' });
  const bytes = await readFile(rows[0].storage_path);
  reply.header('Content-Type', rows[0].content_type).header('Content-Length', bytes.length);
  reply.header('Content-Disposition', `attachment; filename*=UTF-8''${encodeURIComponent(rows[0].filename)}`);
  return reply.send(bytes);
});

app.get('/v1/ws', { websocket: true }, async (socket, request) => {
  const token = (request.headers.authorization || '').replace(/^Bearer\s+/i, '').trim();
  if (!token) return socket.close(1008, 'Authentication required');
  const { rows } = await pool.query(
    'SELECT u.id FROM sessions s JOIN users u ON u.id=s.user_id WHERE s.token_hash=$1 AND s.revoked_at IS NULL AND s.expires_at>now()',
    [hash(token)]
  );
  if (!rows[0]) return socket.close(1008, 'Invalid session');
  const userId = Number(rows[0].id);
  if (!sockets.has(userId)) sockets.set(userId, new Set());
  sockets.get(userId).add(socket);
  socket.on('message', data => {
    if (String(data) === 'ping' && socket.readyState === 1) socket.send('pong');
  });
  socket.on('close', () => {
    const set = sockets.get(userId);
    if (set) { set.delete(socket); if (!set.size) sockets.delete(userId); }
  });
});

app.setErrorHandler((error, request, reply) => {
  request.log.error(error);
  if (reply.sent) return;
  const status = error.statusCode && error.statusCode >= 400 ? error.statusCode : 500;
  reply.code(status).send({ detail: status === 500 ? 'Internal server error' : error.message });
});

await app.listen({ host: '0.0.0.0', port: PORT });
