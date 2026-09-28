# Arquitectura

## Por qué Express y no NestJS

Cloud Run con `min-instances=0` significa arranque en frío en cada ráfaga de uso. NestJS
levanta un contenedor de inyección de dependencias y `reflect-metadata` antes de atender
la primera petición: entre 300 y 500 ms extra, para 10 endpoints sin dominio complejo.

Express + `zod` da validación tipada, arranque de ~80 ms e imagen más pequeña. La
estructura por capas (`routes` → `services` → `repos`) da la misma separación que darían
los módulos de Nest, sin el coste de arranque.

Si el proyecto creciera hasta necesitar interceptores, guards y módulos de verdad, migrar
sería reescribir el enrutado — las capas de abajo no cambian.

## Capas del backend

```
routes/     HTTP: parseo, validación zod, códigos de estado. Cero lógica de negocio.
services/   Reglas. `antifraude.ts` es pura (sin IO) para poder testear cada regla.
repos/      Único sitio que conoce Firestore. Dos implementaciones: firestore y memoria.
domain/     Tipos y schemas. Espejo de contracts/.
lib/        Haversine, hash, errores, logger.
```

Regla dura: **nada fuera de `repos/` importa `firebase-admin`**. Eso es lo que permite
que `npm test` corra sin emulador, sin Java y sin credenciales — y por tanto que los
tests se ejecuten de verdad en vez de estar rotos desde el segundo mes.

### Los cuatro adaptadores intercambiables

`AUTH_MODE`, `DB_MODE`, `STORAGE_MODE` e `INTEGRITY_MODE`. Cada uno tiene una versión
real y una falsa. `cargarEnv` **se niega a arrancar** con `NODE_ENV=production` si
alguno sigue en modo desarrollo, así que el atajo no puede llegar a producción por
descuido.

### Composición

`container.ts` es un `switch` y seis objetos. Sin framework de DI: metería arranque en
frío a un servicio que escala desde cero.

## Capas de Android

Módulo único `:app` con paquetes por capa. Multi-módulo Gradle para ~8 pantallas hace el
build más lento sin ganar nada; el corte por paquetes deja la separación lista si algún
día hace falta.

```
ui/          Compose + ViewModels. Dependen sólo de interfaces de domain/repository.
domain/      Modelos y puertos. Sin Android, sin Retrofit, sin Room.
data/
  remote/    Retrofit, DTOs espejo del OpenAPI, traductor de errores.
  local/     Room, DataStore, EncryptedFile.
  repository/ Implementaciones.
  sync/      WorkManager.
capture/     CameraX, ubicación, Play Integrity, autenticación.
```

### Offline-first, no cache-aside

La UI observa Room y **sólo** Room. La red escribe en Room y Room notifica. No hay dos
caminos por los que puedan llegar datos a la pantalla.

Esto no es una preferencia de estilo: el caso de uso real es un supervisor en el sótano
de un edificio. La bandeja tiene que abrirse ahí.

### El camino crítico de una evidencia

```
CameraX (bytes del sensor, EXIF intacto)
   ↓
SHA-256 en claro → cifrado AES-GCM → fila en Room, estado PREPARANDO
   ↓
[la foto ya es irrecuperablemente durable. Nada de lo que siga la pierde.]
   ↓
Ubicación puntual + token de Play Integrity (con nonce ligado a la foto)
   ↓
fila → PENDIENTE
   ↓
WorkManager (NetworkType.CONNECTED, backoff exponencial 30 s, máx 10 intentos)
   ↓
POST multipart → 2xx → se borran fila y archivo
```

**El protocolo de dos fases es la corrección más importante de todo el proyecto.** En la
primera versión el JPEG vivía en memoria mientras se pedían GPS y Play Integrity: si el
usuario pulsaba atrás o el sistema mataba el proceso en esos segundos, la foto desaparecía
sin dejar rastro. En una app cuya única promesa es "nunca perder una evidencia", esa
ventana era el peor bug posible.

