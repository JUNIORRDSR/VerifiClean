# Brief de rediseño — Arquitectura de la información y UI/UX de VerifiClean

Documento de encargo para un agente autónomo. Contiene todo el contexto necesario:
producto, usuarios reales, restricciones que no se pueden violar, estado actual del
código, lo que ya está probado, lo que está roto y lo que falta.

**Léelo entero antes de tocar un archivo.** Buena parte de lo que parece un descuido de
diseño es una decisión antifraude deliberada, y desharla en nombre de la claridad sería
el peor resultado posible de este trabajo.

---

## 1. El encargo, en las palabras del dueño del producto

> "quiero que trabaje rediseñando todo el front o visible del proyecto, ya que se ve muy
> básico y simple y yo como usuario no entendí nada de cómo crear, iniciar sesión, revisar
> ni nada"

Léelo con cuidado, porque dice dos cosas distintas y la segunda es la importante.

1. **"Se ve muy básico y simple"** — problema estético. Real, pero secundario.
2. **"No entendí nada de cómo crear, iniciar sesión, revisar ni nada"** — el dueño del
   producto, que conoce el dominio mejor que nadie, **no logró operar su propia app**.

Eso no se arregla con sombras y gradientes. Es un fallo de arquitectura de la información
y de *affordances*: la app no enseña qué hay que hacer, en qué orden, ni por qué. El
objetivo de este trabajo es que un supervisor de aseo con un teléfono gama media y un
administrador que nunca ha visto la app puedan usarla sin que nadie les explique nada.

Si al terminar la app es preciosa y sigue sin poderse aprender sola, el trabajo falló.

---

## 2. Qué es VerifiClean

Una app Android + una API que verifican que un supervisor de una empresa de aseo
**estuvo físicamente** en la sede de un cliente cuando dice que estuvo.

El problema de negocio no es registrar visitas —eso lo hace cualquier formulario— sino que
el registro sea difícil de falsificar: sin fotos viejas, sin GPS simulado, sin "ya fui,
confía en mí". Las empresas de aseo facturan supervisión que a veces no ocurre, y el
cliente no tiene forma de saberlo.

### Los dos usuarios, de verdad

**Carlos, supervisor.** 40-55 años. Recorre 4-8 sedes al día en moto o transporte público.
Teléfono Android gama media, pantalla con arañazos, a menudo con guantes o con una mano
ocupada. Trabaja **en sótanos de parqueadero, cuartos de máquinas y bodegas sin señal**.
No es técnico. Le pagan por visita completada. Cada segundo que la app le hace perder es
dinero suyo. Si la app le falla, vuelve al papel.

**Ana, administradora.** Oficina, computador y teléfono. Revisa por la mañana lo que pasó
ayer y arma la jornada de hoy. Necesita responder tres preguntas rápido: *¿alguien
reportó una visita que no hizo?*, *¿quién va a dónde hoy?*, *¿cómo le facturo el
cumplimiento a este cliente?* Le importa mucho no acusar a nadie injustamente.

### Idioma y localización

Todo en **español de Colombia (es-CO)**. Coma decimal en el teclado numérico. Formato de
fecha `d MMM yyyy · HH:mm`. Moneda no aplica. Bogotá como referencia geográfica.

---

## 3. Restricciones que NO se pueden violar

Estas no son preferencias. Romper cualquiera invalida el producto.

### 3.1 La regla de oro: marcar, nunca rechazar — y nunca contárselo al supervisor

El servidor detecta evidencia sospechosa y la **guarda igual**, marcada, con sus motivos.
Nunca responde 4xx por sospecha. Y **nunca le dice al supervisor cuál fue el veredicto**.

El motivo es que cualquier respuesta que refleje el veredicto convierte la app en un
oráculo: el defraudador prueba una ubicación, lee el resultado, ajusta y reintenta hasta
que pase. Marcando en silencio, cada intento queda registrado con foto, ubicación y hora, y
el administrador ve el patrón completo.

