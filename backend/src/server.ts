import 'dotenv/config';
import express from 'express';
import cors from 'cors';
import { Pool } from 'pg';
import { Server } from 'socket.io';
import { createServer } from 'node:http';
import crypto from 'node:crypto';
import { z } from 'zod';
import { adminAuth, signAdmin, verifyPassword } from './auth.js';

const app = express();
const httpServer = createServer(app);
const io = new Server(httpServer, { cors: { origin: '*' } });
const pool = new Pool({ connectionString: process.env.DATABASE_URL });
const port = Number(process.env.PORT ?? 8080);
const cooldownSeconds = Number(process.env.ATTENDANCE_COOLDOWN_SECONDS ?? 60);

app.use(cors());
app.use(express.json({ limit: '2mb' }));

const loginSchema = z.object({ email: z.string().email(), password: z.string().min(1) });
const attendanceSchema = z.object({
  clientEventId: z.string().uuid(), employeeId: z.string().uuid(), status: z.enum(['IN', 'OUT']),
  capturedAt: z.string().datetime(), faceMatchScore: z.number().min(0).max(1).optional(),
  cloudinaryPublicId: z.string().max(500).optional(), cloudinaryAssetId: z.string().max(200).optional(), photoSecureUrl: z.string().url().max(2000).optional()
});

function hashToken(token: string) { return crypto.createHash('sha256').update(token).digest('hex'); }

async function deviceAuth(req: express.Request, res: express.Response, next: express.NextFunction) {
  try {
    const token = req.header('x-device-token');
    if (!token) return res.status(401).json({ error: 'DEVICE_TOKEN_REQUIRED' });
    const result = await pool.query('SELECT id, business_id, status FROM devices WHERE device_token_hash=$1 LIMIT 1', [hashToken(token)]);
    const device = result.rows[0];
    if (!device || device.status === 'revoked') return res.status(401).json({ error: 'INVALID_DEVICE' });
    (req as any).device = device; next();
  } catch { res.status(500).json({ error: 'DEVICE_AUTH_FAILED' }); }
}

app.get('/health', async (_req, res) => {
  try { await pool.query('SELECT 1'); res.json({ ok: true, service: 'attendance-backend' }); }
  catch { res.status(503).json({ ok: false, service: 'attendance-backend' }); }
});

app.post('/api/v1/admin/login', async (req, res) => {
  const parsed = loginSchema.safeParse(req.body);
  if (!parsed.success) return res.status(400).json({ error: 'INVALID_REQUEST' });
  const result = await pool.query('SELECT id,business_id,email,name,role,password_hash FROM admins WHERE lower(email)=lower($1) AND active=true LIMIT 1', [parsed.data.email]);
  const admin = result.rows[0];
  if (!admin || !verifyPassword(parsed.data.password, admin.password_hash)) return res.status(401).json({ error: 'INVALID_CREDENTIALS' });
  const token = signAdmin({ id: admin.id, businessId: admin.business_id, role: admin.role });
  res.json({ token, admin: { id: admin.id, businessId: admin.business_id, email: admin.email, name: admin.name, role: admin.role } });
});

app.get('/api/v1/admin/attendance', adminAuth, async (req, res) => {
  const admin = (req as any).admin;
  const requested = typeof req.query.businessId === 'string' ? req.query.businessId : admin.businessId;
  if (requested !== admin.businessId) return res.status(403).json({ error: 'BUSINESS_SCOPE_VIOLATION' });
  const result = await pool.query(`SELECT a.id,a.status,a.captured_at,a.photo_secure_url,a.cloudinary_public_id,e.employee_code,e.name AS employee_name,d.name AS device_name FROM attendance_events a JOIN employees e ON e.id=a.employee_id JOIN devices d ON d.id=a.device_id WHERE a.business_id=$1 ORDER BY a.captured_at DESC LIMIT 500`, [requested]);
  res.json({ items: result.rows });
});

app.get('/api/v1/admin/employees', adminAuth, async (req, res) => {
  const admin = (req as any).admin;
  const result = await pool.query('SELECT id,employee_code,name,department,active,created_at FROM employees WHERE business_id=$1 ORDER BY name', [admin.businessId]);
  res.json({ items: result.rows });
});

