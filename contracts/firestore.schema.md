# VerifiClean — Esquema Firestore (Native mode)

Fuente de verdad para backend y app Android. Todo cambio aquí obliga a revisar
`firestore.rules`, `firestore.indexes.json`, `openapi.yaml`, los DTOs de Retrofit
y las entidades Room.

Convención: campos en español (el dominio del cliente es en español), tipos en
notación TS. `Timestamp` = `google.firestore.Timestamp`.

---

## `users/{uid}`

El id del documento **es** el `uid` de Firebase Authentication. No se duplica el
uid dentro del documento.

| Campo | Tipo | Notas |
|---|---|---|
| `nombre` | `string` | |
| `email` | `string` | espejo del email de Firebase Auth |
| `rol` | `'admin' \| 'supervisor'` | fuente de autorización server-side |
| `deviceId` | `string \| null` | huella del dispositivo vinculado (ver abajo) |
| `activo` | `boolean` | `false` = no puede subir evidencia ni loguear. Se cambia con `PATCH /v1/users/{id}`; nunca se borra el documento, porque sus evidencias y visitas lo siguen citando |
| `creadoEn` | `Timestamp` | |
| `actualizadoEn` | `Timestamp` | |

**Vinculación de dispositivo (`deviceId`)** — anti-fraude por colusión: si un
supervisor le pasa sus credenciales a otro para que "haga la ronda por él", el
`deviceId` cambia. En el primer login se graba el `deviceId` recibido; en los
siguientes, si no coincide, la evidencia se marca con motivo
`DISPOSITIVO_NO_VINCULADO`. No bloquea (un cambio de celular es legítimo), solo
alerta — el admin reasigna borrando el campo.

---

## `sites/{siteId}`

Sede física de un cliente. Es lo que se geocerca.

| Campo | Tipo | Notas |
|---|---|---|
| `clienteId` | `string` | agrupador para `GET /v1/admin/reports?clienteId=` |
| `nombreCliente` | `string` | denormalizado para no leer una colección `clients` |
| `nombreSede` | `string` | ej. "Torre B - Piso 3" |
| `direccion` | `string` | |
| `lat` | `number` | grados decimales, WGS84 |
| `lng` | `number` | grados decimales, WGS84 |
| `radioPermitidoMetros` | `number` | geocerca. Default 150, mínimo 50 |
| `activo` | `boolean` | `false` = no admite visitas nuevas. Las ya asignadas siguen su curso. Se cambia con `PATCH /v1/sites/{id}` |
| `_idempotencyFingerprint` | `string` | interno; SHA-256 de la carga de creación, no se expone en la API |
| `creadoEn` / `actualizadoEn` | `Timestamp` | |

<!-- ponytail: cliente denormalizado en el site (clienteId + nombreCliente) en vez
     de colección `clients`. Techo: renombrar un cliente exige un batch update
     sobre sus sites. Se promueve a colección propia cuando haga falta editar
     datos de cliente (NIT, contacto, logo en reportes). -->

**Por qué el radio mínimo es 50 m:** por debajo de eso el error típico de
`PRIORITY_BALANCED_POWER_ACCURACY` (~30-60 m en interiores) genera falsos
positivos de fraude. Ver `docs/ANTIFRAUD.md`.

---

## `visits/{visitId}`

Unidad de trabajo. La crea el admin al asignar; el supervisor la ejecuta.

| Campo | Tipo | Notas |
|---|---|---|
| `siteId` | `string` | |
| `siteNombreCliente` | `string` | denorm — la lista del supervisor no lee `sites` |
| `siteNombreSede` | `string` | denorm |
| `siteClienteId` | `string` | denorm — filtro de reportes sin join |
| `siteLat` / `siteLng` | `number` | denorm — la app dibuja el destino offline |
| `siteRadioPermitidoMetros` | `number` | denorm — snapshot: si el admin cambia el radio, las visitas ya evaluadas no cambian de veredicto |
| `supervisorId` | `string` | uid |
| `supervisorNombre` | `string` | denorm |
| `estado` | `'pendiente' \| 'en_progreso' \| 'completada'` | |
| `fechaAsignada` | `Timestamp` | |
| `iniciadaEn` | `Timestamp \| null` | lo pone el servidor en la 1ª evidencia |
| `completadaEn` | `Timestamp \| null` | lo pone el servidor en `PATCH /complete` |
| `checklist` | `ChecklistItem[]` | ver abajo |
| `evidenciaCount` | `number` | contador denorm |
| `tieneSospechosas` | `boolean` | denorm — **el filtro del admin depende de esto** |
| `revisionAlerta` | `map` | `{ estado, nota, revisadaPorId, revisadaPorNombre, revisadaEn }`; sólo se expone al admin |
| `_idempotencyFingerprint` | `string` | interno; SHA-256 de la carga de asignación, no se expone en la API |
| `creadoEn` / `actualizadoEn` | `Timestamp` | |