**En la interfaz del supervisor no puede aparecer, ni en texto, ni en color, ni en icono,
ni en una animación distinta:**

- `esSospechosa`, `tieneSospechosas`, `motivosAlerta`
- `distanciaAlSitioMetros` — le dice cuánto le falta
- `siteRadioPermitidoMetros` — el umbral con el que se le juzga
- `hashSHA256` — le permitiría comprobar qué fotos ya están registradas
- Cualquier variación visual que dependa de esos datos: una tarjeta que se ponga ámbar,
  un check que tarde más, un mensaje de "recibido" distinto al de otra evidencia

Esta fuga se ha reintroducido **tres veces** en este proyecto. La última vez se quitó el
radio de la pantalla y se dio por resuelto, pero el campo seguía viajando en el JSON. Hay
tests que fallan si vuelve. **No los toques para que pasen.**

Lo que el supervisor sí puede ver de una evidencia: que se registró, a qué hora según el
servidor, y si está pendiente de subir. Nada más.

Detalle completo del razonamiento: [`docs/ANTIFRAUD.md`](ANTIFRAUD.md).

### 3.2 La foto sólo puede venir de CameraX

No hay selector de galería, ni intent a la cámara del sistema, ni "adjuntar archivo".
Un intent devolvería un `Uri` a un archivo del almacenamiento compartido, y ese archivo
pudo llegar ahí de cualquier forma — que es exactamente el fraude a impedir.

Si rediseñas la pantalla de captura, sigue siendo un visor de CameraX embebido. Puedes
mejorar el encuadre, las instrucciones, el feedback del disparo y la accesibilidad del
botón. No puedes añadir una segunda vía de entrada de imagen.

### 3.3 Offline-first no es un extra, es el caso de uso

Carlos trabaja sin señal la mitad del tiempo. La app guarda la foto cifrada en disco
**antes** de pedir GPS y token de integridad, y la cola de WorkManager la sube cuando
haya red.

La interfaz tiene que comunicar ese estado con total claridad, porque el miedo real de
Carlos es "hice el recorrido y la app perdió la foto":

- Cuántas evidencias están esperando conexión
- Que están a salvo aunque cierre la app o se le acabe la batería
- Cuáles fallaron definitivamente y qué puede hacer (hay un reintento manual)
- Nunca un spinner infinito ni un estado ambiguo entre "no hay datos" y "no pude cargar"

### 3.4 Batería y datos

- Sin `ACCESS_BACKGROUND_LOCATION`. La ubicación se pide **una vez**, en primer plano, al
  disparar la foto.
- Sin foreground service. Sin WebSockets. Sin polling. Sin auto-refresh en bucle.
- Sin cargar imágenes pesadas en listas del supervisor.

Un rediseño que meta animaciones continuas, sondeo periódico o precarga agresiva rompe el
segundo requisito del producto.

### 3.5 Stack cerrado

Kotlin + Jetpack Compose + Material 3, MVVM (`ui` / `viewmodel` / `domain` / `data`),
Hilt, Coroutines + Flow, Navigation Compose, Room, WorkManager, CameraX, Retrofit + OkHttp.

**No añadas dependencias** sin justificarlo explícitamente en el informe final. En
particular: nada de Lottie, nada de librerías de gráficas, nada de Accompanist para lo que
Compose ya hace. Si necesitas un gráfico para reportes, dibújalo con `Canvas`.

**No actives color dinámico (Material You).** Se quitó a propósito: el rojo de fraude y el
ámbar de error operativo son semánticos y no pueden reasignarse al fondo de pantalla del
usuario.

---

## 4. Estado actual del código

### 4.1 Estructura

```
Verificlean/
├─ contracts/     Fuente de verdad: openapi.yaml, firestore.schema.md, rules, índices, seed
├─ backend/       API REST (Node + Express + TypeScript). 135 tests en verde
├─ android/       App nativa. 181 tests JVM + 22 instrumentados en verde
└─ docs/          ARCHITECTURE.md, ANTIFRAUD.md, TESTING.md, RUNBOOK-GCP.md
```

