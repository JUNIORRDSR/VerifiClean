# Cómo probar VerifiClean

## Backend

```bash
cd backend && npm test
```

153 tests, sin emulador ni credenciales. Cubren:

| Archivo | Qué protege |
|---|---|
| `antifraude.test.ts` | Cada regla por separado y en combinación, incluidos los casos límite del margen de precisión |
| `geo.test.ts` | Haversine contra distancias reales de Bogotá, antimeridiano, puntos casi idénticos |
| `cursor.test.ts` | El cursor de paginación (un bug aquí no rompe nada visible: la lista responde 200 con la primera página, siempre) |
| `api.evidence.test.ts` | El endpoint crítico: marca en silencio, confirma sin veredicto, conserva idempotencia y usa hora de servidor |
| `api.visits.test.ts` | Autenticación, aislamiento entre supervisores, cierre de visita y contratos separados sin radio, dispositivo ni sospecha para el supervisor |
| `api.admin.test.ts` | Permisos, filtros, reporte y memoria de revisión de alertas |
| `api.catalog.test.ts` | Altas y bajas, identidad de cliente, idempotencia de sede/asignación y conservación del histórico |

Comprobar tipos sin ejecutar:

```bash
cd backend && npm run typecheck
```

## Backend en vivo con curl

```bash
cd backend && npm run dev
```

Arranca sembrado. Los tokens en modo desarrollo son `dev:<uid>`.

```bash
curl -H "Authorization: Bearer dev:u_sup_1" http://localhost:8080/v1/visits
```

```bash
curl -H "Authorization: Bearer dev:u_admin" "http://localhost:8080/v1/admin/visits?sospechosas=true"
```

Subir una evidencia limpia (dentro de la geocerca de `visit_demo_1`):

```bash
curl -X POST http://localhost:8080/v1/visits/visit_demo_1/evidence -H "Authorization: Bearer dev:u_sup_1" -H "X-Device-Id: pixel-de-carlos" -F "clienteEvidenciaId=$(uuidgen)" -F "lat=4.700570" -F "lng=-74.063660" -F "precisionMetros=12" -F "integrityToken=fake:confiable" -F "foto=@evidencia.jpg;type=image/jpeg"
```

Provocar las cuatro alertas a la vez: misma foto en otra visita, lejos, con mock GPS y
dispositivo comprometido:

```bash
curl -X POST http://localhost:8080/v1/visits/visit_demo_2/evidence -H "Authorization: Bearer dev:u_sup_1" -F "clienteEvidenciaId=$(uuidgen)" -F "lat=4.610000" -F "lng=-74.150000" -F "mockLocationReportada=true" -F "integrityToken=fake:no_confiable" -F "foto=@evidencia.jpg;type=image/jpeg"
```

La ruta del supervisor responde `201` sólo con la confirmación:

```json
{ "id": "...", "visitId": "visit_demo_2", "timestampServidor": "..." }
```

El veredicto se comprueba únicamente con una sesión administrativa en
`GET /v1/admin/visits/visit_demo_2`.

Los tokens simulados de integridad son `fake:confiable`, `fake:no_confiable` y
`fake:no_evaluado`. Omitir el campo produce `INTEGRIDAD_NO_EVALUADA`.

## Android

### Estado verificado

El wrapper (`gradlew` + `gradle-wrapper.jar`, Gradle 8.14.3) está generado y versionado,
así que `./gradlew` funciona sin instalar nada. Comprobado en esta máquina con el JDK 21
que trae Android Studio y SDK 36:

| Tarea | Resultado |
|---|---|
| `assembleDebug` | `app-debug.apk`, 24.7 MB |
| `testDebugUnitTest` | 205 tests, 0 fallos |
| `connectedDebugAndroidTest` | 22 tests, 0 fallos (emulador API 34) |
| `assembleRelease` | `app-release-unsigned.apk`, 3.9 MB, R8 activo |
| AVD local | Login de ambos roles, alta de cliente/sede, filtro «Ayer» y fuente al 150 % |