```ts
type ChecklistItem = {
  id: string          // estable, ej. "banos"
  pregunta: string
  respuesta: 'si' | 'no' | 'na' | null
  nota: string | null
}
```

**Por qué `tieneSospechosas` está denormalizado:** Firestore no hace joins ni
subconsultas. `GET /v1/admin/visits?sospechosas=true` sería, sin este campo, un
scan de `evidences` + N lecturas de `visits`. Con el flag es un solo `where`.
Se recalcula en la misma transacción que escribe la evidencia.

`revisionAlerta.estado` admite `pendiente`, `revisada`, `justificada` y `escalada`.
Autor y hora los fija el servidor. `justificada` y `escalada` requieren una nota de hasta
1000 caracteres. Cuando llega una evidencia sospechosa nueva a un caso cerrado, la misma
transacción que guarda la evidencia devuelve la revisión a `pendiente` y limpia la memoria
del cierre anterior. Los documentos históricos sin este mapa se leen como `pendiente`.

---

## `evidences/{evidenceId}`

Colección **raíz**, no subcolección de `visits`. Motivo: la detección de imagen
duplicada consulta por `hashSHA256` a través de todas las visitas; una
subcolección obligaría a un `collectionGroup` (índice extra, misma latencia) sin
ganar nada.

| Campo | Tipo | Notas |
|---|---|---|
| `visitId` | `string` | |
| `siteId` / `supervisorId` | `string` | denorm para reportes |
| `clienteEvidenciaId` | `string` | UUID generado en el dispositivo; clave de idempotencia |
| `storagePath` | `string` | `evidences/{visitId}/{evidenceId}.jpg` |
| `storageUrl` | `string` | URL firmada o `gs://`; el admin la resuelve al ver |
| `hashSHA256` | `string` | **calculado por el servidor** sobre los bytes recibidos |
| `hashClienteReportado` | `string \| null` | solo auditoría — si difiere del real, `HASH_NO_COINCIDE` |
| `lat` / `lng` | `number` | del `FusedLocationProviderClient` |
| `precisionMetros` | `number \| null` | `Location.accuracy` |
| `distanciaAlSitioMetros` | `number` | Haversine, calculado en el servidor |
| `timestampServidor` | `Timestamp` | **única fuente temporal confiable** |
| `timestampCliente` | `Timestamp \| null` | solo auditoría, nunca decide nada |
| `mockLocationReportada` | `boolean` | `Location.isMock` del cliente |
| `integrity` | `IntegrityResult` | ver abajo |
| `esSospechosa` | `boolean` | `motivosAlerta.length > 0` |
| `motivosAlerta` | `MotivoAlerta[]` | plural: una evidencia puede violar varias reglas |
| `checklistSnapshot` | `ChecklistItem[] \| null` | estado del checklist al subir |
| `creadoEn` | `Timestamp` | == `timestampServidor` |

```ts
type IntegrityResult = {
  veredicto: 'CONFIABLE' | 'NO_CONFIABLE' | 'NO_EVALUADO'
  deviceRecognition: string[]   // MEETS_DEVICE_INTEGRITY, MEETS_BASIC_INTEGRITY, ...
  appRecognition: string        // PLAY_RECOGNIZED | UNRECOGNIZED_VERSION | ...
  motivo: string | null         // por qué NO_EVALUADO / NO_CONFIABLE
}

type MotivoAlerta =
  | 'FUERA_DE_GEOFENCE'          // distancia > radioPermitidoMetros
  | 'IMAGEN_DUPLICADA'           // hashSHA256 ya existe en otra visita
  | 'DISPOSITIVO_NO_CONFIABLE'   // Play Integrity: root / bootloader abierto
  | 'MOCK_LOCATION'              // ubicación simulada declarada por Android
  | 'PRECISION_GPS_BAJA'         // precisionMetros > radio permitido → indecidible
  | 'DISPOSITIVO_NO_VINCULADO'   // deviceId ≠ users.deviceId
  | 'HASH_NO_COINCIDE'           // bytes recibidos ≠ hash que declaró el cliente
  | 'INTEGRIDAD_NO_EVALUADA'     // no llegó token de Play Integrity
  | 'TOKEN_INTEGRIDAD_REUTILIZADO' // el nonce no corresponde a esta foto ni a esta visita
  | 'TOKEN_INTEGRIDAD_CADUCADO'  // emitido fuera de la ventana de validez
  | 'CHECKLIST_ALTERADO'         // preguntas distintas a las asignadas a la visita
  | 'SIN_UBICACION'              // el dispositivo no consiguió ubicación al capturar
```

