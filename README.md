# BankCore

Backend educativo de transferencias internas entre cuentas simuladas. El objetivo es demostrar consistencia contable, control de concurrencia, idempotencia y trazabilidad de cada operación.

## Alcance inicial

- Cuentas simuladas en PEN.
- Depósitos de prueba y transferencias internas.
- Ledger de doble partida y consulta de movimientos.
- API REST documentada y pruebas contra PostgreSQL.

Este proyecto no procesa dinero real ni datos bancarios reales.

## Estado

**Fase 1 — base técnica.** El proyecto Spring Boot y la primera migración SQL están preparados. La guía completa está en [banckCore_Planificacion.md](banckCore_Planificacion.md).

## Documentación

- [Reglas de negocio](docs/reglas-de-negocio.md)
- [Modelo de datos inicial](docs/modelo-datos.md)
- [Contrato OpenAPI de transferencias](docs/openapi.yaml)

## Próximo entregable

Construir una transferencia atómica con PostgreSQL, cuentas y ledger según el contrato documentado.

## Ejecutar en Windows

Requisitos: JDK 25 y PostgreSQL local activo. El proyecto incluye Maven Wrapper; no hace falta instalar Maven por separado.

1. Ejecuta `./scripts/setup-local-db.ps1` en PowerShell. El script pide la contraseña local de `postgres`, crea la base y un usuario `bankcore`, y guarda una contraseña generada en `.env` (ignorado por Git).
2. Ejecuta `./scripts/start-local.ps1`. Flyway aplicará las migraciones al iniciar.
3. Comprueba `http://localhost:8080/actuator/health`.

El código aún no expone transferencias; el endpoint de salud confirma solamente que la aplicación y la conexión local arrancan.
