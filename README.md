# VerifiClean

App Android + API que verifican que un supervisor de aseo **estuvo físicamente** en la
sede de un cliente cuando dice que estuvo.

El problema no es registrar visitas — eso lo hace cualquier formulario. El problema es
que el registro sea difícil de falsificar: sin fotos viejas, sin GPS simulado, sin
"ya fui, confía en mí".

## Cómo lo resuelve

Cada evidencia lleva cinco comprobaciones, **todas del lado del servidor**:

| Comprobación | Qué impide |
|---|---|
| Distancia Haversine a la sede | Reportar desde casa |
| SHA-256 de los bytes recibidos | Reutilizar una foto de otra visita |
| Play Integrity | Root, emulador o APK reempaquetado |
| Nonce ligado a visita + evidencia + foto | Reutilizar un token de integridad legítimo |
| `Location.isMock` | Apps de ubicación falsa |
| Checklist contrastado con el asignado | Cerrar la visita con preguntas inventadas |
| Timestamp puesto por el servidor | Adelantar el reloj del teléfono |

Y dos decisiones que gobiernan todo lo demás:

**El servidor marca, nunca rechaza.** Una evidencia sospechosa se guarda igual, con sus
motivos, y aparece en el panel del admin. Devolver un 4xx convertiría la API en un oráculo
donde el defraudador ajusta un parámetro y reintenta hasta pasar.

**El supervisor nunca ve el veredicto de sus propias evidencias.** Ni el flag, ni los
motivos, ni la distancia. Devolvérselos en el cuerpo de un 201 sería el mismo oráculo con
otra ropa. Todo veredicto vive bajo `/v1/admin`.

Detalle completo del razonamiento: [docs/ANTIFRAUD.md](docs/ANTIFRAUD.md).

## Estructura

```
Verificlean/
├─ contracts/     Contrato compartido: OpenAPI, esquema Firestore, reglas, índices, seed
├─ backend/       API REST (Node + Express + TypeScript) para Cloud Run
├─ android/       App nativa (Kotlin + Compose + MVVM)
└─ docs/          Arquitectura, antifraude, pruebas, puesta en marcha en GCP
```

`contracts/` es la fuente de verdad. Cambiar un campo ahí obliga a cambiarlo en el
backend (`src/domain/`) y en la app (`data/remote/dto/`) a la vez.

## Arrancar en local (sin GCP)

Todo funciona sin una sola credencial de Google. El backend trae adaptadores falsos para
autenticación, base de datos, almacenamiento e integridad; la app trae los suyos, que
encajan con ellos.

**1. Backend**

```bash
cd backend && npm install && npm run dev
```

Arranca en `http://localhost:8080` con la base en memoria ya sembrada: 4 usuarios,
4 sedes y 5 visitas. Comprobación rápida:

```bash
curl -H "Authorization: Bearer dev:u_sup_1" http://localhost:8080/v1/visits
```

**2. App Android**

Abre `android/` en Android Studio y ejecuta la configuración `app` en un emulador.
El build de debug ya apunta a `http://10.0.2.2:8080/` (el host visto desde el emulador).

Usuarios de prueba — cualquier contraseña sirve en modo desarrollo:

| Correo | Rol |
|---|---|
| `admin@verificlean.test` | admin |
| `carlos@verificlean.test` | supervisor |
| `diana@verificlean.test` | supervisor |

**3. Provocar una alerta de fraude**

En el emulador: `⋮` → *Location* → pon una coordenada lejos de la sede, toma la foto y
mírala aparecer marcada en el panel del admin. Los pasos exactos, incluidos los de mock
GPS y dispositivo comprometido, están en [docs/TESTING.md](docs/TESTING.md).

## Pruebas

```bash
cd backend && npm test
```

153 tests: reglas antifraude una a una, Haversine contra distancias reales de Bogotá,
y la API entera por HTTP con adaptadores en memoria.

Android:

```bash
cd android && ./gradlew testDebugUnitTest
```

## Estado

