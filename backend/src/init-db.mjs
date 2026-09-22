import 'dotenv/config';
import fs from 'node:fs';
import crypto from 'node:crypto';
import { Client } from 'pg';

if (!process.env.DATABASE_URL) throw new Error('DATABASE_URL is required');

const db = new Client({ connectionString: process.env.DATABASE_URL });
await db.connect();

try {
  const schema = fs.readFileSync(new URL('../schema.sql', import.meta.url), 'utf8');
  await db.query(schema);

  const email = process.env.ADMIN_EMAIL?.trim().toLowerCase();
  const password = process.env.ADMIN_PASSWORD;
  const businessName = process.env.BUSINESS_NAME?.trim() || 'Streetbell Attendance';
  const adminName = process.env.ADMIN_NAME?.trim() || 'Administrator';

  if (email && password) {
    const existing = await db.query('SELECT id FROM admins WHERE lower(email)=lower($1) LIMIT 1', [email]);
    if (!existing.rowCount) {
      const business = await db.query('SELECT id FROM businesses WHERE name=$1 ORDER BY created_at LIMIT 1', [businessName]);
      const businessId = business.rowCount
        ? business.rows[0].id
        : (await db.query('INSERT INTO businesses(name) VALUES($1) RETURNING id', [businessName])).rows[0].id;

      const salt = crypto.randomBytes(16).toString('hex');
      const hash = crypto.scryptSync(password, salt, 64).toString('hex');
      await db.query(
        'INSERT INTO admins(business_id,email,password_hash,name,role) VALUES($1,$2,$3,$4,$5)',
        [businessId, email, `scrypt:${salt}:${hash}`, adminName, 'owner']
      );
      console.log(`Initial admin created for ${email}`);
    } else {
      console.log(`Admin ${email} already exists`);
    }
  } else {
    console.log('ADMIN_EMAIL/ADMIN_PASSWORD not set; database initialized without an initial admin');
  }
} finally {
  await db.end();
}
