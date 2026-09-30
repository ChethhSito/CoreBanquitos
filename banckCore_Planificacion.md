**BANKCORE**

**Core de Transferencias y Ledger Contable**

Guía de arquitectura, implementación local y portafolio para banca

Objetivo: construir un backend financiero simulado que demuestre consistencia, concurrencia, idempotencia, auditabilidad, mensajería y observabilidad sin depender de servicios de pago.

**Plan recomendado: monolito modular primero ? eventos y worker después ? observabilidad y carga al final**

Versión del documento: septiembre de 2026

# Cómo usar esta guía

Este documento no propone construir un banco real. El objetivo es crear un sistema educativo y demostrable que imite algunos de los problemas técnicos que aparecen en sistemas transaccionales: mantener el dinero consistente, evitar dobles cargos, manejar solicitudes repetidas, registrar auditoría y desacoplar procesos secundarios como las notificaciones.

**Idea central:** El valor del proyecto no está en tener muchas pantallas. Está en demostrar que puedes explicar por qué una transferencia sigue siendo correcta incluso cuando llegan varias solicitudes al mismo tiempo o una parte del sistema falla.

|  |  |
| --- | --- |
| **Bloque** | **Qué aprenderás / demostrarás** |
| Dominio financiero | Ledger de doble partida, saldos, reversos y reconciliación. |
| Base de datos | Transacciones ACID, bloqueos, constraints y consistencia. |
| API | REST, validaciones, idempotencia, errores y documentación OpenAPI. |
| Arquitectura | Monolito modular, hexagonal, outbox y eventos. |
| Seguridad | JWT, refresh tokens, roles, protección de secretos y auditoría. |
| Calidad | Tests unitarios, integración real con PostgreSQL y carga concurrente. |
| Operación | Logs, métricas, trazas, correlation IDs y dashboards. |

## Índice de contenidos

* 1. Visión y alcance del proyecto
* 2. Arquitectura propuesta
* 3. Stack local y gratuito
* 4. Modelo de dominio y base de datos
* 5. Ledger de doble partida
* 6. Saldo proyectado vs. ledger
* 7. Flujo de una transferencia
* 8. Estados de transferencia
* 9. Idempotencia
* 10. Concurrencia y doble gasto
* 11. Deadlocks y orden determinista
* 12. Transactional Outbox
* 13. Consumidor idempotente y entrega at-least-once
* 14. Arquitectura hexagonal y monolito modular
* 15. Seguridad, autenticación y auditoría
* 16. Correlation ID y observabilidad
* 17. Testing y Testcontainers
* 18. Pruebas de carga con k6
* 19. Reconciliación
* 20. API sugerida
* 21. Roadmap de implementación
* 22. Docker Compose y ejecución local
* 23. Estructura del repositorio
* 24. Qué mostrar en GitHub y en una entrevista
* 25. Mejoras futuras
* 26. Referencias técnicas

# 1. Visión y alcance del proyecto

BankCore será un backend de transferencias internas entre cuentas simuladas. El sistema permitirá registrar usuarios, crear cuentas, realizar depósitos de prueba, transferir dinero entre cuentas propias o de otros usuarios y consultar movimientos. Cada transferencia quedará respaldada por un ledger contable inmutable.

## 1.1 Qué sí incluye

* Cuentas financieras simuladas denominadas inicialmente en PEN.
* Transferencias internas entre cuentas del sistema.
* Ledger de doble partida y saldo proyectado.
* Idempotencia de solicitudes y control de concurrencia.
* Autenticación, autorización y auditoría.
* Publicación asíncrona de eventos mediante RabbitMQ.
* Worker independiente de notificaciones simuladas.
* Métricas, logs, trazas y pruebas de carga.

## 1.2 Qué NO necesitas implementar al inicio

* Tarjetas reales, pagos con bancos externos o conexión a redes financieras.
* Datos reales de clientes, KYC real, AML real o procesamiento de información sensible.
* Microservicios para cada módulo.
* Kubernetes, cloud o infraestructura de pago.
* Conversión de moneda real o tipos de cambio externos.

**Consejo de alcance:** En portafolio es preferible terminar una solución pequeña pero consistente y muy bien documentada antes que construir diez servicios incompletos.

# 2. Arquitectura propuesta

La primera versión debe ser un monolito modular. Esto significa que hay una sola aplicación desplegable, pero el código se divide en módulos con límites claros: identidad, cuentas, transferencias, ledger, outbox y auditoría. Más adelante, el worker de notificaciones sí puede ejecutarse como un proceso independiente.

**Código Mermaid — Arquitectura general**

flowchart LR

C[Cliente / Postman / Frontend] -->|HTTPS REST| API[BankCore API]

API --> AUTH[Identity]

API --> ACC[Accounts]

API --> TR[Transfers]

TR --> LED[Ledger]

TR --> OUT[Outbox]

AUTH --> DB[(PostgreSQL)]

ACC --> DB

TR --> DB

LED --> DB

OUT --> DB

PUB[Outbox Publisher] --> DB

PUB --> MQ[(RabbitMQ)]

MQ --> WORKER[Notification Worker]

API --> OTEL[OpenTelemetry]

WORKER --> OTEL

OTEL --> PROM[Prometheus]

PROM --> GRAF[Grafana]

