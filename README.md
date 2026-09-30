# BankCore

Backend educativo de transferencias internas entre cuentas simuladas. El objetivo es demostrar consistencia contable, control de concurrencia, idempotencia y trazabilidad de cada operación.

## Alcance inicial

- Cuentas simuladas en PEN.
- Depósitos de prueba y transferencias internas.
- Ledger de doble partida y consulta de movimientos.
- API REST documentada y pruebas contra PostgreSQL.

Este proyecto no procesa dinero real ni datos bancarios reales.

## Estado

**Fase 0 — diseño.** La guía completa está en [banckCore_Planificacion.md](banckCore_Planificacion.md). Los acuerdos iniciales están en [`docs/`](docs/).

## Documentación

- [Reglas de negocio](docs/reglas-de-negocio.md)
- [Modelo de datos inicial](docs/modelo-datos.md)

## Próximo entregable

Definir el contrato de `POST /api/v1/transfers` y construir una transferencia atómica con PostgreSQL, cuentas y ledger.
