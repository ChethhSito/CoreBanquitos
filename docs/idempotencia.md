# Idempotencia de transferencias

`IdempotentTransferService.transfer(key, command)` es la entrada interna para una intención de transferencia. La clave `key` es un UUID generado por el cliente y se identifica junto con el usuario autenticado.

## Flujo

1. Validar el formato básico y calcular SHA-256 sobre usuario, origen, destino, importe normalizado y moneda.
2. Intentar insertar `(user_id, key, request_hash)` en `idempotency_keys`. Su clave primaria impide dos reservas iguales.
3. Si la reserva es nueva, ejecutar `TransferService.transfer` y guardar la respuesta `201` en la misma transacción SQL. Un rechazo definitivo de negocio, como saldo insuficiente, guarda `422` sin crear una transferencia.
4. Si ya existía la clave, comparar el hash. La misma solicitud recupera la respuesta guardada; un payload distinto produce `IDEMPOTENCY_CONFLICT`.
5. Si ocurre un error inesperado, toda la transacción hace rollback: tampoco queda la clave reservada, por lo que un reintento puede procesarse.

El `INSERT ... ON CONFLICT DO NOTHING` espera a que termine otra transacción que esté usando la misma clave. Por eso dos solicitudes simultáneas con la misma intención no crean dos movimientos.

| Situación | Resultado |
| --- | --- |
| Clave nueva y transferencia válida | `201`, un movimiento y respuesta guardada. |
| Misma clave y mismo payload | Misma respuesta `201` o `422`, sin nuevo movimiento. |
| Misma clave y distinto payload | Conflicto, sin nuevo movimiento. |
| Error inesperado antes del commit | Rollback completo; la clave se puede reintentar. |

Las respuestas `400`, `403` y `404` no se guardan en esta versión. Las claves guardadas no expiran en el MVP. La futura API usará este servicio una vez que obtenga el usuario autenticado y el header `Idempotency-Key`.