![](data:image/png;base64...)La transferencia financiera termina cuando la transacción SQL se confirma. El envío de una notificación es un efecto secundario: puede ocurrir después y puede reintentarse sin volver a mover dinero.

# 3. Stack local y gratuito

|  |  |  |
| --- | --- | --- |
| **Necesidad** | **Tecnología propuesta** | **Motivo** |
| Lenguaje | Java 25 LTS | Versión LTS actual adecuada para un proyecto nuevo; evita depender de versiones no soportadas. |
| Framework | Spring Boot 4.1.x | Ecosistema empresarial, web, seguridad, persistencia, testing y mensajería. |
| Base de datos | PostgreSQL | Transacciones, constraints y row-level locking reales. |
| Migraciones | Flyway | Versiona el esquema junto con el código. |
| Mensajería | RabbitMQ | Fácil de ejecutar localmente y suficiente para eventos del proyecto. |
| API | REST + OpenAPI | Contrato visible y testeable. |
| Pruebas | JUnit 5 + Mockito + Testcontainers | Combina pruebas de dominio y pruebas contra PostgreSQL real. |
| Carga | k6 OSS | Permite generar concurrencia local desde scripts JavaScript/TypeScript. |
| Contenedores | Docker + Docker Compose | Levanta todo el entorno con pocos comandos. |
| Observabilidad | OpenTelemetry + Prometheus + Grafana | Trazas, métricas y dashboards sin SaaS de pago. |

**Nota de versiones:** A septiembre de 2026, Oracle identifica Java 25 como LTS y la documentación estable de Spring Boot señala 4.1.1 como versión estable. Puedes fijar versiones exactas en tu repositorio para que el proyecto sea reproducible.

# 4. Modelo de dominio y base de datos

El modelo separa la operación que ve el usuario (transfer) de la representación contable (ledger\_transaction y ledger\_entry). Esa separación es importante: una transferencia es una intención de negocio; el ledger es el registro financiero que explica cómo cambió el valor entre cuentas.

|  |  |
| --- | --- |
| **Tabla** | **Responsabilidad principal** |
| users | Identidad y estado del usuario. |
| accounts | Cuenta, moneda, estado y saldo proyectado. |
| transfers | Solicitud y estado de la transferencia. |
| ledger\_transactions | Agrupa los asientos de una operación contable. |
| ledger\_entries | Débitos/créditos inmutables de cada cuenta. |
| idempotency\_keys | Evita ejecutar dos veces una misma petición lógica. |
| outbox\_events | Eventos que deben publicarse después del commit. |
| processed\_events | Eventos ya consumidos por un worker. |
| refresh\_tokens | Sesiones renovables; almacenar hash, no token en claro. |
| audit\_logs | Registro de acciones sensibles y actor responsable. |

**Código Mermaid — Modelo ER simplificado**

erDiagram

