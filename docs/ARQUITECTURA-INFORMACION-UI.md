# Arquitectura de información y UI de VerifiClean

## Problema que guía el rediseño

El fallo principal no era visual. El dueño del producto no pudo crear una sede ni supo qué hacer después de entrar. Por eso la arquitectura se organiza alrededor de tareas con nombre, estado y siguiente acción. Cada pantalla debe contestar tres preguntas: dónde estoy, qué falta y qué puedo hacer ahora.

VerifiClean conserva Material 3 y la separación estricta entre roles. Carlos trabaja desde una bandeja breve; Ana administra personas, sedes, asignaciones, alertas y reportes. No se mezclan destinos de administrador en el grafo del supervisor.

## Mapa de navegación

### Supervisor

`Mis visitas` → `Detalle de visita` → `Capturar evidencia`

La bandeja ordena primero las visitas en progreso y después las pendientes. La primera acción se distingue con «Empieza aquí» y cada tarjeta dice «Iniciar visita», «Continuar» o «Ver visita». El detalle cambia «Checklist» por «Revisión de aseo», muestra el avance y explica por qué todavía no se puede completar.

### Administración

La navegación principal mantiene cinco destinos porque corresponden a trabajos distintos y caben en la barra Material 3:

1. `Alertas`: revisar lo ocurrido ayer, hoy o en todo el historial.
2. `Asignar`: programar una visita con sede y supervisor buscables.
3. `Sedes`: crear el primer cliente, agregar sedes y gestionar bajas.
4. `Equipo`: crear supervisores y administrar su estado.
5. `Reportes`: consultar cumplimiento por cliente y periodo.

En teléfonos se usa navegación inferior; desde 600 dp se cambia a rail lateral. Los detalles quedan fuera de la navegación principal y vuelven al origen.

## Primer minuto

### Carlos

Después de entrar ve «Mis visitas», la hora de la última consulta correcta y una instrucción para abrir la primera pendiente. Un fallo de red nunca se convierte en un estado vacío. Si existe caché, se muestran los datos guardados junto con el aviso y la acción de reintento.

Al guardar una foto, la confirmación aparece cuando el JPEG ya está cifrado y la fila existe en Room. La pantalla dice que la foto está a salvo aunque se cierre la app. La ubicación y Play Integrity se resuelven después y no alteran texto, color, animación ni duración de esa confirmación.

### Ana

El panel abre en «Ayer» y ofrece «Hoy» y «Todo». El título del resumen nombra el periodo y, si hay paginación, aclara que existen más páginas. Las alertas pendientes se distinguen por texto y color.

En `Sedes`, Ana elige un cliente existente o pulsa «Crear cliente». Sólo escribe el nombre del cliente, el nombre de la sede, la dirección y la ubicación. El identificador se genera como valor opaco. Puede pegar coordenadas de Google Maps o usar una lectura puntual de la ubicación actual.

## Límite antifraude del supervisor

El supervisor nunca recibe ni puede deducir el veredicto. La separación se aplica en todas las capas:

- Login usa `UsuarioSesion` y no devuelve `deviceId` ni el resultado de comparar el dispositivo.
- Retrofit tiene DTO distintos para resumen y detalle de supervisor y administrador.
- Los DTO del supervisor no declaran `tieneSospechosas`, `revisionAlerta`, radio, distancia, motivos, hash ni datos de integridad.
- La captura responde igual antes y después del enriquecimiento antifraude.
- La cola local se filtra por el usuario en sesión; una segunda cuenta no ve ni reintenta evidencias de la anterior.
- El grafo administrativo no se registra en una sesión de supervisor.

Pruebas de contrato deserializan respuestas hostiles con campos prohibidos y comprueban que esos datos no alcanzan el dominio del supervisor.

## Sistema de diseño

| Parte | Decisión |
|---|---|
| Color | Verde para avance y confirmación; ámbar para incidencias operativas; rojo y su texto sólo para riesgo antifraude dentro de administración. El color nunca carga solo con el significado. |
| Tipografía | Escala Material 3 con jerarquía clara. Se verificó la bandeja y el detalle con fuente al 150 %. |
| Espaciado | Base de 4 dp; ritmos de 8, 12, 16, 24 y 32 dp; margen lateral de 16 dp en teléfono. |
| Forma | Radios de 8, 14 y 24 dp. La elevación ordena superficies y no decora. |
| Controles | Objetivo mínimo de 48 dp, etiquetas accesibles en iconos y filas que permiten crecer al texto. |
| Carga | Indicador o esqueleto con contexto. La primera emisión no afirma que el catálogo esté vacío. |
| Vacío | Describe el estado real y su siguiente paso. |
| Error | Permanece visible, conserva los datos disponibles y ofrece reintento. Los errores por campo viven junto al control. |
| Éxito | Confirmación corta y estable. En supervisor no depende de señales antifraude. |
| Offline | La foto queda cifrada, se muestra como pendiente del usuario actual y WorkManager reintenta con backoff. |

## Decisiones de producto documentadas

- No se añadió un mapa. Maps Compose implicaría otra dependencia, más peso y una clave con facturación. Para este flujo basta con pegar coordenadas, leer la ubicación actual y validar latitud, longitud y radio.
- No se añadió un recorrido modal de bienvenida. El problema se resuelve con navegación nombrada, texto contextual, estados vacíos y acciones visibles; un tour que se descarta no ayuda en el uso diario.
- Se eliminó Accompanist Permissions. CameraX se conserva y los permisos usan Activity Result, que ya cubre cámara y ubicación puntual.
- `revisionAlerta` pertenece sólo a administración. Sus estados son `pendiente`, `revisada`, `justificada` y `escalada`; justificar o escalar exige nota. Actor y hora los fija el servidor y una evidencia sospechosa nueva reabre el caso.
- `POST /v1/sites` y `POST /v1/visits/assign` exigen `Idempotency-Key`. Android conserva la clave durante un reintento idéntico y la rota si cambia la carga o la operación termina correctamente. El detalle está en `docs/IDEMPOTENCIA-CATALOGOS.md`.
- Un cliente no puede dividirse por error: el backend rechaza el mismo identificador con otro nombre y el mismo nombre normalizado con otro identificador.
- No se desplegó ni modificó GCP.
