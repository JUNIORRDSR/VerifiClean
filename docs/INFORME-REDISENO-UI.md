# Informe de rediseño UI

Este informe describe el código terminado. Las decisiones de navegación, diseño y producto están en `docs/ARQUITECTURA-INFORMACION-UI.md`.

## Resultado por recorrido

### Inicio de sesión

La pantalla explica quién crea la cuenta y por qué cada rol ve tareas distintas. En desarrollo, los usuarios de prueba aparecen con rol y correo. Se conservaron los textos que usan las pruebas instrumentadas: «VerifiClean», «Correo», «Contraseña», «Entrar» y «Correo o contraseña incorrectos.».

Login dejó de reutilizar el DTO administrativo. La respuesta de sesión no contiene el dispositivo registrado ni el resultado de compararlo con el actual.

### Supervisor

La bandeja prioriza el trabajo operativo, muestra una acción en cada tarjeta y conserva los errores de actualización en pantalla. La hora visible corresponde a la última consulta correcta, no al último intento. El detalle cae a Room sólo ante un error de red; un 401, 403 o 404 no se disfraza con caché.

Las observaciones y la nota respetan los límites del contrato y muestran contador. Las evidencias locales cuentan para informar que la foto está segura, pero completar permanece bloqueado hasta que el servidor la recibe. Cuando la cola de una visita se vacía, el detalle se actualiza.

La captura sigue usando CameraX. No se agregó galería, intent de cámara ni archivo compartido. La foto se cifra y registra antes de consultar ubicación e integridad. Play Integrity tiene un límite de diez segundos y un callback tardío no cambia la respuesta visible. La pantalla de permisos explica cámara y ubicación, permite continuar sin ubicación y enlaza a ajustes cuando hace falta.

### Panel y revisión de alertas

El panel abre en «Ayer», permite cambiar a «Hoy» o «Todo» y envía las fechas reales a la API. El resumen declara su periodo y conserva el alcance de página cuando existen más resultados. Los filtros de periodo, sospecha y estado son independientes.

El detalle administrativo permite marcar una alerta como revisada, justificada o escalada. Justificar y escalar exigen nota. La identidad y la hora vienen del servidor. Si se registra una evidencia sospechosa nueva, el caso vuelve a pendiente dentro de la misma transacción.

La imagen de evidencia ofrece «Recargar foto» cuando la URL firmada falla. Distancia y radio sólo se resaltan cuando el servidor devuelve el motivo correspondiente; la UI no inventa un veredicto a partir de un cálculo local.

### Sedes, equipo y asignación

El alta de sede ya no pide `clienteId`. El administrador elige un cliente o crea el primero escribiendo su nombre. El identificador nuevo es opaco y el servidor impide duplicar un cliente por diferencias de mayúsculas, tildes o espacios. El formulario acepta enlaces y pares de coordenadas comunes, ofrece ubicación actual y explica el radio con una referencia práctica.

Los errores de carga y mutación permanecen visibles aunque ya haya datos. Equipo protege la cuenta activa de una baja accidental y resuelve la identidad antes de habilitar el cambio. Asignar distingue un catálogo vacío de un catálogo que no cargó, conserva el error de envío y permite reintentar sin duplicar la visita.

### Reportes

El cliente se busca por nombre y se valida que todas sus sedes conserven una identidad coherente. Cada periodo muestra fechas exactas, se compara con el periodo anterior y el gráfico Canvas incluye semántica textual. Cuando no hubo visitas programadas se dice así, en vez de mostrar un cumplimiento de 0 %. El resultado puede exportarse como CSV mediante Activity Result, sin otra dependencia.

## Contratos y persistencia

- OpenAPI declara las respuestas 401 y 403 que faltaban en catálogo, el límite de correo y los DTO separados por rol.
- `revisionAlerta` aparece sólo en modelos administrativos.
- Las altas de sede y las asignaciones usan UUID idempotente. Misma clave y carga devuelve el recurso original; cambiar la carga devuelve 409.
- La huella idempotente es interna y no sale en DTO.
- La cola de evidencia se consulta y reintenta por `ownerUid`.
- Se eliminó Accompanist Permissions; no se añadió Maps ni otra dependencia de UI.
- No se desplegó ni modificó GCP.

## Verificación final

La verificación se ejecutó después del último cambio, sobre el árbol compartido completo:

- Backend: `npm test`, 153 de 153 pruebas aprobadas.
- Backend: `npm run typecheck`, sin errores.
- Android: `gradlew.bat testDebugUnitTest --no-daemon`, 205 pruebas, 0 fallos, 0 errores y 0 omitidas.
- Android instrumentado: `gradlew.bat connectedDebugAndroidTest --no-daemon`, 22 pruebas, 0 fallos y 0 omitidas en el AVD API 34.
- Android: `gradlew.bat assembleDebug assembleRelease --no-daemon`, `BUILD SUCCESSFUL`; release pasó R8 y lint vital.
- Impeccable: detector sobre `android/app/src/main/java/com/verificlean/app/ui`, resultado `[]`.
- No-ai-slop: revisión de microcopy y de estos documentos; no quedaron eslóganes, promesas vagas ni formato decorativo.

También se probó la app en el AVD local `verificlean_test` con el backend en memoria:

1. Inicio como administradora y apertura del panel en «Ayer».
2. Creación desde la UI de `Cliente Piloto QA` y su primera sede `Sede Autonoma`; la API devolvió un cliente con ID opaco, coordenadas y radio correctos.
3. Inicio como Carlos desde un identificador distinto al sembrado; login no devolvió señales de vínculo y la jerarquía de la bandeja no contenía fraude, sospecha, dispositivo, integridad, ubicación, distancia, radio, alerta, veredicto ni hash.
4. Bandeja y detalle del supervisor con fuente al 150 %, con acciones legibles y contenido desplazable.

El APK debug quedó en `android/app/build/outputs/apk/debug/app-debug.apk`. El build release genera `android/app/build/outputs/apk/release/app-release-unsigned.apk`, que es el artefacto esperado porque el repositorio no incluye una clave de firma de producción.
