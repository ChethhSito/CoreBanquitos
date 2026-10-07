# Ejemplo de correo real (sin activar)

Hoy `NotificationWorkerService` registra una fila en `notification_deliveries`: es una notificación simulada. Para enviar correo, el worker podría buscar el email del `recipientUserId` y llamar a un proveedor SMTP o HTTP. Por ejemplo, con `spring-boot-starter-mail` y credenciales obtenidas de variables de entorno:

```java
public void sendTransferNotice(TransferCompletedEvent event) {
    String address = users.emailFor(event.recipientUserId());
    SimpleMailMessage mail = new SimpleMailMessage();
    mail.setFrom("avisos@ejemplo.com");
    mail.setTo(address);
    mail.setSubject("Movimiento en tu cuenta");
    mail.setText("Se registró un movimiento de S/ " + event.amount()
            + " en tu cuenta. Referencia: " + event.transferId());
    mailSender.send(mail);
}
```

La configuración usaría `spring.mail.host`, `spring.mail.username` y `spring.mail.password`, con sus valores sensibles fuera de Git. El envío ocurre **después** del commit financiero, en el worker. Un fallo del correo no revierte la transferencia.

```properties
spring.mail.host=${BANKCORE_SMTP_HOST}
spring.mail.port=${BANKCORE_SMTP_PORT:587}
spring.mail.username=${BANKCORE_SMTP_USER}
spring.mail.password=${BANKCORE_SMTP_PASSWORD}
spring.mail.properties[mail.smtp.auth]=true
spring.mail.properties[mail.smtp.starttls.enable]=true
spring.mail.properties[mail.smtp.connectiontimeout]=5000
spring.mail.properties[mail.smtp.timeout]=3000
spring.mail.properties[mail.smtp.writetimeout]=5000
```

Para llevarlo a producción hay que añadir estados de entrega, reintentos y límites de frecuencia. Si el proveedor acepta el correo pero el worker falla antes de guardar `SENT`, el mensaje puede repetirse. Una clave de idempotencia basada en `eventId`, cuando el proveedor la soporte, ayuda a evitarlo; SMTP por sí solo no garantiza entrega exactamente una vez. También conviene usar una plantilla sin datos financieros innecesarios y registrar el identificador del proveedor, nunca credenciales ni contenido del mensaje.
