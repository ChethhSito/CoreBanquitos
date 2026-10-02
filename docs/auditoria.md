# Auditoría de acciones

La migración `V2__audit_log.sql` crea `audit_logs`. Cada fila guarda el usuario que actuó (`actor_id`), la acción, el tipo e ID del recurso y la fecha que asigna PostgreSQL. No almacena contraseñas, tokens ni el cuerpo de la solicitud.

Por ahora se registran tres acciones confirmadas: `USER_REGISTERED`, `TEST_DEPOSIT_COMPLETED` y `TRANSFER_COMPLETED`. Registro, depósito y transferencia insertan su fila de auditoría dentro de la misma transacción que sus datos de negocio. Si falla la auditoría, se revierte también la operación. Al repetir una transferencia con la misma `Idempotency-Key`, se devuelve el resultado guardado sin crear otra fila.

La tabla es de solo inserción: un trigger impide `UPDATE` y `DELETE`. La auditoría se consulta directamente en PostgreSQL durante el desarrollo; aún no hay un endpoint público para leerla. Más adelante, un rol auditor podrá tener una consulta controlada.

Consulta de ejemplo en PostgreSQL:

```sql
SELECT actor_id, action, resource_type, resource_id, created_at
FROM audit_logs
ORDER BY created_at DESC
LIMIT 20;
```
