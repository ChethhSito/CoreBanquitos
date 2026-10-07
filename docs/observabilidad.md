# Observabilidad local

La fase 5 agrega un `X-Correlation-ID` por petición HTTP. Si el cliente envía un UUID válido, la API lo conserva; de lo contrario genera uno. La respuesta devuelve el mismo encabezado y los logs incluyen `request`, `trace` y `span`. El `eventId` del outbox permite seguir la publicación y el consumo entre procesos. No se registran tokens, contraseñas ni importes en los logs nuevos.

## Levantar con Podman en Windows

Con Podman y RabbitMQ iniciados, ejecuta una vez:

```powershell
.\scripts\start-observability-podman.ps1
```

Detén los procesos Java anteriores y arranca esta versión en dos terminales:

```powershell
# Terminal de la API
.\scripts\start-local.ps1 -Messaging -Tracing

# Terminal del worker
.\scripts\start-notification-worker.ps1 -Tracing
```

Abre [Grafana](http://localhost:3000) (`admin` / `bankcore-local-only`) y busca la carpeta **BankCore** y el dashboard **BankCore - Operacion local**. [Prometheus](http://localhost:9090/targets) debe mostrar el objetivo `bankcore-metrics` como **UP**. [Jaeger](http://localhost:16686) muestra las trazas bajo `bankcore-api` y `bankcore-notification-worker`. Haz un depósito o transferencia desde Swagger para generar datos. El switch `-Tracing` activa la exportación de trazas y métricas; sin él, la aplicación puede funcionar aunque el stack de observabilidad esté detenido.

El dashboard muestra operaciones por minuto, eventos pendientes, notificaciones simuladas, reintentos y latencia HTTP p95. La API expone métricas en `/actuator/prometheus` para diagnóstico con JWT; ese endpoint no es público. La API y el worker envían sus métricas por OTLP al colector local; Prometheus consulta ese colector. El worker no abre un servidor HTTP. Los contadores de notificaciones del dashboard proceden de PostgreSQL a través de la API. Si detienes o reinicias la API, los contadores en memoria empiezan desde cero; los totales guardados en PostgreSQL no.

Para identificar una petición concreta, copia `X-Correlation-ID` de la respuesta y búscalo en los logs de la API. Para seguir el evento asíncrono, consulta `outbox_events.id` a partir del ID de la transferencia y busca ese `eventId` en los logs del publicador y del worker. La transferencia y el worker pueden tener trazas distintas porque el outbox publica después del commit; el `eventId` las relaciona.

## Opciones

Si tienes un proveedor de Compose, también puedes usar `docker compose -f compose.observability.yaml up -d` o `podman compose -f compose.observability.yaml up -d`. La guía de Podman usa `podman run` porque esta PC no tiene proveedor de Compose. Detén los contenedores con `podman stop bankcore-grafana bankcore-prometheus bankcore-otel-collector bankcore-jaeger` y vuelve a iniciarlos con el script. Las URLs se publican solo en `127.0.0.1` para uso local.

En Podman para Windows, el contenedor no siempre puede consultar un puerto servido por una aplicación Windows. El envío OTLP hacia el colector evita esa dependencia de red.
