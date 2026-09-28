# Idempotencia en altas administrativas

`POST /v1/sites` y `POST /v1/visits/assign` exigen `Idempotency-Key`, un UUID que Android
genera antes del primer envío. La clave representa la operación completa, no un intento de
transporte.

## Semántica

- Primera clave y carga: crea el recurso y devuelve `201`.
- Misma clave y misma carga normalizada: devuelve `200` y el recurso original.
- Misma clave y otra carga: devuelve `409 CONFLICTO`.
- Clave ausente o que no es UUID: devuelve `400 SOLICITUD_INVALIDA`.

El id del documento es la propia clave. El repositorio guarda además
`_idempotencyFingerprint`, un SHA-256 del cuerpo ya validado y normalizado. Ese campo es
interno y ningún mapper lo expone.

En memoria, comprobar la huella y crear el recurso ocurre en una sección síncrona. En
Firestore, ambas acciones ocurren en una transacción sobre el documento determinista. Así,
dos peticiones simultáneas no pueden producir dos sedes o visitas.

La asignación busca primero un reenvío idempotente. Por eso sigue devolviendo la visita
original aunque la sede o el supervisor se hayan desactivado después de la primera
respuesta: el reenvío no vuelve a ejecutar la operación.

## Android

Los ViewModels conservan el UUID si la red falla y el administrador pulsa reintentar sin
cambiar los datos. Si cambia sede, supervisor, fecha o cualquier campo del alta, la carga
ya representa otra operación y se genera otra clave. Tras éxito también se rota.

No se añadió ninguna dependencia. No se desplegó ni modificó ningún recurso de GCP.
