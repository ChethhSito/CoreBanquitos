# BankCore

Backend educativo de transferencias internas entre cuentas simuladas. El objetivo es demostrar consistencia contable, control de concurrencia, idempotencia y trazabilidad de cada operación.

## Alcance inicial

- Cuentas simuladas en PEN.
- Depósitos de prueba y transferencias internas.
- Ledger de doble partida y consulta de movimientos.
- API REST documentada y pruebas contra PostgreSQL.

Este proyecto no procesa dinero real ni datos bancarios reales.

## Estado

**MVP listo para probar localmente.** Incluye registro, login con JWT, cuentas, depósitos simulados, transferencias idempotentes y Swagger UI. La guía completa está en [banckCore_Planificacion.md](banckCore_Planificacion.md).

## Documentación

- [Reglas de negocio](docs/reglas-de-negocio.md)
- [Modelo de datos inicial](docs/modelo-datos.md)
- [Contrato inicial de transferencias](docs/openapi.yaml). El contrato actualizado se genera al ejecutar la aplicación en `/v3/api-docs`.
- [Flujo de una transferencia](docs/flujo-transferencia.md)
- [Idempotencia y reintentos](docs/idempotencia.md)
- [Auditoría de acciones](docs/auditoria.md)

Para consultar la auditoría desde Swagger, sigue la sección **Probar el rol auditor en local** de esa guía.

## Ejecutar en Windows

Requisitos: JDK 25 y PostgreSQL local activo. El proyecto incluye Maven Wrapper; no hace falta instalar Maven por separado.

1. Ejecuta `./scripts/setup-local-db.ps1` en PowerShell. El script pide la contraseña local de `postgres`, crea la base y un usuario `bankcore`, y guarda una contraseña generada en `.env` (ignorado por Git).
2. Ejecuta `./scripts/start-local.ps1`. Flyway aplicará las migraciones al iniciar.
3. Comprueba `http://localhost:8080/actuator/health` y abre `http://localhost:8080/swagger-ui.html`.

`start-local.ps1` genera automáticamente un secreto JWT en `.env` si aún no existe. El perfil `local` activa los depósitos simulados. Detén la aplicación con Ctrl+C.

## Probar desde Swagger UI

En cada operación, pulsa **Try it out**, completa el JSON y pulsa **Execute**:

1. `POST /api/v1/auth/register` para crear dos usuarios con correos distintos. Ejemplo de cuerpo: `{"email":"ana@example.com","password":"ClaveSegura123"}`. Guarda el `accountId` de cada respuesta.
2. `POST /api/v1/auth/login` con el correo y contraseña del primer usuario. Copia `accessToken`.
3. Pulsa **Authorize** arriba a la derecha y pega solo el `accessToken` (sin escribir `Bearer`).
4. `POST /api/v1/test-deposits` con `{"destinationAccountId":"<accountId del primer usuario>","amount":"100.00"}`. Esta ruta existe solo al ejecutar con perfil `local`.
5. `GET /api/v1/accounts` para ver el saldo `100.00`.
6. `POST /api/v1/transfers` con una cabecera `Idempotency-Key` que sea un UUID nuevo y el cuerpo `{"sourceAccountId":"<primera cuenta>","destinationAccountId":"<segunda cuenta>","amount":"60.00","currency":"PEN"}`.
7. Repite la misma solicitud y la misma clave: recibirás el mismo `id` de transferencia y el saldo se moverá una sola vez. Con esa clave y otro importe obtendrás `409 IDEMPOTENCY_CONFLICT`.
8. Consulta `GET /api/v1/accounts` para comprobar que la primera cuenta quedó en `40.00`. Inicia sesión como el segundo usuario para comprobar que la suya quedó en `60.00`.

Para Postman, puedes importar la URL `http://localhost:8080/v3/api-docs` como colección OpenAPI mientras la aplicación está ejecutándose.

## Probar el core de transferencias

Ejecuta `./scripts/test-local.ps1` en PowerShell. El script inicia un PostgreSQL temporal en el puerto 55432, aplica Flyway, prueba el core y el flujo HTTP completo, y elimina la base temporal. Al terminar muestra cuántas pruebas pasaron y si hubo fallos. No modifica la base `bankcore` de desarrollo. Si el puerto está ocupado, usa `./scripts/test-local.ps1 -Port 55433`.