| Parte | Estado |
|---|---|
| Contratos (OpenAPI, Firestore, reglas, índices) | Completo |
| Backend | 153 tests en verde + verificado en ejecución de punta a punta |
| App Android | Compila debug y release; 205 tests JVM + 22 instrumentados en verde |
| Despliegue en GCP | Pendiente, deliberadamente — ver [docs/RUNBOOK-GCP.md](docs/RUNBOOK-GCP.md) |

Verificado en esta máquina (JDK 21 de Android Studio, SDK 36, Gradle 8.14.3):

```
assembleDebug               app-debug.apk              24.7 MB
testDebugUnitTest           205 tests, 0 fallos
connectedDebugAndroidTest    22 tests, 0 fallos   (emulador API 34)
assembleRelease             app-release-unsigned.apk    3.9 MB   (R8 activo)
```

La app se probó además corriendo de verdad en el emulador contra el backend local: login,
bandeja del supervisor con datos reales, y confirmación de que la respuesta a ese rol **no
incluye** el veredicto antifraude.

El código pasó tres rondas de auditoría externa con Codex, y cada una encontró algo real:

- La API seguía filtrándole el veredicto antifraude al supervisor pese a que
  `docs/ANTIFRAUD.md` decía lo contrario.
- El índice de reportes estaba declarado ascendente mientras la consulta pedía descendente
  — habría fallado sólo contra Firestore real.
- `GET /v1/visits/{id}` devolvía `siteRadioPermitidoMetros`: el umbral con el que se juzga
  al supervisor. Se había quitado de la pantalla y se dio por resuelto, pero el valor
  seguía viajando en el JSON. Ahora la frontera son tipos separados
  (`VisitaDetalleSupervisor` / `VisitaDetalle`) y hay tres pruebas que la fijan.
- La navegación registraba las diez rutas para todos los roles y sólo cambiaba el destino
  inicial. Ahora el grafo se construye por rol.

Todo lo anterior está corregido y cubierto por tests. Quedan hallazgos medios abiertos —
sobre todo que un fallo de carga se presenta como un catálogo vacío, y que las altas de
sede y las asignaciones no llevan clave de idempotencia.

Que el release pase con R8 no es un detalle: valida las reglas de
`app/proguard-rules.pro` para kotlinx.serialization y Retrofit, que es donde este tipo de
app suele romperse sólo en producción, al deserializar la primera respuesta.

## Qué hace cada rol en la app

**Supervisor** — bandeja de visitas asignadas, detalle con checklist, captura de evidencia
y cola offline con reintento manual de lo que falló.

**Administrador** — cinco secciones en barra inferior, ordenadas por frecuencia de uso:

| Sección | Para qué |
|---|---|
| Alertas | Visitas y evidencias marcadas, con el motivo escrito |
| Asignar | Crear el trabajo: sede + supervisor + día |
| Sedes | Alta de sedes de cliente con coordenadas y radio de geocerca |
| Equipo | Alta de supervisores y administradores |
| Reportes | Cumplimiento por cliente y rango, con desglose por sede |

Hasta hace poco las cuatro últimas sólo existían como endpoints: el producto no se podía
operar sin curl. Ahora están en la app.

## Lo que falta para producción

1. Configurar GCP siguiendo [docs/RUNBOOK-GCP.md](docs/RUNBOOK-GCP.md).
2. `google-services.json` en `android/app/` y el número de proyecto real en
   `di/Modulos.kt` (`NUMERO_PROYECTO_CLOUD`).
3. Firma de release y subida a un canal interno de Play. Play Integrity exige
   distribución por Play: en un APK instalado por `adb`, **toda evidencia saldrá
   marcada** como `INTEGRIDAD_NO_EVALUADA`. Es correcto, pero conviene saberlo antes de
   ver el primer panel lleno de alertas.
4. Tests de la captura con CameraX: la ruta del sensor al `ImageProxy`, la recreación de
   pantalla a mitad de captura y el callback tardío del proveedor de cámara. El cerrojo
   contra la doble captura —que es donde estaba el bug— sí está cubierto.
