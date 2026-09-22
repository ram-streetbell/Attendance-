CREATE EXTENSION IF NOT EXISTS pgcrypto;

CREATE TABLE IF NOT EXISTS businesses (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  name TEXT NOT NULL,
  timezone TEXT NOT NULL DEFAULT 'Asia/Kolkata',
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS admins (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  business_id UUID NOT NULL REFERENCES businesses(id) ON DELETE CASCADE,
  email TEXT NOT NULL UNIQUE,
  password_hash TEXT NOT NULL,
  name TEXT NOT NULL,
  role TEXT NOT NULL DEFAULT 'admin' CHECK (role IN ('owner','admin')),
  active BOOLEAN NOT NULL DEFAULT true,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS devices (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  business_id UUID NOT NULL REFERENCES businesses(id) ON DELETE CASCADE,
  name TEXT NOT NULL,
  pairing_code TEXT UNIQUE,
  device_token_hash TEXT,
  status TEXT NOT NULL DEFAULT 'offline' CHECK (status IN ('online','offline','revoked')),
  app_version TEXT,
  last_seen_at TIMESTAMPTZ,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS employees (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  business_id UUID NOT NULL REFERENCES businesses(id) ON DELETE CASCADE,
  employee_code TEXT NOT NULL,
  name TEXT NOT NULL,
  department TEXT,
  active BOOLEAN NOT NULL DEFAULT true,
  face_template BYTEA,
  face_model_version TEXT,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE(business_id, employee_code)
);

CREATE TABLE IF NOT EXISTS device_employees (
  device_id UUID NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
  employee_id UUID NOT NULL REFERENCES employees(id) ON DELETE CASCADE,
  PRIMARY KEY(device_id, employee_id)
);

CREATE TABLE IF NOT EXISTS attendance_events (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  business_id UUID NOT NULL REFERENCES businesses(id) ON DELETE CASCADE,
  employee_id UUID NOT NULL REFERENCES employees(id) ON DELETE RESTRICT,
  device_id UUID NOT NULL REFERENCES devices(id) ON DELETE RESTRICT,
  status TEXT NOT NULL CHECK (status IN ('IN','OUT')),
  captured_at TIMESTAMPTZ NOT NULL,
  server_received_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  cloudinary_public_id TEXT,
  cloudinary_asset_id TEXT,
  photo_secure_url TEXT,
  face_match_score REAL,
  client_event_id UUID NOT NULL,
  UNIQUE(device_id, client_event_id)
);

CREATE INDEX IF NOT EXISTS attendance_business_time_idx ON attendance_events(business_id, captured_at DESC);
CREATE INDEX IF NOT EXISTS attendance_employee_time_idx ON attendance_events(employee_id, captured_at DESC);
CREATE INDEX IF NOT EXISTS device_heartbeat_idx ON devices(business_id, last_seen_at DESC);