Si Gradle no encuentra el JDK, exporta `JAVA_HOME` al que trae Android Studio:
`C:\Program Files\Android\Android Studio\jbr`.

### Tests unitarios

```bash
cd android && ./gradlew testDebugUnitTest
```

JVM puros, sin emulador:

| Archivo | Qué protege |
|---|---|
| `AlmacenEvidenciaCifradaTest` | Cifrado AES-GCM: ida y vuelta, IV distinto por archivo, detección de manipulación, AAD cruzada, escritura atómica, limpieza de huérfanos |
| `DaosTest` | Consultas de Room contra SQLite real: orden de la cola, aislamiento por supervisor, exclusión de fallidas, promoción de `PREPARANDO` |
| `SincronizarEvidenciasWorkerTest` | La promesa entera: que una evidencia **nunca** se borra sin confirmación del servidor, en todos los modos de fallo |
| `ClasificadorSubidaTest` | La decisión reintentar/rendirse, que puede tanto descartar evidencia válida como martillear el servidor |
| `EvidenciaRepositoryImplTest` | El protocolo de dos fases y su orden: la foto se persiste **antes** de pedir GPS e integridad |
| `ContratoDtosTest` | JSON literal del backend → DTO. Detecta desalineaciones de contrato en segundos, no en el teléfono de un supervisor |
| `LlamadaApiTest` | La traducción de códigos HTTP a errores de dominio |
| `MapeadoresTest` | Que un backend más nuevo no rompa la app: valores desconocidos caen a uno conocido en vez de lanzar |
| `EstadoDetalleTest` | Que el botón de completar refleje las mismas condiciones que valida el servidor |
| `AutenticadorFalsoTest` | Que el token de desarrollo tenga el formato exacto que acepta el backend |
| `CapturaViewModelTest` | El cerrojo contra la doble captura, incluido un caso con 10 hilos compitiendo |
| `FormularioSedeTest` | El radio de la geocerca: los mismos límites 50–2000 m que el servidor, y la coma decimal del teclado colombiano |
| `SupervisoresViewModelTest` | Que el 409 del servidor se traduzca a "ya existe ese correo" y que el doble toque no cree dos usuarios |
| `AsignarVisitaViewModelTest` | Que un supervisor inactivo no reciba visitas, que las dos consultas vayan en paralelo y que encadenar asignaciones conserve sede y fecha |
| `ReportesViewModelTest` | La derivación de clientes agrupando sedes y que una consulta lenta que llega tarde no pise a la nueva |

Los ViewModels de administración comparten `dobles/CatalogoFalso`, que cuenta llamadas
simultáneas y puede retener respuestas en una compuerta. Sin eso no se distingue una
consulta en paralelo de dos encadenadas —ambas terminan igual— ni se puede provocar que un
resultado viejo aterrice después de uno nuevo, que es el bug que esas pantallas tienen más
cerca.

Los de `DaosTest` y `SincronizarEvidenciasWorkerTest` corren con Robolectric: levantan el
framework de Android dentro de la JVM, así que ejercitan SQLite real y el ciclo de vida
del Worker sin emulador — que en CI multiplicaría por diez el tiempo de la suite.

### Tests instrumentados (emulador)

```bash
cd android && ./gradlew connectedDebugAndroidTest
```

Requieren un emulador o dispositivo conectado. Crear el AVD una vez:

```bash
sdkmanager "system-images;android-34;google_apis;x86_64" && avdmanager create avd --name verificlean_test --package "system-images;android-34;google_apis;x86_64"
```

`google_apis` y no `google_apis_playstore`: la variante con Play Store bloquea la imagen
del sistema y quita `adb root`, que es lo que permite inspeccionar el almacenamiento
cifrado.

