# Product

<!-- impeccable:product-schema 1 -->

## Platform

android

## Users

Carlos es supervisor de aseo. Recorre entre cuatro y ocho sedes al día, a menudo sin señal, con una mano ocupada y un teléfono Android de gama media. Necesita registrar una visita completa sin perder la evidencia.

Ana administra la operación desde oficina. Revisa lo ocurrido, organiza las visitas del día y prepara el cumplimiento para sus clientes sin acusar a un supervisor sin contexto.

## Product Purpose

VerifiClean registra evidencia de las visitas de supervisión de aseo y la conserva para que la empresa y su cliente puedan verificar que la supervisión ocurrió. Una primera persona debe poder completar una visita y una administradora debe poder crear la operación sin capacitación.

## Positioning

La evidencia se captura exclusivamente con CameraX, se guarda cifrada antes de solicitar ubicación e integridad, y se sincroniza después. El servidor marca señales sospechosas en silencio y sólo las muestra a administradores: el supervisor nunca recibe un veredicto ni una señal equivalente.

## Operating Context

La supervisión se hace en sedes, sótanos, cuartos de máquinas y bodegas, con conexión intermitente. La administración usa la app para revisar ayer, planear hoy y revisar el cumplimiento mensual. Idioma: español de Colombia; fecha: `d MMM yyyy · HH:mm`.

## Capabilities and Constraints

- Kotlin, Jetpack Compose y Material 3; MVVM, Hilt, Flow, Room, WorkManager, CameraX y Retrofit.
- Offline-first: la UI del supervisor observa Room; las evidencias pendientes deben ser claras y recuperables.
- La foto sólo puede llegar desde CameraX. No se permite galería ni cámara externa.
- Sin ubicación en segundo plano, foreground service, WebSockets, polling, color dinámico ni dependencias nuevas sin una justificación explícita.
- Rojo reservado para alerta de fraude; ámbar para errores operativos. Nunca se borra una sede, usuario o visita: las bajas son lógicas.
- Las rutas de administración se registran sólo para administradores.

## Brand Commitments

VerifiClean debe sentirse claro, serio y humano. El lenguaje nombra la tarea y la siguiente acción, no detalles internos. Esta ficha se infiere del brief de rediseño del 16 de agosto de 2026.

## Evidence on Hand

El repositorio contiene el contrato OpenAPI, documentación de arquitectura y antifraude, el backend local sembrado y la app Android. No hay un kit de marca ni evidencia comercial que permita inventar métricas o testimonios.

## Product Principles

1. La siguiente acción debe ser evidente antes que la decoración.
2. Una evidencia guardada nunca se confunde con una evidencia subida.
3. La interfaz del supervisor protege el modelo antifraude por diseño.
4. Los formularios convierten datos internos en decisiones comprensibles.
5. Los errores dicen qué pasó y cómo continuar.

## Accessibility & Inclusion

Los controles táctiles miden al menos 48 dp. El texto normal alcanza contraste AA, el color no es el único indicador y los flujos se conservan utilizables con el tamaño de fuente del sistema al máximo.
