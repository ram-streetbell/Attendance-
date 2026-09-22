import 'dotenv/config';
import crypto from 'node:crypto';
import { Client } from 'pg';

const [businessName='Demo Business', adminEmail='admin@example.com', adminPassword='ChangeMe123!', deviceName='Entrance 01'] = process.argv.slice(2);
const db = new Client({ connectionString: process.env.DATABASE_URL });
await db.connect();
const hashPassword = p => { const salt=crypto.randomBytes(16).toString('hex'); const hash=crypto.scryptSync(p,salt,64).toString('hex'); return `scrypt:${salt}:${hash}`; };
const token = crypto.randomBytes(32).toString('base64url');
const pairingCode = crypto.randomBytes(4).toString('hex').toUpperCase();
const b = await db.query('INSERT INTO businesses(name) VALUES($1) RETURNING id',[businessName]);
const businessId=b.rows[0].id;
await db.query('INSERT INTO admins(business_id,email,password_hash,name,role) VALUES($1,$2,$3,$4,$5)',[businessId,adminEmail,hashPassword(adminPassword),'Administrator','owner']);
const d=await db.query('INSERT INTO devices(business_id,name,pairing_code,device_token_hash) VALUES($1,$2,$3,$4) RETURNING id',[businessId,deviceName,pairingCode,crypto.createHash('sha256').update(token).digest('hex')]);
console.log(JSON.stringify({businessId,adminEmail,deviceId:d.rows[0].id,pairingCode,deviceToken:token},null,2));
await db.end();