| Archivo | Qué protege |
|---|---|
| `AlmacenEvidenciaKeystoreTest` | Lo que sólo existe en un dispositivo: que la clave esté en `AndroidKeyStore`, que **`clave.encoded` sea `null`** (no extraíble ni con root), que sobreviva entre instancias, y que una foto de 4 MB pase por el TEE |
| `FlujoEvidenciaIntegracionTest` | Room real + Keystore real juntos: el ciclo `PREPARANDO → PENDIENTE → borrada`, y que la limpieza de huérfanos **no** borre la foto de una evidencia fallida |
| `LoginScreenTest` | La pantalla renderizada de verdad: botón apagado hasta tener ambos campos, mensajes de error, y el guard contra envíos duplicados con la petición en vuelo |

### Lo que sigue sin cubrir

**La captura con CameraX**. Lo que sí está probado es el cerrojo que impide la doble
captura (`CapturaViewModelTest`, en JVM, que es donde vivía el bug). Lo que no: la ruta que
va del sensor al `ImageProxy`, la recreación de la pantalla a mitad de captura y el
callback tardío del proveedor de cámara. Necesitan `ActivityScenario` con una cámara
controlada; la cámara emulada no es fiable para eso.

Compilar el APK de depuración:

```bash
cd android && ./gradlew assembleDebug
```

### En el emulador

1. Levanta el backend (`npm run dev`). El build de debug apunta a `10.0.2.2:8080`.
2. Arranca un emulador con Play Services (imagen *Google APIs*), API 26 o superior.
3. Instala y entra con `carlos@verificlean.test` y cualquier contraseña.

**Camino feliz:** abre una visita → *Tomar evidencia* → concede cámara y ubicación →
dispara. Antes de capturar, pon la ubicación del emulador cerca de la sede:
`⋮` → *Location* → lat `4.700543`, lng `-74.063644` → *Set Location*.

Entra como `admin@verificlean.test` y la visita aparece sin marca.

**Alerta de geocerca:** repite con la ubicación en lat `4.610000`, lng `-74.150000`. La
evidencia se sube igual (el servidor marca, no rechaza) y en el panel del admin la
tarjeta sale con fondo de alerta y el motivo escrito.

**Cola offline:** activa el modo avión antes de disparar. La app confirma que guardó la
evidencia; la bandeja muestra "1 evidencia esperando conexión". Desactiva el modo avión y
WorkManager la sube solo — o pulsa *Enviar ahora*.

**Imagen duplicada:** no se puede reproducir tomando dos fotos (los bytes del sensor
nunca son idénticos). Se prueba desde el backend con curl, subiendo el mismo archivo a
dos visitas distintas.

**Play Integrity:** en debug entra `ProveedorIntegridadFalso`, que devuelve
`fake:confiable`. Cambia `tokenSimulado` a `"fake:no_confiable"` en
`capture/ProveedorIntegridad.kt` para ver la alerta de dispositivo comprometido.

### En un dispositivo físico

El backend tiene que ser alcanzable desde el teléfono. La forma más limpia es un túnel
por USB, que evita tocar el código y funciona aunque el móvil esté en otra red:

```bash
adb reverse tcp:8080 tcp:8080
```

Después cambia `API_BASE_URL` del bloque `debug` en `app/build.gradle.kts` a
`http://localhost:8080/`. La `network_security_config` de depuración
(`app/src/debug/res/xml/`) ya permite HTTP en claro hacia `localhost`, `127.0.0.1` y
`10.0.2.2` — y sólo hacia esos: la variante release sigue siendo HTTPS-only, y apuntar
la app de debug a un servidor real por HTTP falla igual que en producción.

Si prefieres la IP de tu máquina en la red local (`http://192.168.x.x:8080/`), hay que
añadir ese dominio a la misma `network_security_config`.

Para probar mock GPS de verdad: instala una app de ubicación falsa, actívala en
*Opciones de desarrollador → Selecciona app de ubicación simulada* y captura. Debe
aparecer `MOCK_LOCATION`.
