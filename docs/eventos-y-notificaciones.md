# Outbox y notificaciones simuladas

La migración `V5__outbox_notifications.sql` crea `outbox_events`, `processed_events` y `notification_deliveries`. Una transferencia o depósito confirmado inserta su evento en `outbox_events` dentro de la misma transacción que saldos, ledger y auditoría. Si falla ese INSERT, toda la operación financiera se revierte.

El publicador reclama eventos pendientes con `FOR UPDATE SKIP LOCKED`, espera la confirmación de RabbitMQ y recién entonces marca el evento `PUBLISHED`. Si RabbitMQ está caído, el evento queda pendiente para un reintento. Si la confirmación llegó pero falla el UPDATE en PostgreSQL, puede publicarse otra vez: por eso el worker guarda `event_id` en `processed_events` y genera una sola fila en `notification_deliveries`. Esa fila **simula** una notificación; todavía no se envía correo ni SMS.

El worker funciona como un segundo proceso de la misma aplicación Java, sin servidor HTTP. Consume la cola `bankcore.notifications`. Los mensajes inválidos o los que fallan tras los reintentos terminan en `bankcore.notifications.dlq` para inspección. Publicador y worker usan la misma base PostgreSQL en esta etapa.

## Probar con RabbitMQ local

Necesitas Docker Desktop ejecutándose. En `D:\core_bank`, abre tres terminales PowerShell:

```powershell
# Terminal 1: RabbitMQ
docker compose -f compose.messaging.yaml up -d

# Terminal 2: API y publicador
.\scripts\start-local.ps1 -Messaging

# Terminal 3: worker de notificaciones
.\scripts\start-notification-worker.ps1
```

En Swagger (`http://localhost:8080/swagger-ui.html`) realiza un depósito simulado o una transferencia. La migración `V5` se aplica al arrancar. En pgAdmin puedes revisar:

```sql
SELECT id, aggregate_id, status, attempts, published_at
FROM outbox_events ORDER BY created_at DESC LIMIT 10;

SELECT event_id, transfer_id, recipient_user_id, created_at
FROM notification_deliveries ORDER BY created_at DESC LIMIT 10;
```

RabbitMQ Management está en `http://localhost:15672` con usuario `bankcore` y contraseña `bankcore-local-only` para este entorno local. Allí puedes ver las colas y la DLQ. Las variables opcionales `BANKCORE_RABBIT_HOST`, `BANKCORE_RABBIT_PORT`, `BANKCORE_RABBIT_USER` y `BANKCORE_RABBIT_PASSWORD` permiten usar otro broker. Si cambias las credenciales de un volumen RabbitMQ ya inicializado, tendrás que actualizarlas también dentro del broker.

Si ejecutas `.\scripts\start-local.ps1` sin `-Messaging`, la API sigue aceptando transferencias y acumulando eventos `PENDING`; podrás publicarlos al volver a iniciarla con `-Messaging` y RabbitMQ disponible. La caída del broker nunca debe revertir una transferencia ya confirmada.
