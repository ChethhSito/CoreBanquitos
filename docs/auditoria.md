# Auditoría de acciones

La migración `V2__audit_log.sql` crea `audit_logs`. Cada fila guarda el usuario que actuó (`actor_id`), la acción, el tipo e ID del recurso y la fecha que asigna PostgreSQL. No almacena contraseñas, tokens ni el cuerpo de la solicitud.

Por ahora se registran tres acciones confirmadas: `USER_REGISTERED`, `TEST_DEPOSIT_COMPLETED` y `TRANSFER_COMPLETED`. Registro, depósito y transferencia insertan su fila de auditoría dentro de la misma transacción que sus datos de negocio. Si falla la auditoría, se revierte también la operación. Al repetir una transferencia con la misma `Idempotency-Key`, se devuelve el resultado guardado sin crear otra fila.

La tabla es de solo inserción: un trigger impide `UPDATE` y `DELETE`. La migración `V3__user_roles.sql` agrega los roles `CUSTOMER` y `AUDITOR`. Todo registro nuevo empieza como `CUSTOMER`. La ruta `GET /api/v1/audit-logs?limit=20` devuelve los registros más recientes únicamente a tokens con rol `AUDITOR`; el límite permitido es de 1 a 100.

## Probar el rol auditor en local

Registra un usuario normalmente y, en pgAdmin sobre la base `bankcore`, asígnale el rol para esta prueba:

```sql
UPDATE users SET role = 'AUDITOR' WHERE lower(email) = 'tu-correo@example.com';
```

Inicia sesión **de nuevo** en Swagger con ese usuario y pega el nuevo `accessToken` en **Authorize**. El JWT guarda el rol al momento del login; el token anterior seguirá siendo `CUSTOMER` hasta vencer. En Swagger, ejecuta `GET /api/v1/audit-logs`. Un usuario normal recibe `403` y una solicitud sin token recibe `401`.

La promoción de roles se hace directamente en la base solo para la demostración local. No hay un endpoint público que permita a un usuario asignarse el rol `AUDITOR`.

Consulta de ejemplo en PostgreSQL:

```sql
SELECT actor_id, action, resource_type, resource_id, created_at
FROM audit_logs
ORDER BY created_at DESC
LIMIT 20;
```