Ahora la fase durable va primera. Si el proceso muere entre las dos, el worker encuentra
la fila `PREPARANDO` vieja y la promueve tal cual: se sube sin ubicación y el servidor la
marca. Una evidencia marcada y visible vale infinitamente más que una foto perdida.

Otras decisiones dentro de ese flujo:

**La foto sólo puede venir de CameraX.** No hay selector de galería ni intent a la cámara
del sistema. Un intent devolvería un `Uri` a un archivo del almacenamiento compartido, y
ese archivo pudo llegar ahí de cualquier forma — que es exactamente el fraude a impedir.

**La ubicación se pide después de disparar, no antes.** Pedirla antes dejaría el GPS
encendido mientras el supervisor encuadra, y la lectura correspondería a un instante
distinto al de la captura: justo el hueco que aprovecha quien fotografía desde la puerta
y luego camina.

**Los bytes no se recomprimen nunca.** Decodificar a `Bitmap` y volver a comprimir
borraría el EXIF y cambiaría el hash respecto a lo que salió del sensor.

### Por qué se cifra algo que ya está en almacenamiento interno

El almacenamiento interno protege frente a *otras apps*, no frente a alguien con el
teléfono en la mano y root o un backup ADB. Y lo que hay en esa carpeta es precisamente
lo que un supervisor deshonesto querría manipular: la foto que aún no ha llegado al
servidor, antes de que exista un hash de referencia.

**AES-256-GCM contra el Android Keystore directamente**, sin `androidx.security`. Esa
biblioteca (`EncryptedFile` / `MasterKey`) está deprecada sin reemplazo oficial —Google
recomienda usar el Keystore— y arrastraba Tink entero para cifrar archivos de pocos MB.
La implementación propia son ~100 líneas contra la API de plataforma, y con la clave
inyectada tras una interfaz para que toda la lógica (formato, IV, AAD, detección de
manipulación) se pruebe en JVM sin emulador.

Tres decisiones criptográficas que importan:

- **GCM y no CBC.** GCM es AEAD: detecta manipulación. Con CBC, alterar el ciphertext
  devuelve basura sin avisar.
- **El IV lo genera la plataforma**, nunca nosotros. Repetir un IV con GCM rompe la
  confidencialidad por completo.
- **`clienteEvidenciaId` como AAD.** Intercambiar dos archivos `.enc` en un dispositivo
  rooteado hace fallar el descifrado, en vez de subir la foto de otra visita como propia.

La clave se declara sin `setUserAuthenticationRequired`: WorkManager sube en segundo plano
con el teléfono posiblemente bloqueado, y exigir autenticación no sólo dejaría evidencias
sin subir, sino que invalidaría la clave al cambiar el patrón de bloqueo — perdiendo
evidencia válida.

Los backups están desactivados porque la clave no sale del dispositivo: restaurada en otro
teléfono, la carpeta sería ilegible.

### Las pantallas de administración

Hasta que existieron, el producto sólo se podía administrar por `curl`: no había forma de
dar de alta una sede, crear un supervisor ni asignar una visita desde la app. Son cinco
secciones — Alertas, Asignar, Sedes, Equipo, Reportes — colgadas de una `NavigationBar` en
`ui/navegacion/ContenedorAdmin.kt`.

**La barra vive en el contenedor, no dentro de cada pantalla.** Cada pantalla trae su
propio `Scaffold`, así que repetirla serían cinco copias del mismo bloque; y sobre todo, la
navegación entre secciones es una decisión de arquitectura de la información, no de cada
pantalla: rediseñar una no debería poder romper cómo se llega a las demás.

**`CatalogoRepository` va aparte de `AdminRepository`.** Uno responde "¿hubo fraude?" y el
otro "¿quién trabaja dónde?". Fusionarlos dejaría una interfaz que ningún ViewModel usa
entera.

**Lo administrativo no tiene caché local**, al revés que la bandeja del supervisor. El
admin trabaja en oficina con conexión, y aquí un dato viejo es caro en la dirección
contraria: un supervisor dado de baja que siguiera apareciendo en la lista de asignación
produciría visitas que nadie puede atender.

