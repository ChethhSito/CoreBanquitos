# Flujo de una transferencia interna

`Transfer` registra la operación de negocio. `Account` guarda el saldo disponible para consultas rápidas. `Ledger` registra los movimientos contables que explican ese cambio: un cargo negativo al origen y un abono positivo al destino.

```mermaid
sequenceDiagram
    actor Cliente
    participant S as TransferService
    participant A as AccountJdbcRepository
    participant T as TransferJdbcRepository
    participant L as LedgerJdbcRepository
    participant DB as PostgreSQL

    Cliente->>S: transfer(actor, origen, destino, monto)
    Note over S,DB: BEGIN por @Transactional
    S->>S: Validar monto, moneda y cuentas distintas
    S->>A: Bloquear menor UUID
    A->>DB: SELECT ... FOR UPDATE
    S->>A: Bloquear mayor UUID
    A->>DB: SELECT ... FOR UPDATE
    S->>S: Verificar titular, estado, moneda y saldo
    alt Validación correcta
        S->>T: Guardar transferencia COMPLETED
        T->>DB: INSERT transfers
        S->>A: Actualizar ambos saldos
        A->>DB: UPDATE accounts
        S->>L: Registrar cargo y abono
        L->>DB: INSERT ledger_transactions
        L->>DB: INSERT ledger_entries (-monto, +monto)
        Note over S,DB: COMMIT: todo queda confirmado
        S-->>Cliente: TransferResult
    else Rechazo o error de escritura
        Note over S,DB: ROLLBACK: ningún cambio persiste
        S-->>Cliente: Error
    end
```

## Por qué se hace así

- `SELECT ... FOR UPDATE` impide que dos transferencias gasten simultáneamente el mismo saldo. Las cuentas se bloquean siempre en el mismo orden para reducir deadlocks.
- Los saldos y el ledger se escriben dentro de **una sola transacción SQL**. Si falla un asiento, se revierten también la transferencia y ambos saldos.
- Los dos asientos de una transferencia se compensan: `-monto + monto = 0`.
- `Account.available_balance` es una proyección de lectura rápida. El ledger conserva la historia que permitirá reconstruirla y reconciliarla.

Esta versión es un servicio interno. Todavía faltan la idempotencia, la autenticación y el endpoint HTTP.