`SIN_UBICACION` no es lo mismo que `FUERA_DE_GEOFENCE`: no prueba presencia, pero
tampoco la desmiente. Antes la app mandaba `0,0` en ese caso y el servidor calculaba una
distancia de 8.000 km que el panel del admin mostraba como si fuera una medición real.

`esSospechosa` **nunca** rechaza la subida. La evidencia se guarda siempre y se
marca. Rechazar le enseñaría al defraudador exactamente qué ajustar hasta pasar.

---

---

## `hashesEvidencia/{sha256}`

Colección de control, sin datos de negocio. **El id del documento es el propio hash.**

| Campo | Tipo | Notas |
|---|---|---|
| `visitId` | `string` | visita que reservó el hash primero |
| `creadoEn` | `Timestamp` | |

**Por qué existe.** Detectar imágenes duplicadas con una consulta normal deja una ventana
de carrera: dos subidas simultáneas de la misma foto a visitas distintas consultan antes
de que la otra exista, ninguna ve nada, y las dos entran sin marcar. Como tocan documentos
de visita diferentes, Firestore tampoco genera contención que fuerce un reintento.

Un documento con id determinista mueve esa decisión dentro de una transacción sobre un
documento concreto: el segundo en llegar encuentra el del primero. Repetir la misma foto
dentro de la **misma** visita no cuenta como duplicado.

**Sin lectura para nadie.** No aparece en `firestore.rules` porque la regla `match
/{document=**}` la deja denegada por defecto, que es lo correcto: sólo la escribe y la lee
el backend con el Admin SDK, y exponerla permitiría a un supervisor comprobar si un hash
ya está registrado antes de intentar subirlo.

**Crece indefinidamente.** Es un documento minúsculo por evidencia. Si algún día molesta,
se le pone una regla de TTL — pero sólo con una retención mayor que la ventana en la que
tenga sentido detectar una foto reutilizada.

---

## Índices — qué consulta paga cada uno

`firestore.indexes.json` va sin comentarios (el Firebase CLI rechaza claves
desconocidas). La correspondencia es esta; si un endpoint muere, su índice se
borra — cada índice cuesta almacenamiento y latencia en **cada escritura**.

| Colección | Campos | Lo pide |
|---|---|---|
| `visits` | `supervisorId, estado, fechaAsignada↓` | `GET /v1/visits?supervisorId=&estado=` |
| `visits` | `supervisorId, fechaAsignada↓` | `GET /v1/visits?supervisorId=` sin estado |
| `visits` | `siteId, fechaAsignada↓` | `GET /v1/admin/visits?siteId=` |
| `visits` | `tieneSospechosas, fechaAsignada↓` | `GET /v1/admin/visits?sospechosas=true` |
| `visits` | `siteId, tieneSospechosas, fechaAsignada↓` | los dos filtros juntos |
| `visits` | `estado, fechaAsignada↓` | `GET /v1/admin/visits?estado=` |
| `visits` | `siteClienteId, fechaAsignada↑` | `GET /v1/admin/reports` (rango de fechas) |
| `evidences` | `visitId, timestampServidor↑` | evidencias del detalle, en orden |
| `evidences` | `esSospechosa, timestampServidor↓` | alertas recientes del reporte |
| `sites` | `clienteId, nombreSede↑` | listado de sedes del admin |

**Exenciones (`fieldOverrides`)** — evitan trabajo de indexado inútil por escritura:

- `evidences.hashSHA256`: sólo se consulta por igualdad → basta el índice
  ascendente; se apagan el descendente y el de arrays.
- `visits.checklist`, `evidences.checklistSnapshot`, `evidences.integrity`:
  estructuras anidadas que nunca se filtran. Sin exención, Firestore indexa cada
  subcampo de cada elemento en cada escritura.

---

## Invariantes

1. `evidences.timestampServidor` lo escribe el servidor. Nada del cliente lo toca.
2. `evidences.hashSHA256` se calcula sobre los bytes que llegaron al servidor.
3. `visits.tieneSospechosas == OR(evidences[visitId].esSospechosa)` — se mantiene
   en la misma transacción que crea la evidencia.
4. `visits.evidenciaCount == count(evidences where visitId)` — misma transacción.
5. Una visita `completada` no acepta evidencias nuevas (409).
6. `visits.siteRadioPermitidoMetros` es un snapshot: cambiar `sites` no reescribe
   veredictos históricos.
7. Ningún cliente escribe en Firestore directamente. Todo pasa por la API — las
   reglas de `firestore.rules` son la segunda línea de defensa, no la primera.
8. Las altas de sede y asignaciones usan como id de documento la `Idempotency-Key` UUID.
   La creación y la comparación de `_idempotencyFingerprint` ocurren en una transacción.