**La lista de clientes se deriva agrupando sedes.** No hay endpoint de clientes porque no
hay colección de clientes: el cliente vive denormalizado en cada sede. Inventar una entidad
en el servidor sólo para llenar un desplegable habría añadido una colección que mantener
sincronizada con dos campos de `sites`.

**El radio se valida en el cliente y en el servidor con los mismos límites (50–2000 m).**
No es defensa en profundidad, es respuesta inmediata: el servidor manda, y validarlo en el
formulario sólo evita el viaje. Los dos números están fijados en tests a ambos lados
precisamente porque son la clase de constante que se cambia en un sitio y se olvida en el
otro. Por debajo de 50 m el error típico de `PRIORITY_BALANCED_POWER_ACCURACY` marcaría
como fraude a supervisores que sí estuvieron en la sede; por encima de unos cientos, el
sistema deja de probar nada.

**El formulario de sede acepta coma decimal.** El teclado numérico de un teléfono en
español de Colombia ofrece coma, y `toDoubleOrNull` sólo entiende el punto: escribir la
coordenada como se escribe aquí devolvía "Latitud entre -90 y 90" sobre un valor
perfectamente válido, sin pista de por qué.

**Las visitas se asignan a las 8:00 locales.** Ninguna regla antifraude usa esa hora —el
servidor sella su propio timestamp al recibir la evidencia— pero una hora razonable hace
legible la lista del supervisor. Tras asignar se limpia el supervisor y se conservan la
sede y la fecha: planificar una jornada es repartir la misma sede entre varias personas.

## Consumo de batería

Ninguna de estas tres cosas existe en el proyecto, y es deliberado:

- **Sin `ACCESS_BACKGROUND_LOCATION`.** La ubicación se pide una vez, en primer plano, al
  disparar. Pedir el permiso dispararía además la revisión de Play en cada publicación.
- **Sin foreground service.** La sincronización es trabajo diferido de WorkManager, que
  el sistema agrupa y que respeta Doze.
- **Sin WebSockets ni polling.** Todo es transaccional: el supervisor abre una visita,
  sube evidencia, cierra.

`PRIORITY_BALANCED_POWER_ACCURACY` da 30-60 m usando wifi y torres antes que GNSS puro.
Para una geocerca de 150 m sobra, y `HIGH_ACCURACY` multiplicaría el gasto en cada
captura.

## Modelo de datos

Detalle completo en [`contracts/firestore.schema.md`](../contracts/firestore.schema.md).
Las tres decisiones que hay que conocer:

**`evidences` es colección raíz, no subcolección de `visits`.** La detección de
duplicados consulta por hash a través de todas las visitas; una subcolección obligaría a
un `collectionGroup` sin ganar nada.

**`visits.tieneSospechosas` está denormalizado.** Firestore no hace joins. Sin ese campo,
`GET /admin/visits?sospechosas=true` sería un scan de `evidences` más N lecturas de
`visits`. Se mantiene en la misma transacción que escribe la evidencia.

**`visits.siteRadioPermitidoMetros` es un snapshot.** Cambiar el radio de una sede no
reescribe veredictos ya emitidos: una visita se juzga con las reglas que estaban vigentes
cuando se asignó.

## Seguridad

- Ningún cliente escribe en Firestore. Todo pasa por la API. `firestore.rules` es la
  segunda línea de defensa, no la primera: por defecto deniega todo.
- El rol se lee de `users/{uid}` en cada petición, no de un custom claim. Un claim viaja
  en el JWT y sigue válido hasta que expire; degradar a un admin tiene que surtir efecto
  en la siguiente petición.
- `verifyIdToken(token, checkRevoked = true)`: desactivar a un supervisor le corta el
  acceso ya.
- El supervisor **no recibe** `hashSHA256` ni las URLs de las fotos en su propio detalle
  de visita. Con los hashes a la vista podría comprobar cuáles de sus imágenes ya están
  registradas y buscar una que pase el filtro de duplicados.
- Una visita ajena devuelve **404, no 403**. Distinguir "no existe" de "existe pero no es
  tuya" permite enumerar identificadores de visitas de otros supervisores.
