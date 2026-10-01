# BankCore

Backend educativo de transferencias internas entre cuentas simuladas. El objetivo es demostrar consistencia contable, control de concurrencia, idempotencia y trazabilidad de cada operación.

## Alcance inicial

- Cuentas simuladas en PEN.
- Depósitos de prueba y transferencias internas.
- Ledger de doble partida y consulta de movimientos.
- API REST documentada y pruebas contra PostgreSQL.

Este proyecto no procesa dinero real ni datos bancarios reales.

## Estado

**Fase 1 — core interno.** El proyecto Spring Boot, la primera migración SQL y el servicio transaccional de transferencias están preparados. La guía completa está en [banckCore_Planificacion.md](banckCore_Planificacion.md).

## Documentación

- [Reglas de negocio](docs/reglas-de-negocio.md)
- [Modelo de datos inicial](docs/modelo-datos.md)
- [Contrato OpenAPI de transferencias](docs/openapi.yaml)

## Próximo entregable

Completar la idempotencia y la autenticación antes de exponer `POST /api/v1/transfers` según el contrato documentado.

## Ejecutar en Windows

Requisitos: JDK 25 y PostgreSQL local activo. El proyecto incluye Maven Wrapper; no hace falta instalar Maven por separado.

1. Ejecuta `./scripts/setup-local-db.ps1` en PowerShell. El script pide la contraseña local de `postgres`, crea la base y un usuario `bankcore`, y guarda una contraseña generada en `.env` (ignorado por Git).
2. Ejecuta `./scripts/start-local.ps1`. Flyway aplicará las migraciones al iniciar.
3. Comprueba `http://localhost:8080/actuator/health`.

El código aún no expone transferencias; el endpoint de salud confirma solamente que la aplicación y la conexión local arrancan.

## Probar el core de transferencias

Ejecuta `./scripts/test-local.ps1` en PowerShell. El script inicia un PostgreSQL temporal en el puerto 55432, aplica Flyway, prueba transferencia, saldo insuficiente, rollback del ledger y dos solicitudes concurrentes, y elimina la base temporal. No modifica la base `bankcore` de desarrollo. Si el puerto está ocupado, usa `./scripts/test-local.ps1 -Port 55433`.

La lógica de transferencias existe como servicio interno. El endpoint HTTP se agregará junto con autenticación e idempotencia.