Lee al menos [`docs/ARCHITECTURE.md`](ARCHITECTURE.md) y [`docs/ANTIFRAUD.md`](ANTIFRAUD.md)
antes de diseñar. `contracts/openapi.yaml` es el contrato exacto de qué datos hay
disponibles en cada pantalla — no inventes campos que la API no devuelve.

### 4.2 Pantallas que existen hoy

Todas en `android/app/src/main/java/com/verificlean/app/ui/`:

| Archivo | Rol | Qué hace |
|---|---|---|
| `pantallas/login/LoginScreen.kt` | ambos | Correo + contraseña |
| `pantallas/visitas/VisitasScreen.kt` | supervisor | Bandeja de visitas asignadas |
| `pantallas/visitas/DetalleVisitaScreen.kt` | supervisor | Checklist + botón de capturar + completar |
| `pantallas/captura/CapturaScreen.kt` | supervisor | Visor CameraX + permisos |
| `pantallas/admin/PanelAdminScreen.kt` | admin | Lista de visitas con filtros de estado y sospecha |
| `pantallas/admin/DetalleAdminScreen.kt` | admin | Evidencias con foto, ubicación, integridad, alertas |
| `pantallas/gestion/SedesScreen.kt` | admin | Listar y crear sedes |
| `pantallas/gestion/SupervisoresScreen.kt` | admin | Listar y crear usuarios |
| `pantallas/gestion/AsignarVisitaScreen.kt` | admin | Sede + supervisor + fecha |
| `pantallas/reportes/ReportesScreen.kt` | admin | Cumplimiento por cliente y rango |
| `navegacion/NavegacionVerifiClean.kt` | — | Grafo por rol |
| `navegacion/ContenedorAdmin.kt` | admin | `NavigationBar` de 5 secciones |
| `componentes/Componentes.kt` | — | Estados de carga, vacío, error, badges |
| `theme/Theme.kt` | — | Paleta, tipografía, formas |

### 4.3 El tema actual

Verde marca `#0F5C4A`, verde claro `#3E8B75`, contenedor `#C7E9DD`, ámbar de atención
`#765A00`. El rol `error` de Material está reasignado a ámbar y existe un rol aparte
`ColorScheme.alertaFraude` (más `contenedorAlertaFraude` y `sobreContenedorAlertaFraude`)
para el rojo, que queda **reservado exclusivamente para fraude**.

Puedes rediseñar la paleta entera, pero esa distinción semántica —error operativo ≠ alerta
de fraude— tiene que sobrevivir, con los nombres actuales o con otros mejores que
documentes.

### 4.4 Navegación

El grafo se construye **por rol**: un supervisor no tiene registradas las rutas de admin,
no sólo se le esconde el botón. Si reorganizas la navegación, mantén esa propiedad.

Rutas actuales en `Rutas` (`NavegacionVerifiClean.kt`):
`login`, `visitas`, `visitas/{visitId}`, `visitas/{visitId}/captura`, `admin`,
`admin/visita/{visitId}`, `admin/sedes`, `admin/supervisores`, `admin/asignar`,
`admin/reportes`.

Hay una propuesta previa de agrupar las cinco secciones de admin en cuatro
(Control / Operación / Gestión / Reportes). **No está decidida.** Evalúala tú: cinco
destinos es el máximo que admite una `NavigationBar` de Material 3, y agrupar añade un
toque a tareas diarias. Decide con criterio y explica la decisión.

---

## 5. Problemas de usabilidad ya observados

Esto **no es especulación**: sale de recorrer la app instalada en un emulador contra el
backend real. Son la explicación concreta de "no entendí nada".

### 5.1 Login

- La ayuda de desarrollo es un bloque de tres líneas de texto gris al fondo de la pantalla,
  con tres correos separados por puntos. Nadie entiende que son credenciales que puede
  usar, ni que la contraseña da igual en modo desarrollo.
- No hay ninguna pista de que existen dos roles con experiencias distintas.
- No hay recuperación de contraseña, ni "¿no tienes cuenta?", ni explicación de que las
  cuentas las crea el administrador. Un usuario nuevo se queda mirando la pantalla.

