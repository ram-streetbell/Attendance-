# Attendance Platform

Multi-business, multi-device attendance platform with two Android applications:

- `attendance-app`: kiosk/device application. Camera detects a face, identifies the employee, then explicitly asks the employee to choose **IN** or **OUT**. The event photo is stored in Cloudinary and the attendance record is stored in PostgreSQL.
- `admin-app`: business/admin application for employees, face enrollment, devices, attendance history, photos and reports.
- `backend`: API, authentication, device pairing, attendance validation, Cloudinary signing, synchronization and WebSocket events.

## Architecture

Android apps communicate with the backend over HTTPS. WebSocket is used for live admin updates and device presence. Attendance devices keep a local queue so temporary internet outages do not lose events.

Cloudinary is media storage only; PostgreSQL is the source of truth for attendance metadata.

## Attendance flow

1. Device detects a face with CameraX.
2. Recognition engine identifies an active employee assigned to that device.
3. Device displays the employee name and **IN / OUT** buttons.
4. Employee selects exactly one action.
5. Device captures the attendance photo.
6. Event is written to the local outbox first.
7. Backend validates business/device/employee relationship and duplicate protection.
8. Photo is uploaded using a backend-authorized Cloudinary flow.
9. Attendance is committed transactionally and broadcast to admins.
10. Local outbox marks the event synchronized.

## Security

No Cloudinary API secret is stored in Android. Signed upload credentials/signatures are generated server-side. Business scoping is enforced on every protected API operation.

## Development

Backend requires Node.js 20+ and PostgreSQL 16+.
Android applications require Android Studio and a current Android SDK.

Face recognition is deliberately isolated behind an interface so the recognition model can be upgraded without changing attendance/business logic. Face detection and camera capture are separate from recognition.
