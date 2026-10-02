# Sesiones y refresh tokens

El `accessToken` es un JWT que dura 30 minutos y autoriza llamadas como `GET /api/v1/accounts`. El `refreshToken` dura 7 días y sirve únicamente para obtener un nuevo par de tokens. No es una clave de idempotencia.

## Probar en Swagger

1. `POST /api/v1/auth/login` devuelve `accessToken`, `expiresIn`, `refreshToken` y `refreshExpiresIn`.
2. Usa `accessToken` en **Authorize** para las rutas protegidas.
3. Envía `{"refreshToken":"<valor recibido>"}` a `POST /api/v1/auth/refresh`. Recibirás un nuevo access token y un **nuevo** refresh token.
4. Si repites el refresh token anterior, recibirás `401 INVALID_REFRESH_TOKEN`. Por seguridad, también se revoca la cadena de tokens renovados de esa sesión.
5. Envía el refresh token vigente a `POST /api/v1/auth/logout`. La respuesta es `204` y esa sesión ya no puede renovarse.

La migración `V4__refresh_tokens.sql` guarda solo un hash SHA-256 del token aleatorio, su usuario, su familia y las fechas de consumo, revocación y vencimiento. La rotación marca el token anterior como consumido e inserta el siguiente dentro de una misma transacción SQL. Una segunda petición con el token consumido revoca toda esa familia. Otras sesiones del mismo usuario tienen familias independientes.

## Cómo funcionaría en una web

Tras iniciar sesión, el frontend mantiene el access token en memoria y lo envía en las llamadas a la API. Cuando está por vencer, o cuando una llamada devuelve `401` por expiración, el frontend llama a `/auth/refresh`, actualiza el access token y reintenta la operación original una sola vez. El usuario continúa usando la página sin hacer nada.

Esta API de demostración entrega el refresh token en JSON para poder probarlo en Swagger. En una web real conviene adaptar el backend para colocarlo en una cookie `HttpOnly`, `Secure` y `SameSite`; el navegador enviaría esa cookie automáticamente y JavaScript no podría leer el token. Esa variante requiere configurar también CSRF y las políticas de origen según dónde se aloje el frontend. Evita guardar refresh tokens en `localStorage`.

Logout revoca la posibilidad de renovar la sesión y el frontend debe borrar su access token en memoria. Un JWT de acceso emitido antes del logout sigue siendo válido hasta que venza, como máximo 30 minutos en esta versión.