### 5.2 Crear una sede — el peor punto de toda la app

El formulario pide, en este orden: `clienteId`, nombre del cliente, nombre de la sede,
dirección, latitud, longitud, radio.

- **`clienteId` es un identificador interno opaco** (`cli_bavaria`) que el administrador
  tiene que inventarse y escribir a mano, sin saber qué es, sin ver los que ya existen y
  sin que nada le avise si se equivoca. Si escribe `cli_bavarias` crea un cliente nuevo
  por accidente y parte el reporte de facturación en dos.
- **Latitud y longitud se teclean como decimales.** No hay mapa, ni buscador de
  direcciones, ni "usar mi ubicación actual". Un administrador en una oficina no tiene ni
  idea de la coordenada de la sede de su cliente.
- El radio de geocerca (50–2000 m, por defecto 150) se pide sin ninguna referencia visual
  de qué significa esa distancia sobre el terreno.

Esto explica literalmente el "no entendí cómo crear".

### 5.3 Bandeja del supervisor

- Al abrir por primera vez dice "Mostrando datos guardados en el dispositivo" antes de que
  exista ningún dato guardado. Confuso y falso.
- Las tarjetas listan sede, cliente, fecha y estado, pero **nada indica qué hay que hacer**.
  No hay una acción primaria visible, ni orden de prioridad, ni "empieza por esta".
- El estado vacío no enseña nada: si Carlos no tiene visitas asignadas, no sabe si es que
  no hay trabajo, si falló la carga o si tiene que hacer algo.

### 5.4 Detalle de visita y captura

- El checklist son cinco preguntas con tres opciones cada una, sin agrupar y sin indicar
  cuáles son obligatorias para poder cerrar.
- No queda claro que **hay que tomar la foto antes de poder completar la visita**, ni
  cuántas evidencias hacen falta.
- El flujo permiso de cámara → permiso de ubicación → visor tiene tres pantallas de
  fricción antes de la primera foto, sin explicar por qué se piden.

### 5.5 Panel del administrador

- Es lo primero que ve Ana al entrar, y arranca con dos filas de chips de filtro sobre una
  lista. Ninguna cifra, ningún resumen, ninguna respuesta a "¿pasó algo anoche?".
- El filtro "Solo sospechosas" está separado de los de estado con un título, pero sigue
  sin explicarse qué cuenta como sospechosa.
- Abrir una visita marcada muestra los motivos, pero no ayuda a decidir qué hacer con ella:
  no hay forma de marcar como revisada, justificada o escalada. El panel no tiene memoria
  de lo que Ana ya miró.

### 5.6 Reportes

- Sale "0.0% de cumplimiento, 0 de 3 visitas" sin ninguna explicación de que eso es
  esperable en un sistema recién sembrado.
- No hay gráfico, ni comparación con el período anterior, ni exportación. Es una lista de
  números sueltos que no se puede llevar a una reunión con el cliente.

### 5.7 Transversal

- **Un fallo de red se presenta como un catálogo vacío.** Si la consulta falla, se quita el
  spinner, la lista queda vacía y el error sólo pasa por un snackbar que desaparece.
  Después la pantalla afirma "no hay sedes" sobre un catálogo que nunca se pudo consultar.
  En la pantalla de asignación llega a decir "No hay sedes activas. Crea una sede" tras un
  timeout. Esto está pendiente de arreglar y **es tuyo**.
- El texto de `ErrorApp.Red` promete "Se reintentará automáticamente" en pantallas que no
  programan ningún reintento ni ofrecen botón de reintentar.
- El backend devuelve errores de validación campo a campo (`detalles`), pero la app sólo
  muestra un mensaje genérico: "Hay campos inválidos en la petición". El usuario no sabe
  qué corregir.
- No hay onboarding, ni tour, ni ayuda contextual, ni estado de primera vez en ninguna
  pantalla.

---

## 6. Hallazgos pendientes de una auditoría previa

