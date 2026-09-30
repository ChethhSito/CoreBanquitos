# Reglas de negocio iniciales

## Alcance

Todas las cuentas y operaciones de la primera versión usan PEN. Los usuarios, cuentas y fondos son simulados.

## Invariantes

1. Un importe debe ser positivo y tener dos decimales como máximo.
2. La cuenta de origen y la de destino deben ser distintas, existir y estar activas.
3. El usuario que solicita una transferencia debe estar autorizado a operar la cuenta de origen.
4. Una transferencia confirmada nunca deja negativo el saldo disponible de origen.
5. Toda operación que mueve valor genera asientos cuya suma firmada es cero.
6. El saldo disponible y los asientos correspondientes se actualizan en la misma transacción de base de datos.
7. Los asientos confirmados son inmutables. Una corrección se registra mediante nuevos asientos compensatorios.
8. Una misma clave de idempotencia, para el mismo usuario y solicitud, no vuelve a mover dinero. La misma clave con otra solicitud produce un conflicto.
9. Una transferencia se considera completada cuando confirma la transacción SQL; las notificaciones ocurren después.

## Depósitos de prueba

Un depósito simulado se registra como una transferencia desde la cuenta interna `SYSTEM` hacia una cuenta `USER`. La cuenta interna puede quedar negativa y sirve de contrapartida para que el ledger global permanezca balanceado. Solo un flujo administrativo podrá crear estos depósitos.

## Casos críticos para verificar

- Transferencia normal, saldo exacto e insuficiente.
- Solicitud repetida con la misma clave y con payload diferente.
- Dos transferencias simultáneas que compiten por el mismo saldo.
- Rollback completo ante un fallo al escribir el ledger.