USERS ||--o{ ACCOUNTS : owns

USERS ||--o{ REFRESH\_TOKENS : has

USERS ||--o{ AUDIT\_LOGS : performs

ACCOUNTS ||--o{ TRANSFERS : source

ACCOUNTS ||--o{ TRANSFERS : destination

TRANSFERS ||--|| LEDGER\_TRANSACTIONS : produces

LEDGER\_TRANSACTIONS ||--|{ LEDGER\_ENTRIES : contains

ACCOUNTS ||--o{ LEDGER\_ENTRIES : receives

TRANSFERS ||--o| OUTBOX\_EVENTS : emits

USERS {

uuid id PK

string email

string password\_hash

string status

}

ACCOUNTS {

uuid id PK

string account\_number

string currency

decimal available\_balance

string status

bigint version

}

TRANSFERS {

uuid id PK

uuid source\_account\_id FK

uuid destination\_account\_id FK

decimal amount

string currency

string status

}

LEDGER\_TRANSACTIONS {

uuid id PK

uuid transfer\_id FK

timestamp created\_at

}

LEDGER\_ENTRIES {

uuid id PK

uuid ledger\_transaction\_id FK

uuid account\_id FK

string entry\_type

decimal amount

}

![](data:image/png;base64...)**Dinero y tipos numéricos:** Nunca uses float/double para importes monetarios. Una opción simple en PostgreSQL es NUMERIC con escala fija. Otra es almacenar unidades menores como enteros. Elige una convención y documenta por qué.

![](data:image/png;base64...)

# 5. Ledger de doble partida

En un sistema de doble partida, una operación económica se explica mediante al menos dos entradas relacionadas. Para una transferencia interna de S/ 100, el valor sale de una cuenta y entra a otra; el conjunto de asientos debe quedar balanceado.

|  |  |  |
| --- | --- | --- |
| **Cuenta** | **Tipo de asiento** | **Importe conceptual** |
| Cuenta A (origen) | DEBIT | -100.00 PEN |
| Cuenta B (destino) | CREDIT | +100.00 PEN |
| Total del conjunto | Balance | 0.00 PEN |

**Importante:** En contabilidad formal, “débito” y “crédito” no significan universalmente “restar” y “sumar”. Para este proyecto puedes usar una convención interna firmada, pero debes documentarla con claridad.

**Código Mermaid — Doble partida**

flowchart LR

T[Transferencia S/ 100] --> LTX[Ledger Transaction]

LTX --> E1[Entry 1: Cuenta A

DEBIT 100]

LTX --> E2[Entry 2: Cuenta B

CREDIT 100]

E1 --> B{Balance contable}

E2 --> B

![](data:image/png;base64...) B -->|DEBIT = CREDIT| OK[Transacción balanceada]

Regla de diseño recomendada: ledger\_entries es append-only. Una entrada confirmada no se “corrige” con UPDATE o DELETE. Si hay una corrección de negocio, se crea una operación compensatoria o reverso que deja rastro de lo ocurrido.

# 6. Saldo proyectado vs. ledger como fuente de verdad

Aquí hay dos conceptos que suelen confundirse. El ledger puede ser la fuente de verdad histórica y, al mismo tiempo, accounts.available\_balance puede existir para responder rápido. El saldo de la tabla accounts funciona como una proyección materializada: una copia calculada y mantenida transaccionalmente.

|  |  |
| --- | --- |
| **Concepto** | **Uso** |
| Ledger | Explica de dónde salió cada cambio de dinero y permite reconstruir el saldo. |
| Saldo proyectado | Permite responder “¿cuánto tengo disponible?” sin sumar miles de entradas. |
| Reconciliación | Comprueba periódicamente que la proyección coincide con el saldo derivado del ledger. |

**Ejemplo:** Si una cuenta tiene 200 000 movimientos, recalcular SUM(ledger\_entries) en cada consulta puede ser costoso. Guardar available\_balance acelera la lectura, siempre que se actualice dentro de la misma transacción que crea el ledger.

# 7. Flujo de una transferencia

Una transferencia debe tratarse como una unidad atómica: o se guardan todos sus efectos financieros o no se guarda ninguno. PostgreSQL permite hacer esto mediante una transacción de base de datos.

1. Validar autenticación, formato, monto, moneda y que origen y destino sean distintos.
2. Comprobar o reservar la clave de idempotencia.
3. Abrir una transacción SQL.
4. Bloquear las cuentas involucradas en un orden determinista.
5. Volver a leer saldos y estados ya bajo bloqueo.
6. Comprobar saldo suficiente y reglas de negocio.
7. Crear transfer, ledger\_transaction y ledger\_entries.
8. Actualizar saldos proyectados.
9. Crear outbox\_event dentro de la misma transacción.
10. Hacer COMMIT y devolver la respuesta.

**Código Mermaid — Secuencia de transferencia**

sequenceDiagram

actor U as Cliente

participant API as TransferController

participant APP as TransferService

participant IDEM as IdempotencyStore

participant DB as PostgreSQL

U->>API: POST /transfers + Idempotency-Key

API->>IDEM: buscar/reservar clave

alt respuesta previa existe

IDEM-->>API: respuesta almacenada

API-->>U: misma respuesta

else nueva solicitud

API->>APP: execute(command)

APP->>DB: BEGIN

APP->>DB: SELECT accounts FOR UPDATE

APP->>APP: validar saldo y reglas

APP->>DB: INSERT transfer

APP->>DB: INSERT ledger transaction + entries

APP->>DB: UPDATE projected balances

APP->>DB: INSERT outbox\_event

APP->>DB: COMMIT

APP-->>API: TransferCompleted

API->>IDEM: guardar resultado

API-->>U: 201 Created

end

![](data:image/png;base64...)

# 8. Estados de transferencia

Los estados ayudan a diferenciar “recibí una solicitud” de “el dinero ya quedó confirmado”. Para una primera versión puedes mantener la máquina simple. Evita crear estados que no tengan un significado operativo claro.

|  |  |
| --- | --- |
| **Estado** | **Significado** |
| PENDING | Solicitud creada pero aún no finalizada. |
| PROCESSING | Operación en ejecución cuando el diseño realmente lo necesita. |
| COMPLETED | La operación financiera confirmó su commit. |
| FAILED | No se realizó el movimiento financiero. |
| REVERSED | Se registró una operación compensatoria que revierte el efecto anterior. |

**Código Mermaid — Máquina de estados**

stateDiagram-v2

[\*] --> PENDING

PENDING --> PROCESSING

PROCESSING --> COMPLETED: commit correcto

PROCESSING --> FAILED: validación o rollback

COMPLETED --> REVERSED: operación compensatoria

FAILED --> [\*]

REVERSED --> [\*]

COMPLETED --> [\*]

![](data:image/png;base64...)

# 9. Idempotencia

Idempotencia significa que repetir la misma intención no debe generar un segundo efecto financiero. Esto es necesario porque una aplicación puede reintentar una petición si pierde la conexión antes de recibir la respuesta, aunque el servidor sí haya procesado la transferencia.

El cliente envía un identificador único, por ejemplo el header Idempotency-Key. El backend persiste esa clave junto con una huella del request y el resultado de la primera ejecución.

|  |  |
| --- | --- |
| **Campo** | **Por qué existe** |
| key | Identificador enviado por el cliente. |
| user\_id | Evita colisiones entre usuarios. |
| request\_hash | Detecta que la misma key se está reutilizando con otro payload. |
| status | IN\_PROGRESS / COMPLETED / FAILED según tu diseño. |
| response\_status | HTTP devuelto originalmente. |
| response\_body | Respuesta que puede devolverse en el reintento. |
| expires\_at | Permite aplicar una política de retención. |

**Caso crítico:** Si key=ABC se usó para transferir S/ 100 y después llega key=ABC con S/ 900, no debes ejecutar la nueva operación ni fingir que es idéntica. El request\_hash permite devolver un conflicto.

**Código Mermaid — Flujo de idempotencia**

flowchart TD

A[POST /transfers] --> B{Idempotency-Key existe?}

B -->|No| C[Reservar key como IN\_PROGRESS]

C --> D[Procesar transferencia]

D --> E[Guardar respuesta]

E --> F[Devolver resultado]

B -->|Sí| G{Request hash coincide?}

G -->|No| H[409 Conflict]

G -->|Sí| I{Resultado ya existe?}

I -->|Sí| J[Devolver respuesta original]

I -->|No| K[Responder que está en proceso o aplicar política de espera]

![](data:image/png;base64...)

# 10. Concurrencia y prevención del doble gasto

La concurrencia aparece cuando dos solicitudes intentan modificar el mismo saldo casi al mismo tiempo. El error clásico ocurre si ambas leen el saldo antes de que alguna lo actualice.

**Ejemplo de carrera:** Saldo inicial S/ 100. Llegan T1 por S/ 80 y T2 por S/ 80. Si ambas leen 100 y ambas se autorizan, el sistema aceptaría S/ 160 de gasto aunque solo existían S/ 100.

Una estrategia didáctica y robusta para este proyecto es el bloqueo pesimista con SELECT ... FOR UPDATE. PostgreSQL bloquea las filas seleccionadas para impedir actualizaciones concurrentes conflictivas hasta que termine la transacción.

BEGIN;

SELECT id, available\_balance

FROM accounts

WHERE id IN (:sourceId, :destinationId)

ORDER BY id

FOR UPDATE;

-- Validar saldo bajo bloqueo

-- Insertar transfer y ledger

-- Actualizar saldos

COMMIT;

**Código Mermaid — Dos transferencias compitiendo por la misma cuenta**

sequenceDiagram

participant T1 as Transacción T1

participant DB as PostgreSQL

participant T2 as Transacción T2

T1->>DB: SELECT Cuenta A FOR UPDATE

DB-->>T1: lock concedido

T2->>DB: SELECT Cuenta A FOR UPDATE

Note over T2,DB: T2 espera

T1->>DB: validar, actualizar y COMMIT

DB-->>T2: lock disponible

T2->>DB: releer saldo actualizado

alt saldo suficiente

T2->>DB: completar operación

else saldo insuficiente

T2->>DB: ROLLBACK / rechazo

end

![](data:image/png;base64...)

# 11. Deadlocks y orden determinista de bloqueos

Un deadlock ocurre cuando dos transacciones esperan recursos que la otra posee. Ejemplo: T1 transfiere A?B y bloquea A; al mismo tiempo T2 transfiere B?A y bloquea B. Luego T1 espera B y T2 espera A.

La prevención recomendada para este caso es bloquear siempre las cuentas en el mismo orden técnico, independientemente de cuál sea origen o destino. Por ejemplo: ordenar por UUID/ID y bloquear primero el menor, luego el mayor.

**Código Mermaid — Deadlock potencial y solución**

flowchart LR

subgraph Riesgo

T1[T1 A a B] --> LA[Lock A]

T2[T2 B a A] --> LB[Lock B]

LA --> WB[Espera B]

LB --> WA[Espera A]

WB --> DEAD[Deadlock]

WA --> DEAD

end

subgraph Solucion

S1[Ordenar IDs] --> S2[Lock menor ID]

S2 --> S3[Lock mayor ID]

S3 --> S4[Ejecutar transferencia]

![](data:image/png;base64...) end

**Además del orden:** La aplicación debe estar preparada para reintentar de forma controlada errores transitorios de concurrencia. El reintento debe combinarse con idempotencia para no duplicar efectos.

# 12. Transactional Outbox

El problema del “dual write” aparece cuando intentas confirmar datos en PostgreSQL y publicar un mensaje en RabbitMQ como dos acciones independientes. Puede ocurrir que la base confirme la transferencia y el proceso muera antes de publicar el evento.

Transactional Outbox evita ese hueco guardando el evento pendiente en PostgreSQL dentro del mismo COMMIT de la transferencia. Un publicador independiente lee outbox\_events y envía esos eventos a RabbitMQ después.

|  |  |
| --- | --- |
| **Sin outbox** | **Con outbox** |
| COMMIT transferencia ? proceso cae ? evento perdido. | COMMIT transferencia + outbox\_event en la misma transacción. |
| No existe una garantía atómica entre dos sistemas. | PostgreSQL garantiza atomicidad de ambos registros locales. |
| La notificación puede quedar desincronizada. | El publisher puede reintentar hasta publicar el evento. |

**Código Mermaid — Transactional Outbox**

sequenceDiagram

participant APP as TransferService

participant DB as PostgreSQL

participant PUB as OutboxPublisher

participant MQ as RabbitMQ

APP->>DB: BEGIN

APP->>DB: INSERT transfer + ledger

APP->>DB: UPDATE balances

APP->>DB: INSERT outbox\_event PENDING

APP->>DB: COMMIT

Note over APP,DB: movimiento financiero confirmado

loop polling/retry

PUB->>DB: buscar eventos PENDING

PUB->>MQ: publish TRANSFER\_COMPLETED

MQ-->>PUB: confirmación

PUB->>DB: marcar PUBLISHED

![](data:image/png;base64...) end

**Detalle útil:** Si tienes varios publishers, puedes seleccionar eventos pendientes con mecanismos de bloqueo y SKIP LOCKED para repartir trabajo sin que dos procesos publiquen el mismo lote de forma innecesaria. Aun así, el consumidor debe tolerar duplicados.

# 13. Consumidor idempotente y entrega at-least-once

En mensajería distribuida, asumir que un mensaje llegará exactamente una vez suele ser una mala simplificación. Un mensaje puede publicarse o entregarse más de una vez por reintentos. Por eso el sistema debe funcionar correctamente aunque el mismo event\_id sea recibido nuevamente.

El worker puede registrar los IDs ya procesados en processed\_events. Si recibe el mismo event\_id otra vez, reconoce que ese efecto ya fue aplicado y confirma el mensaje sin repetir la acción.

**Código Mermaid — Consumidor idempotente**

flowchart TD

A[Mensaje RabbitMQ] --> B{event\_id ya existe?}

B -->|Sí| C[No repetir efecto]

C --> D[ACK]

B -->|No| E[Procesar notificación]

E --> F[INSERT processed\_events]

F --> D

E -->|fallo recuperable| G[Retry]

G --> A

![](data:image/png;base64...) E -->|supera límite| H[Dead Letter Queue]

**Modelo mental:** Busca “at-least-once delivery + idempotent consumer”, no una promesa mágica de exactly-once de extremo a extremo.

# 14. Arquitectura hexagonal y monolito modular

Arquitectura hexagonal busca que las reglas de negocio no dependan directamente de HTTP, JPA o RabbitMQ. El dominio expresa conceptos del negocio; la capa de aplicación coordina casos de uso; la infraestructura conecta el sistema con tecnologías externas.

|  |  |
| --- | --- |
| **Capa** | **Ejemplos** |
| domain | Account, Transfer, Money, reglas e interfaces/ports. |
| application | TransferMoneyUseCase, servicios de aplicación, comandos y resultados. |
| infrastructure/web | Controllers, DTOs HTTP, manejo de errores. |
| infrastructure/persistence | Entidades JPA, repositorios SQL/JPA y mappers. |
| infrastructure/messaging | Publisher RabbitMQ, consumers y serialización de eventos. |

**Código Mermaid — Módulos del monolito**

flowchart TB

subgraph BankCore\_API[BankCore API - un despliegue]

ID[identity]

AC[accounts]

TR[transfers]

LE[ledger]

OB[outbox]

AU[audit]

SH[shared]

end

TR --> AC

TR --> LE

TR --> OB

ID --> AU

TR --> AU

AC --> AU

OB --> MQ[(RabbitMQ)]

src/main/java/com/bankcore/

??? identity/

??? account/

? ??? domain/

? ??? application/

? ??? infrastructure/

??? transfer/

? ??? domain/

? ??? application/

? ??? infrastructure/

??? ledger/

??? outbox/

??? audit/

![](data:image/png;base64...)??? shared/

**Por qué no microservicios desde el día 1:** Separar todo en servicios añade red, despliegue, observabilidad distribuida, consistencia entre bases y fallos parciales. Para un portafolio, primero demuestra límites de dominio y consistencia dentro de un monolito modular; después extrae solo aquello que tenga una razón clara.

# 15. Seguridad, autenticación y auditoría

La seguridad del proyecto debe ser proporcional al alcance. No necesitas imitar toda la seguridad de un banco real, pero sí demostrar hábitos profesionales.

* **Passwords:** hash con Argon2 o BCrypt; nunca texto plano.
* **Access token:** JWT de vida corta.
* **Refresh token:** rotación; almacenar un hash en base de datos y poder revocarlo.
* **Roles:** por ejemplo CUSTOMER, ADMIN y AUDITOR.
* **Logs:** nunca registrar contraseñas, JWT, refresh tokens ni secretos.
* **Datos:** el proyecto usa información simulada; no cargues datos bancarios reales.
* **Rate limiting:** opcional como mejora, sobre todo en login y creación de transferencias.

Audit log no es lo mismo que log técnico. El log técnico ayuda a operar la aplicación; el audit log responde quién hizo qué acción sensible y sobre qué recurso.

|  |  |
| --- | --- |
| **Campo de auditoría** | **Ejemplo** |
| actor\_id | usuario que ejecutó la acción |
| action | TRANSFER\_CREATED, ACCOUNT\_BLOCKED, LOGIN\_FAILED |
| resource\_type | TRANSFER / ACCOUNT / SESSION |
| resource\_id | UUID del recurso |
| correlation\_id | identificador de la solicitud completa |
| timestamp | momento de la acción |
| metadata | información no sensible necesaria para contexto |

# 16. Correlation ID y observabilidad

Un correlation ID es un identificador que acompaña una solicitud a través de diferentes capas y, cuando aplica, se propaga al evento asíncrono. Permite buscar en logs todo lo relacionado con una operación sin depender solo del usuario o del horario.

**Código Mermaid — Propagación del correlation ID**

flowchart LR

REQ[HTTP request

correlation\_id=abc] --> API[BankCore API]

API --> DB[(PostgreSQL)]

API --> OUT[Outbox event

correlation\_id=abc]

OUT --> MQ[(RabbitMQ)]

MQ --> W[Notification Worker]

API --> L1[Logs / traces]

W --> L2[Logs / traces]

L1 --> OBS[Observabilidad]

L2 --> OBS

Observabilidad responde tres preguntas distintas: ¿qué ocurrió?, ¿con qué frecuencia y rendimiento?, y ¿por dónde pasó la solicitud?

|  |  |
| --- | --- |
| **Señal** | **Ejemplos en BankCore** |
| Logs | transfer\_id, correlation\_id, resultado, error sin datos sensibles. |
| Métricas | transfers\_total, failed\_transfers, latency p95/p99, queue depth, DB pool. |
| Traces | HTTP ? TransferService ? PostgreSQL ? outbox ? worker. |

**Código Mermaid — Observabilidad local**

flowchart LR

API[BankCore API] --> OTEL[OpenTelemetry]

WORKER[Notification Worker] --> OTEL

OTEL --> PROM[Prometheus]

PROM --> GRAF[Grafana]

OTEL --> TRACE[Backend de trazas local opcional]

GRAF --> DEV[Dashboard del desarrollador]

![](data:image/png;base64...)

# 17. Testing y Testcontainers

No conviene perseguir un porcentaje de cobertura como único objetivo. La prioridad es cubrir invariantes del dominio y escenarios que podrían mover dinero incorrectamente. Un 80% de líneas no compensa la ausencia de una prueba de concurrencia.

## 17.1 Pirámide de pruebas sugerida

|  |  |
| --- | --- |
| **Tipo** | **Qué probar** |
| Unitarias | Money, reglas de transferencia, estados, validadores, mappers pequeños. |
| Aplicación | Casos de uso con repositorios mock cuando no importa SQL. |
| Integración | Transacciones, constraints, locks, repositorios y migraciones sobre PostgreSQL real. |
| End-to-end | HTTP real + DB + seguridad para caminos críticos. |
| Carga/concurrencia | Muchas solicitudes simultáneas sobre las mismas cuentas. |

## 17.2 Casos mínimos

* Transferencia normal.
* Saldo exacto.
* Saldo insuficiente.
* Monto cero y monto negativo.
* Misma cuenta origen/destino.
* Moneda incompatible.
* Cuenta bloqueada o inexistente.
* Misma idempotency key con mismo request.
* Misma key con payload diferente.
* 100 solicitudes concurrentes.
* Transferencias cruzadas A?B y B?A.
* Rollback si falla la creación del ledger.
* Outbox creado en el mismo commit.
* Mensaje duplicado no produce doble notificación.

Testcontainers permite iniciar PostgreSQL real desde los tests. Esto es especialmente útil porque H2 no replica de manera exacta bloqueos, dialecto SQL, constraints y comportamiento transaccional de PostgreSQL.

# 18. Pruebas de carga y concurrencia con k6

k6 no debe usarse solo para “hacer muchos requests”. Debes definir una propiedad que el sistema tiene que conservar. Para BankCore, la propiedad más interesante es que la suma del dinero y los saldos finales sean correctos bajo concurrencia.

## 18.1 Escenario estrella

Saldo inicial: A = S/ 5 000. Lanza 100 transferencias simultáneas de S/ 100 desde A hacia B. Como solo hay dinero para 50, deben completarse exactamente 50 operaciones y las demás deben fallar por saldo insuficiente. Ninguna debe dejar el saldo negativo.

|  |  |
| --- | --- |
| **Resultado esperado** | **Valor** |
| Transferencias completadas | 50 |
| Rechazadas por saldo insuficiente | 50 |
| Saldo final A | 0 PEN |
| Saldo final B | 5 000 PEN |
| Entradas ledger exitosas | 100 entradas: 50 débitos + 50 créditos |
| Balance global ledger | 0 según la convención firmada |

import http from 'k6/http';

import { check } from 'k6';

export const options = {

vus: 100,

iterations: 100,

};

export default function () {

const payload = JSON.stringify({

sourceAccountId: \_\_ENV.SOURCE\_ID,

destinationAccountId: \_\_ENV.DEST\_ID,

amount: 100,

currency: 'PEN'

});

const params = {

headers: {

'Content-Type': 'application/json',

'Authorization': `Bearer ${\_\_ENV.TOKEN}`,

'Idempotency-Key': `${\_\_VU}-${\_\_ITER}`,

},

};

const res = http.post('http://localhost:8080/api/v1/transfers', payload, params);

check(res, { 'status esperado': r => [201, 422].includes(r.status) });

}

# 19. Reconciliación

Reconciliar significa comparar dos representaciones del mismo valor para detectar divergencias. En este proyecto compararás el saldo proyectado de accounts con el saldo reconstruido desde ledger\_entries.

**Código Mermaid — Proceso de reconciliación**

flowchart TD

A[Seleccionar cuenta] --> B[Leer available\_balance]

A --> C[Calcular saldo desde ledger]

B --> D{Coinciden?}

C --> D

D -->|Sí| E[OK]

D -->|No| F[MISMATCH]

F --> G[Generar alerta / reporte de auditoría]

**No arreglar silenciosamente:** Si aparece una diferencia, la reconciliación debe reportarla. No conviene “corregir” automáticamente el ledger porque eso ocultaría el problema que justamente quieres auditar.

# 20. API sugerida

|  |  |  |
| --- | --- | --- |
| **Método** | **Endpoint** | **Objetivo** |
| POST | /api/v1/auth/register | Crear usuario de prueba. |
| POST | /api/v1/auth/login | Obtener access/refresh token. |
| POST | /api/v1/auth/refresh | Rotar refresh token. |
| GET | /api/v1/accounts | Listar cuentas del usuario. |
| GET | /api/v1/accounts/{id} | Ver cuenta y saldo. |
| POST | /api/v1/transfers | Crear transferencia idempotente. |
| GET | /api/v1/transfers/{id} | Consultar resultado. |
| GET | /api/v1/accounts/{id}/ledger | Consultar movimientos. |
| POST | /api/v1/admin/reconciliations | Ejecutar reconciliación controlada. |
| GET | /actuator/health | Health check técnico. |

## 20.1 Contrato de transferencia

POST /api/v1/transfers

Authorization: Bearer <access-token>

Idempotency-Key: 550e8400-e29b-41d4-a716-446655440000

Content-Type: application/json

{

"sourceAccountId": "uuid",

"destinationAccountId": "uuid",

"amount": 150.00,

"currency": "PEN"

}

## 20.2 Errores de dominio sugeridos

|  |  |  |
| --- | --- | --- |
| **HTTP** | **Código** | **Ejemplo de uso** |
| 400 | INVALID\_REQUEST | JSON/formato inválido. |
| 401 | UNAUTHORIZED | Token ausente/inválido. |
| 403 | FORBIDDEN | Cuenta no pertenece al usuario o rol insuficiente. |
| 404 | ACCOUNT\_NOT\_FOUND | Cuenta inexistente. |
| 409 | IDEMPOTENCY\_CONFLICT | Misma key con payload distinto. |
| 422 | INSUFFICIENT\_FUNDS | Saldo insuficiente. |
| 422 | SAME\_SOURCE\_DESTINATION | Origen y destino iguales. |
| 422 | CURRENCY\_MISMATCH | Monedas incompatibles. |

# 21. Roadmap de implementación

Construye por capas de riesgo. Primero consigue una transferencia correcta en una sola instancia; luego ataca concurrencia; después seguridad y mensajería; finalmente operación y performance.

|  |  |
| --- | --- |
| **Fase** | **Entregable verificable** |
| 0. Diseño | README inicial, ERD, reglas, ADRs y contrato OpenAPI. |
| 1. Core | Accounts + Transfers + Ledger con transacción SQL. |
| 2. Consistencia | Locks, orden determinista, idempotencia y constraints. |
| 3. Seguridad | JWT, refresh rotation, roles y audit log. |
| 4. Eventos | Outbox + RabbitMQ + notification worker + DLQ. |
| 5. Observabilidad | Correlation ID, logs, métricas, trazas y dashboard. |
| 6. Calidad | Testcontainers y suite crítica. |
| 7. Carga | k6, resultados reproducibles y análisis. |
| 8. Presentación | README ejecutivo, diagramas, demo y decisiones técnicas. |

**Código Mermaid — Roadmap**

flowchart LR

F0[0 Diseño] --> F1[1 Core]

F1 --> F2[2 Consistencia]

F2 --> F3[3 Seguridad]

F3 --> F4[4 Eventos]

F4 --> F5[5 Observabilidad]

F5 --> F6[6 Tests integración]

F6 --> F7[7 Carga k6]

F7 --> F8[8 Portfolio / Demo]

## 21.1 Definition of Done por fase

* No avanzar solo porque “compila”: cada fase necesita una prueba o evidencia.
* Las migraciones deben crear el entorno desde cero.
* Las decisiones no obvias deben escribirse como ADR.
* Los casos de error deben quedar documentados en OpenAPI.
* Cada riesgo crítico debe tener una prueba automatizada reproducible.

# 22. Docker Compose y ejecución local

El objetivo es que otra persona pueda clonar el repositorio y levantar el entorno sin crear cuentas cloud. No significa que todo deba ejecutarse dentro de Docker durante el desarrollo: también puedes correr Spring Boot desde el IDE y usar Docker solo para PostgreSQL/RabbitMQ.

**Código Mermaid — Despliegue local**

flowchart TB

DEV[PC del desarrollador]

subgraph DC[Docker Compose]

PG[(PostgreSQL)]

RMQ[(RabbitMQ)]

API[BankCore API]

NW[Notification Worker]

PR[Prometheus]

GR[Grafana]

OT[OTel Collector]

end

DEV --> API

API --> PG

API --> RMQ

RMQ --> NW

API --> OT

NW --> OT

OT --> PR

PR --> GR

# Flujo esperado del repositorio

docker compose up -d

./mvnw verify

k6 run tests/load/transfers.js

# URLs locales sugeridas

# API/Swagger: http://localhost:8080

# RabbitMQ UI: http://localhost:15672

# Grafana: http://localhost:3000

# 23. Estructura del repositorio

bankcore/

??? apps/

? ??? bankcore-api/

? ??? notification-worker/

??? docs/

? ??? architecture/

? ??? diagrams/

? ??? adr/

? ??? ADR-001-postgresql.md

? ??? ADR-002-pessimistic-locking.md

? ??? ADR-003-modular-monolith.md

? ??? ADR-004-transactional-outbox.md

??? docker/

??? monitoring/

? ??? grafana/

? ??? prometheus/

??? tests/

? ??? load/

? ??? transfers.js

??? docker-compose.yml

??? README.md

??? LICENSE

## 23.1 ADR: Architecture Decision Record

Un ADR es un documento corto que deja registrada una decisión de arquitectura y sus consecuencias. Esto es muy útil en entrevistas porque demuestra que no elegiste una tecnología solo porque estaba de moda.

# ADR-002: Pessimistic locking for transfers

## Context

Two concurrent transfers can spend the same balance.

## Decision

Lock involved account rows with SELECT ... FOR UPDATE.

Acquire locks in deterministic account-id order.

## Consequences

+ Easy to reason about financial correctness.

+ Works well for the portfolio workload.

- Contention can reduce throughput on hot accounts.

- Long transactions must be avoided.

# 24. Qué mostrar en GitHub y en una entrevista

El README debe enseñar el problema antes que la tecnología. Una persona que revise el repositorio debería entender en pocos minutos cuál era el riesgo y cómo lo resolviste.

* Diagrama de arquitectura y secuencia de una transferencia.
* Explicación breve del double-entry ledger y su inmutabilidad.
* Explicación reproducible de la prueba de doble gasto.
* Por qué usaste SELECT FOR UPDATE y cómo previenes deadlocks.
* Por qué existe Idempotency-Key y request\_hash.
* Por qué necesitas Transactional Outbox.
* Captura o export del dashboard de Grafana.
* Resultados de k6 con saldos finales esperados.
* ADRs de decisiones importantes.
* Un comando claro para levantar todo localmente.

## 24.1 Preguntas que deberías poder responder

* ¿Qué pasaría si el cliente reintenta una transferencia después de un timeout?
* ¿Qué pasaría si dos transferencias gastan la misma cuenta al mismo tiempo?
* ¿Qué pasa si la base confirma el dinero pero RabbitMQ está caído?
* ¿Qué ocurre si RabbitMQ entrega dos veces el mismo evento?
* ¿Cómo detectas un deadlock o una divergencia de saldo?
* ¿Por qué no hiciste microservicios para todo?
* ¿Qué cambiarías si una cuenta recibiera miles de operaciones por segundo?
* ¿Cómo demostrarías que el ledger continúa balanceado?

# 25. Mejoras futuras

Estas mejoras son opcionales. Agrégalas solo después de terminar el core y documentar pruebas. La intención es evolucionar el proyecto, no inflarlo.

|  |  |
| --- | --- |
| **Mejora** | **Valor que agrega** |
| Optimistic locking | Comparar estrategia con pesimistic locking usando version columns. |
| Límites por usuario/cuenta | Reglas de negocio y acumulados por ventana temporal. |
| Reversos formales | Modelar compensating transaction sin borrar historial. |
| Rate limiting | Protección de endpoints sensibles. |
| Circuit breaker en integraciones | Demostrar resiliencia cuando agregues un servicio externo simulado. |
| Schema/versionado de eventos | Evolucionar payloads de RabbitMQ sin romper consumidores. |
| CI con GitHub Actions | Ejecutar unit tests, integration tests y lint en cada push. |
| Mutation testing opcional | Evaluar calidad de tests, no solo cobertura de líneas. |

# Anexo A. Índice rápido de diagramas Mermaid incluidos

|  |  |
| --- | --- |
| **Diagrama** | **Sección** |
| Arquitectura general | 2 |
| Modelo ER simplificado | 4 |
| Doble partida | 5 |
| Secuencia de transferencia | 7 |
| Máquina de estados | 8 |
| Idempotencia | 9 |
| Concurrencia | 10 |
| Deadlock | 11 |
| Transactional Outbox | 12 |
| Consumidor idempotente | 13 |
| Monolito modular | 14 |
| Correlation ID | 16 |
| Observabilidad | 16 |
| Reconciliación | 19 |
| Roadmap | 21 |
| Despliegue Docker | 22 |

**Uso:** Puedes copiar cualquiera de los bloques Mermaid en GitHub Markdown compatible, Mermaid Live Editor u otra herramienta compatible. Si modificas nombres con símbolos especiales, revisa la sintaxis del renderer que estés usando.

# 26. Referencias técnicas

Fuentes oficiales consultadas para mantener actualizadas las recomendaciones tecnológicas de esta guía:

* **Oracle — Java support roadmap:** https://www.oracle.com/java/technologies/java-se-support-roadmap.html
* **Spring Boot — referencia y versiones estables:** https://docs.spring.io/spring-boot/
* **PostgreSQL — Explicit Locking / FOR UPDATE:** https://www.postgresql.org/docs/current/explicit-locking.html
* **RabbitMQ — Tutorials:** https://www.rabbitmq.com/tutorials
* **OpenTelemetry — Spring Boot starter:** https://opentelemetry.io/docs/zero-code/java/spring-boot-starter/
* **Testcontainers for Java — PostgreSQL module:** https://java.testcontainers.org/modules/databases/postgres/
* **Grafana k6 — Testing guides:** https://grafana.com/docs/k6/latest/testing-guides/

**Cierre:** Si desarrollas el proyecto en este orden, tendrás algo más útil que un CRUD: una demostración concreta de que entiendes consistencia transaccional, fallos parciales, eventos y operación de un backend financiero simulado.