Ordenados por severidad. Los dos altos ya están cerrados; estos siguen abiertos y **entran
en tu alcance** porque todos se manifiestan en la interfaz.

| # | Severidad | Hallazgo |
|---|---|---|
| 4 | media | Los fallos de carga se presentan como catálogos vacíos reales. Falta estado de error duradero que gane sobre `isEmpty()`, con acción de reintentar. Afecta a Sedes, Equipo, Asignar y Reportes |
| 5 | media | Las validaciones de la app son más flojas que el contrato (faltan los máximos de 64/160/160/300 caracteres en sede, y email válido / 254 / nombre 120 / password 128 en usuario), y `ErrorApp.Validacion.porCampo` no se muestra en los `supportingText` |
| 6 | media | `clienteId` y `nombreCliente` pueden divergir entre sedes del mismo cliente. La app agrupa por id y toma `sedes.first().nombreCliente`, así que un typo cambia el nombre que sale en un reporte facturable |
| 7 | media | Las altas de sede y las asignaciones no llevan clave de idempotencia. Si el servidor confirma y se cae la conexión antes de responder, un reintento humano crea un duplicado |
| 11 | baja | `openapi.yaml` omite 401/403 en varias operaciones de catálogo |
| 12 | baja | El diálogo de nuevo usuario usa una `Column` no desplazable: con teclado abierto o fuente accesible, los controles de abajo quedan fuera de pantalla |
| 13 | baja | Los selectores de sede, supervisor y cliente componen todos sus elementos sin búsqueda. Con catálogos grandes la operación diaria se vuelve lenta |

### Funcionalidad que existe en el backend y no en la app

`PATCH /v1/users/{id}` y `PATCH /v1/sites/{id}` con `{ "activo": boolean }` ya funcionan y
están probados. Es la **única forma de cortarle el acceso a alguien que deja la empresa**:
el middleware comprueba `activo` en cada petición, así que surte efecto de inmediato. La
app todavía no tiene ninguna forma de invocarlos.

Reglas de negocio que la interfaz debe respetar:

- Nunca hay borrado, sólo baja lógica. El histórico de un supervisor dado de baja se
  conserva entero: sus evidencias siguen siendo válidas y sus visitas siguen contando para
  el cumplimiento del cliente.
- Un administrador **no puede** cambiar el estado de su propia cuenta (el servidor responde
  400). La interfaz debería impedirlo antes, y explicar por qué.
- Desactivar una sede sólo impide asignarle visitas nuevas; las ya asignadas siguen su
  curso porque el supervisor puede estar de camino.

---

## 7. Qué se espera de ti

### 7.1 Primero la arquitectura de la información, después el pixel

Antes de escribir Compose, produce y deja escrito en el repo:

1. **Inventario de tareas por rol**, ordenadas por frecuencia real de uso. Para Carlos hay
   básicamente una tarea que repite 8 veces al día. Para Ana hay tres rutinas: revisar
   ayer, planear hoy, facturar el mes.
2. **Mapa de navegación propuesto**, con la decisión sobre agrupar o no las secciones de
   admin y el porqué.
3. **El primer minuto de cada rol.** Qué ve Carlos la primera vez que abre la app. Qué ve
   Ana. Cómo aprenden qué hacer sin que nadie se lo explique.
4. **Sistema de diseño**: paleta con roles semánticos, escala tipográfica, espaciado,
   estados (carga / vacío / error / éxito / offline), y el catálogo de componentes.

### 7.2 Después, la implementación

- Rediseña las pantallas listadas en 4.2.
- Resuelve los problemas de la sección 5 y los hallazgos de la sección 6 que sean de
  interfaz (4, 5, 6, 12, 13 seguro; 7 requiere tocar también el contrato y el backend).
- Cablea la baja lógica de usuarios y sedes (sección 6).
- Añade lo que falte para que la app se aprenda sola: estados vacíos que enseñan, ayuda
  contextual, confirmaciones que explican consecuencias, mensajes de error accionables.

### 7.3 Lo que hay que resolver de verdad, no maquillar