app.get('/api/v1/admin/devices', adminAuth, async (req, res) => {
  const admin = (req as any).admin;
  const result = await pool.query('SELECT id,name,status,app_version,last_seen_at,created_at FROM devices WHERE business_id=$1 ORDER BY name', [admin.businessId]);
  res.json({ items: result.rows });
});

app.post('/api/v1/device/heartbeat', deviceAuth, async (req, res) => {
  const device = (req as any).device;
  await pool.query(`UPDATE devices SET status='online',last_seen_at=now(),app_version=$2 WHERE id=$1`, [device.id, typeof req.body.appVersion === 'string' ? req.body.appVersion : null]);
  io.to(`business:${device.business_id}`).emit('device:status', { deviceId: device.id, status: 'online', lastSeenAt: new Date().toISOString() });
  res.json({ ok: true, serverTime: new Date().toISOString() });
});

app.post('/api/v1/device/attendance', deviceAuth, async (req, res) => {
  const device = (req as any).device; const parsed = attendanceSchema.safeParse(req.body);
  if (!parsed.success) return res.status(400).json({ error: 'INVALID_REQUEST', details: parsed.error.flatten() });
  const body = parsed.data; const client = await pool.connect();
  try {
    await client.query('BEGIN');
    const employee = await client.query(`SELECT e.id,e.name FROM employees e JOIN device_employees de ON de.employee_id=e.id WHERE e.id=$1 AND e.business_id=$2 AND e.active=true AND de.device_id=$3`, [body.employeeId,device.business_id,device.id]);
    if (!employee.rowCount) { await client.query('ROLLBACK'); return res.status(403).json({ error: 'EMPLOYEE_NOT_ASSIGNED_TO_DEVICE' }); }
    const duplicate = await client.query('SELECT id FROM attendance_events WHERE device_id=$1 AND client_event_id=$2',[device.id,body.clientEventId]);
    if (duplicate.rowCount) { await client.query('COMMIT'); return res.json({ ok:true,duplicate:true,attendanceId:duplicate.rows[0].id }); }
    const recent = await client.query(`SELECT id,status,captured_at FROM attendance_events WHERE employee_id=$1 AND business_id=$2 AND captured_at > now() - ($3 * interval '1 second') ORDER BY captured_at DESC LIMIT 1`,[body.employeeId,device.business_id,cooldownSeconds]);
    if (recent.rowCount) { await client.query('ROLLBACK'); return res.status(409).json({ error:'ATTENDANCE_COOLDOWN',lastEvent:recent.rows[0] }); }
    const inserted = await client.query(`INSERT INTO attendance_events (business_id,employee_id,device_id,status,captured_at,client_event_id,face_match_score,cloudinary_public_id,cloudinary_asset_id,photo_secure_url) VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10) RETURNING id,server_received_at`, [device.business_id,body.employeeId,device.id,body.status,body.capturedAt,body.clientEventId,body.faceMatchScore??null,body.cloudinaryPublicId??null,body.cloudinaryAssetId??null,body.photoSecureUrl??null]);
    await client.query('COMMIT');
    const event={attendanceId:inserted.rows[0].id,businessId:device.business_id,deviceId:device.id,employeeId:body.employeeId,employeeName:employee.rows[0].name,status:body.status,capturedAt:body.capturedAt};
    io.to(`business:${device.business_id}`).emit('attendance:new',event);
    res.status(201).json({ok:true,...event,serverReceivedAt:inserted.rows[0].server_received_at});
  } catch(error) { await client.query('ROLLBACK'); console.error(error); res.status(500).json({error:'ATTENDANCE_WRITE_FAILED'}); }
  finally { client.release(); }
});

app.get('/api/v1/cloudinary/signature', deviceAuth, (_req, res) => {
  const timestamp=Math.floor(Date.now()/1000); const folder='attendance';
  const toSign=`folder=${folder}&timestamp=${timestamp}${process.env.CLOUDINARY_API_SECRET}`;
  const signature=crypto.createHash('sha1').update(toSign).digest('hex');
  res.json({timestamp,signature,apiKey:process.env.CLOUDINARY_API_KEY,cloudName:process.env.CLOUDINARY_CLOUD_NAME,folder});
});

io.on('connection', socket => { const businessId=socket.handshake.auth?.businessId; if(typeof businessId==='string') socket.join(`business:${businessId}`); });

httpServer.listen(port,()=>console.log(`Attendance backend listening on :${port}`));
