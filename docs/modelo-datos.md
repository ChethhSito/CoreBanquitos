# Modelo de datos inicial

Este esquema sirve para discutir las relaciones antes de escribir migraciones. Las columnas y restricciones finales se definirán al implementar cada módulo.

```mermaid
erDiagram
    USERS ||--o{ ACCOUNTS : owns
    ACCOUNTS ||--o{ TRANSFERS : source
    ACCOUNTS ||--o{ TRANSFERS : destination
    TRANSFERS ||--|| LEDGER_TRANSACTIONS : produces
    LEDGER_TRANSACTIONS ||--|{ LEDGER_ENTRIES : contains
    ACCOUNTS ||--o{ LEDGER_ENTRIES : receives
    USERS ||--o{ IDEMPOTENCY_KEYS : submits

    USERS {
        uuid id PK
        string email UK
        string password_hash
        string status
    }
    ACCOUNTS {
        uuid id PK
        uuid owner_id FK
        string currency
        decimal available_balance
        string status
    }
    TRANSFERS {
        uuid id PK
        uuid source_account_id FK
        uuid destination_account_id FK
        decimal amount
        string status
    }
    LEDGER_TRANSACTIONS {
        uuid id PK
        uuid transfer_id FK
        datetime created_at
    }
    LEDGER_ENTRIES {
        uuid id PK
        uuid ledger_transaction_id FK
        uuid account_id FK
        decimal signed_amount
    }
    IDEMPOTENCY_KEYS {
        uuid user_id FK
        string key
        string request_hash
        string status
    }
```

## Decisiones por cerrar

- Representación monetaria: `NUMERIC` con escala fija o unidades menores enteras.
- Cómo asociar depósitos de prueba a su cuenta interna de contrapartida.
- Restricciones SQL que garanticen unicidad y estados válidos.
- Política de retención de claves de idempotencia.