**El `clienteId` escrito a mano.** La solución no es un campo más bonito. Es que el
administrador elija un cliente existente de una lista y sólo escriba el identificador
cuando de verdad está creando un cliente nuevo — y que en ese caso se genere o se valide,
y que el nombre quede asociado y bloqueado para las siguientes sedes del mismo cliente.
Esto resuelve además el hallazgo 6.

**Las coordenadas tecleadas.** Sin mapa no se puede resolver del todo, y no puedes añadir
dependencias de mapas sin justificarlo. Mínimo aceptable: aceptar que se pegue una
coordenada copiada de Google Maps en cualquiera de sus formatos habituales, permitir
"usar mi ubicación actual" cuando el administrador esté en la sede, y dar una referencia
comprensible del radio ("150 m ≈ una manzana"). Si propones incluir un mapa, di qué
dependencia, qué peso añade al APK y qué requisitos de clave de API implica; el dueño del
producto decidirá.

**El panel sin memoria.** Ana necesita poder cerrar el caso de una alerta. Si eso requiere
un campo nuevo en el servidor, propónlo con su forma exacta en `contracts/openapi.yaml`,
pero no lo implementes en el backend sin avisar: puede que se prefiera dejarlo para otra
iteración.

### 7.4 Accesibilidad, no negociable

- Objetivos táctiles de 48 dp mínimo. Carlos usa guantes.
- Contraste AA (4.5:1 en texto normal). Se trabaja a pleno sol y en sótanos oscuros.
- `contentDescription` en todo control sin etiqueta visible.
- La app tiene que seguir siendo usable con el tamaño de fuente del sistema al máximo.
  Varias pantallas se rompen hoy; el diálogo de usuario nuevo es una de ellas.
- El color nunca puede ser el único portador de significado.

---

## 8. Cómo trabajar

### 8.1 Skills disponibles en esta máquina

Úsalas. Están instaladas y son mejores que improvisar:

- **impeccable** — `~/.claude/plugins/marketplaces/impeccable/.agents/skills/impeccable/SKILL.md`
  (hay copias equivalentes en `.claude/`, `.cursor/`, `.gemini/` y `.github/` del mismo
  repositorio de marketplace). Es la skill principal para este encargo: diseño de interfaz,
  jerarquía visual, arquitectura de la información, carga cognitiva, accesibilidad, estados
  vacíos y de error, tipografía, color, movimiento y sistemas de diseño reutilizables.
  Cubre explícitamente el caso de "diseño soso que debe volverse más audaz".
- **ui-ux-pro-max** — `~/.claude/skills/ui-ux-pro-max/`. Catálogo de estilos, paletas,
  emparejamientos tipográficos, tipos de producto y guías de UX. Tiene material específico
  de mobile y de sistemas de color accesibles.
- **no-ai-slop** — `~/.claude/skills/no-ai-slop/`. Pásala por todo el texto de interfaz y
  por la documentación que escribas. Los microcopys de esta app los va a leer un supervisor
  con prisa; no pueden sonar a plantilla.
- **conventional-commits** — `~/.claude/skills/conventional-commits/`, para los mensajes de
  commit.
- **design-patterns** y **architecture-patterns** — `~/.claude/skills/`, si el rediseño te
  obliga a reorganizar la capa `ui`.

Si alguna ruta no existe en tu entorno, dilo en el informe en vez de inventarte el
contenido de la skill.

### 8.2 Reglas de honestidad — esto ya salió mal antes

En una iteración previa se entregó un rediseño completo declarado como terminado. **No
compilaba**: faltaban tres imports. El agente no pudo ejecutar Gradle en su sandbox y
aun así presentó el trabajo como cerrado. Se descubrió al día siguiente.

Por tanto:

- **Intenta compilar.** `cd android && ./gradlew assembleDebug` y `./gradlew testDebugUnitTest`.
- **Si no puedes ejecutar Gradle, dilo con esas palabras en el informe final.** No escribas
  "todo en verde", ni "los tests pasan", ni ninguna variante. Escribe qué intentaste y qué
  error saliste.
