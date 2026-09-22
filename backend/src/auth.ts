import crypto from 'node:crypto';
import jwt from 'jsonwebtoken';
import type { Request, Response, NextFunction } from 'express';

const secret = process.env.JWT_SECRET;
if (!secret) throw new Error('JWT_SECRET is required');

export function hashPassword(password: string) {
  const salt = crypto.randomBytes(16).toString('hex');
  const hash = crypto.scryptSync(password, salt, 64).toString('hex');
  return `scrypt:${salt}:${hash}`;
}

export function verifyPassword(password: string, stored: string) {
  const [scheme, salt, hash] = stored.split(':');
  if (scheme !== 'scrypt' || !salt || !hash) return false;
  const derived = crypto.scryptSync(password, salt, 64).toString('hex');
  return crypto.timingSafeEqual(Buffer.from(hash, 'hex'), Buffer.from(derived, 'hex'));
}

export function signAdmin(admin: { id: string; businessId: string; role: string }) {
  return jwt.sign({ sub: admin.id, businessId: admin.businessId, role: admin.role }, secret, { expiresIn: '12h' });
}

export function adminAuth(req: Request, res: Response, next: NextFunction) {
  const header = req.header('authorization');
  if (!header?.startsWith('Bearer ')) return res.status(401).json({ error: 'ADMIN_AUTH_REQUIRED' });
  try {
    (req as any).admin = jwt.verify(header.slice(7), secret);
    next();
  } catch { res.status(401).json({ error: 'INVALID_ADMIN_TOKEN' }); }
}