- Si no puedes compilar, revisa a mano cada símbolo nuevo que uses y cada import. La
  mayoría de los fallos de la vez anterior eran imports de `androidx.compose` que se dan
  por sabidos.
- **No modifiques un test para que pase.** Si un test falla por tu cambio, o el cambio está
  mal, o el test codificaba un bug — y en ese segundo caso explícalo antes de tocarlo.

### 8.3 Verificación

```bash
cd backend && npm test          # 135 tests, deben seguir en verde
cd android && ./gradlew testDebugUnitTest    # 181 tests
cd android && ./gradlew assembleDebug assembleRelease
```

`assembleRelease` importa: pasa por R8 y valida las reglas de
`app/proguard-rules.pro` para kotlinx.serialization y Retrofit, que es donde este tipo de
app se rompe sólo en producción.

Hay tests de interfaz instrumentados que dependen de textos exactos —"VerifiClean",
"Correo", "Contraseña", "Entrar", "Correo o contraseña incorrectos."— en
`android/app/src/androidTest/`. Si cambias esos textos, actualiza los tests en el mismo
commit y dilo.

Para probar la app corriendo:

```bash
cd backend && npm run dev
```

Arranca en `http://localhost:8080` con la base en memoria ya sembrada: 4 usuarios, 4 sedes
de Bogotá y 5 visitas. Usuarios de prueba: `admin@verificlean.test` (admin),
`carlos@verificlean.test` y `diana@verificlean.test` (supervisores). Cualquier contraseña
sirve en modo desarrollo. El build de debug apunta a `http://10.0.2.2:8080/`, que es el
host visto desde el emulador.

La base es **en memoria**: reiniciar el backend rehace el seed y borra lo que hayas creado.
Para consultar la API a mano, el token en desarrollo es literalmente `dev:<uid>`:

```bash
curl -H "Authorization: Bearer dev:u_admin" http://localhost:8080/v1/sites
```

Los uid del seed son `u_admin`, `u_sup_1` y `u_sup_2`.

### 8.3.1 Emulador

Hay un AVD ya creado (`verificlean_test`, API 34, imagen `google_apis` — no
`google_apis_playstore`, porque esa variante quita `adb root`). El SDK está en
`~/AppData/Local/Android/Sdk`.

```bash
~/AppData/Local/Android/Sdk/emulator/emulator.exe -avd verificlean_test -no-snapshot-load -no-boot-anim
```

Tarda 1-2 minutos en arrancar. Déjalo en segundo plano y comprueba con
`adb devices` hasta ver `emulator-5554  device`.

Instalar y lanzar:

```bash
cd android && ./gradlew assembleDebug
~/AppData/Local/Android/Sdk/platform-tools/adb.exe install -r app/build/outputs/apk/debug/app-debug.apk
~/AppData/Local/Android/Sdk/platform-tools/adb.exe shell am start -n com.verificlean.app.debug/com.verificlean.app.MainActivity
```

**Trampas que ya costaron tiempo, no las redescubras:**

- El `applicationId` de debug lleva sufijo: el paquete instalado es
  **`com.verificlean.app.debug`**, pero la clase de la actividad sigue siendo
  `com.verificlean.app.MainActivity`. `am start -n com.verificlean.app/.MainActivity` falla
  con `Error type 3`.
- En Git Bash, `adb shell` con rutas absolutas se rompe: `/sdcard/ui.xml` se convierte en
  `C:/Program Files/Git/sdcard/ui.xml`. Exporta `MSYS_NO_PATHCONV=1` antes de cualquier
  comando `adb shell` que lleve una ruta.
- Al primer arranque tras instalar puede salir el diálogo "System UI isn't responding".
  Pulsa *Wait* y sigue; no es un fallo de la app.

Para inspeccionar la pantalla sin capturas de imagen —más barato y verificable— vuelca el
árbol de accesibilidad, que además te dice si tus `contentDescription` están puestos:

```bash
export MSYS_NO_PATHCONV=1
A=~/AppData/Local/Android/Sdk/platform-tools/adb.exe
$A shell uiautomator dump /sdcard/ui.xml && $A shell cat /sdcard/ui.xml
```

Cada nodo trae `text`, `content-desc` y `bounds="[x1,y1][x2,y2]"`. El centro de esos
límites es la coordenada para `adb shell input tap X Y`, y el tamaño del rectángulo te dice
si el objetivo táctil llega a 48 dp. Para escribir en un formulario:
`input tap` en el campo, `input text "..."`, `input keyevent 61` (TAB) para pasar al
siguiente. Deja 1-2 segundos entre pasos o Compose no ha recolocado el foco y el texto
acaba en el campo equivocado.

Captura de pantalla, si la necesitas para juzgar composición y color:

```bash
export MSYS_NO_PATHCONV=1
~/AppData/Local/Android/Sdk/platform-tools/adb.exe exec-out screencap -p > /tmp/pantalla.png
```

### 8.3.2 Recorridos que tienes que hacer tú mismo

No des por bueno un rediseño que no has visto correr. Como mínimo:

1. **Primer arranque de Carlos**: instalar, abrir, entrar como `carlos@verificlean.test`,
   abrir una visita, tomar la foto, completar. Antes de disparar, pon la ubicación del
   emulador cerca de la sede: `⋮` → *Location* → lat `4.700543`, lng `-74.063644` → *Set*.
2. **Sin conexión**: activa el modo avión antes de disparar. La app debe confirmar que la
   evidencia está guardada y mostrar la cola. Desactívalo y comprueba que sube sola.
3. **Primer arranque de Ana**: entrar como `admin@verificlean.test`, y **crear una sede
   nueva de un cliente que no existe**. Ese es el recorrido que el dueño del producto no
   consiguió completar. Si tú tampoco puedes hacerlo sin leer el código, no has terminado.
4. **Alerta de fraude**: repite la captura con la ubicación en lat `4.610000`,
   lng `-74.150000`. La evidencia se sube igual —el servidor marca, no rechaza— y aparece
   marcada en el panel de Ana. Comprueba en la pantalla de Carlos que **nada** delata que
   salió marcada.
5. **Fuente grande**: *Settings* → *Display* → *Font size* al máximo, y repite 1 y 3.

### 8.4 Alcance y límites

- **No toques nada de GCP.** El despliegue lo hace el dueño del producto a mano y es lo
  último del proyecto. Trabaja sólo en local.
- Cambiar el backend está permitido si el diseño lo exige, pero cada cambio de contrato se
  refleja en `contracts/openapi.yaml` **y** en los DTO de Android, y viene con tests.
- No reescribas la capa de datos, la sincronización ni el cifrado. Están probados y no son
  el problema.
- Commits pequeños y temáticos. No un commit gigante de "rediseño".

### 8.5 Qué entregar

En el mensaje final, y también como archivo en `docs/`:

1. Qué cambiaste y por qué, pantalla por pantalla.
2. El sistema de diseño resultante, derivado de lo que construiste de verdad (no de lo que
   pensabas construir).
3. Las decisiones de arquitectura de la información, con las alternativas que descartaste.
4. Qué compilaste y qué ejecutaste, literalmente. Si no ejecutaste algo, dilo.
5. Textos de interfaz que cambiaste y que puedan romper tests.
6. Lo que dejaste sin hacer y por qué.
7. Cualquier decisión que necesite que el dueño del producto elija (dependencia de mapas,
   campo nuevo en el servidor, agrupación de secciones).

---

## 9. La prueba de que el trabajo funcionó

Un supervisor que nunca ha visto la app la abre y completa su primera visita sin ayuda.

Una administradora que nunca ha visto la app da de alta un cliente con su sede, crea un
supervisor, le asigna una visita y, al día siguiente, entiende de un vistazo si alguien
reportó algo raro.

Y ninguno de los dos puede deducir, de nada de lo que ve la app del supervisor, si una
evidencia va a salir marcada.
