# Modelo antifraude

Este documento explica **por qué** cada regla es como es. El código está en
`backend/src/services/antifraude.ts` (reglas puras) y `evidencia.ts` (orquestación).

## Principio: marcar, nunca rechazar — y nunca contárselo al supervisor

Una evidencia sospechosa se guarda con `esSospechosa=true` y sus `motivosAlerta`. Nunca
se devuelve un 4xx por sospecha.

La revisión humana se registra en `visits.revisionAlerta`, pero sólo se proyecta bajo
`/v1/admin`. El supervisor no recibe el estado, la nota, el autor ni la hora: cualquiera
de esos datos le permitiría comprobar indirectamente si su evidencia activó una alerta.
Cuando una evidencia sospechosa nueva llega a un caso revisado, justificado o escalado,
la transacción de registro devuelve el caso a `pendiente`; así no queda cerrado con una
decisión tomada antes de existir la evidencia más reciente.

El motivo es que rechazar convierte la API en un oráculo. Si el servidor responde
"fuera de geocerca", el defraudador sabe que el problema es la ubicación, ajusta su app
de mock GPS y reintenta hasta que pase. Marcando en silencio, cada intento queda
registrado con su foto, su ubicación y su hora — y el admin ve el patrón completo.

**La segunda mitad de esa regla es igual de importante y en la primera versión se nos
escapó.** El servidor respondía 201 pero incluía `esSospechosa` y `motivosAlerta` en el
cuerpo. Eso es el mismo oráculo con otra ropa: el supervisor prueba, lee el motivo, se
acerca cien metros y repite. Lo mismo con `distanciaAlSitioMetros`, que le dice
exactamente cuánto le falta, y con `tieneSospechosas` en su lista de visitas.

Ahora el supervisor recibe **sólo confirmación de registro**: el id de la evidencia y la
hora del servidor. Todo veredicto —flags, motivos, distancias, hashes, integridad y URLs
de foto— vive únicamente bajo `/v1/admin`. El corte está en
`backend/src/routes/mappers.ts` y hay tests que fallan si alguno de esos campos vuelve a
aparecer en una respuesta al supervisor.

Los 4xx del endpoint de evidencia son sólo por forma (no es un JPEG, coordenada fuera de
rango), por estado (la visita ya está cerrada) o por permisos.

### El umbral tampoco es información suya

`GET /v1/visits/{id}` **no** devuelve `siteRadioPermitidoMetros`. No es un veredicto, pero
lleva a lo mismo: saber que la geocerca son 150 m le dice exactamente cuánto puede alejarse
antes de que la evidencia salga marcada, sin necesidad de probar y leer el resultado.

Este se escapó dos veces. Primero estaba escrito en la pantalla del supervisor ("Radio
permitido: 150 m"). Se quitó de ahí — y el campo siguió viajando en el JSON, que no sirve
de nada porque se lee con cualquier proxy. Ahora la frontera está en tipos separados,
`VisitaDetalleSupervisor` frente a `VisitaDetalle`, y la fijan tres pruebas: el servidor no
lo manda, el DTO de Android no lo declara, y el dominio lo deja en `null` aunque un backend
futuro volviera a mandarlo.

La dirección y las coordenadas de la sede sí van: el supervisor tiene que llegar hasta
ella, y saber dónde está no le dice nada sobre el veredicto.

El mismo razonamiento aplica a la navegación de la app: las pantallas de administración
sólo se registran en el grafo cuando el rol es admin. El formulario de alta de sede enseña
el rango de radio permitido y explica qué produce falsas alertas, y eso es información
antifraude que no debe poder construirse en la sesión de un supervisor ni aunque las
consultas fallaran con 403. Queda un límite conocido: un supervisor que decompile el APK
lee esas constantes igual. Cerrarlo exigiría un artefacto separado para admin.

## Las reglas

### 1. Geocerca — `FUERA_DE_GEOFENCE`

Distancia Haversine entre la foto y la sede, contra el radio de la visita.

**Se concede margen por el error del GPS.** La regla no es `distancia > radio`, sino:

```
distancia > radio + min(precisión, radio)
```

Sin ese margen, un supervisor parado en la puerta con 60 m de precisión y un radio de
50 m se marca como fraude en cada visita. Una alerta que salta siempre es una alerta que
nadie mira, y el sistema entero deja de servir. Con margen, sólo se acusa a quien está
fuera *incluso asumiendo el error del sensor a su favor*.

El margen se topa en el propio radio para que el sensor no se convierta en la palanca:
un cliente modificado que reporte 50 km de precisión consigue, como mucho, duplicar el
radio — y dispara `PRECISION_GPS_BAJA`, que el admin sí ve.

### 2. Precisión insuficiente — `PRECISION_GPS_BAJA`

`precisión > radio permitido`. Es información distinta de estar fuera: significa que la
medición no distingue "dentro" de "fuera". Se reporta aparte para que el admin sepa que
no es una acusación, es una ausencia de prueba.

Por eso el radio mínimo de una sede es 50 m (`sitioNuevoSchema`): por debajo, el error
típico de `PRIORITY_BALANCED_POWER_ACCURACY` haría que casi toda evidencia legítima
cayera aquí.

### 3. Imagen duplicada — `IMAGEN_DUPLICADA`

SHA-256 **calculado por el servidor sobre los bytes recibidos**, buscado en el resto de
visitas. El hash que manda el cliente se guarda sólo como auditoría; si no coincide con
el real, salta `HASH_NO_COINCIDE` (foto alterada en tránsito o cliente modificado).

Detalle que lo sostiene: la foto se sube **a través del servidor**, no directamente a
Cloud Storage con una URL firmada. Si el cliente subiera directo, el hash quedaría bajo
su control y esta regla sería decorativa.

Repetir la misma foto **dentro** de la misma visita no se marca: es legítimo fotografiar
dos veces el mismo punto.

### 4. Integridad del dispositivo — `DISPOSITIVO_NO_CONFIABLE` / `INTEGRIDAD_NO_EVALUADA`

Play Integrity, verificado en el servidor. Se exige `MEETS_DEVICE_INTEGRITY` —no
`MEETS_BASIC_INTEGRITY`— porque la básica la pasa un emulador, y un emulador es la forma
más barata de fabricar visitas.

La app **no interpreta el veredicto**: pide el token y lo reenvía. Si lo evaluara ella,
un APK modificado diría siempre "todo bien" — y un APK modificado es justo lo que hay
que detectar.

Se comprueba además que el `requestPackageName` del token sea el de esta app: sin eso,
un atacante puede pedirle un token válido a cualquier otra app instalada y reenviarlo.

**Sin token también se marca** (`INTEGRIDAD_NO_EVALUADA`). Si "sin token" pasara limpio,
el fraude más barato del mundo sería bloquear la llamada a Play Integrity.

### 4b. Token reutilizado — `TOKEN_INTEGRIDAD_REUTILIZADO` / `TOKEN_INTEGRIDAD_CADUCADO`

Leer el veredicto no basta, y esto también se nos escapó en la primera versión.

Un veredicto describe **el momento en que se pidió el token**, no el momento en que se
usa. Sin más comprobaciones, un token obtenido una sola vez desde un teléfono limpio y
sin root sirve para siempre: se guarda y se adjunta a cualquier subida posterior hecha
desde un cliente modificado. El veredicto seguiría diciendo `MEETS_DEVICE_INTEGRITY` y
`PLAY_RECOGNIZED`, porque era verdad — hace tres semanas.

La ligadura se hace con el nonce, que se calcula sobre tres cosas que sólo pueden ser las
de esta evidencia:

```
nonce = base64url( SHA256( visitId : clienteEvidenciaId : SHA256(bytes de la foto) ) )
```

El hash es el que midió **el servidor** sobre los bytes recibidos, no el que declaró el
cliente. Reproducir el nonce exige tener los tres datos antes de pedir el token, que es
justo lo que un cliente honesto tiene y un reproductor de tokens no.

No hace falta llevar un registro de nonces usados: como `clienteEvidenciaId` sólo puede
registrarse una vez (idempotencia), el mismo token no sirve dos veces.

`TOKEN_INTEGRIDAD_CADUCADO` cubre el otro flanco: `requestDetails.timestampMillis` fuera
de una ventana de diez minutos. **No** limita cuánto puede tardar una evidencia en
sincronizarse — el token se pide al capturar, así que una subida que espere horas en la
cola offline llegará con el token caducado, se marcará, y no se perderá. Es información
para el admin, no un rechazo.

El cálculo vive en los dos lados (`backend/src/lib/nonce.ts` y
`EvidenciaRepositoryImpl.calcularNonce`) y cada suite fija el mismo valor literal para una
entrada conocida. Si una implementación cambia sin la otra, ese test lo dice; sin él, el
síntoma sería que **todas** las evidencias salen marcadas como token reutilizado, sin
ninguna pista de por qué.

### 5. Ubicación simulada — `MOCK_LOCATION`

`Location.isMock` (API 31+) o `isFromMockProvider` en versiones anteriores. Se consultan
las dos porque la señal es demasiado valiosa como para perderla en media flota.

### 5b. Imagen duplicada, sin ventana de carrera

La comprobación de hash duplicado **no** puede hacerse con una consulta normal. Dos
subidas simultáneas de la misma foto a visitas distintas consultan antes de que la otra
exista, ninguna ve nada, y las dos entran sin marcar. Al tocar documentos de visita
diferentes, tampoco hay contención que fuerce un reintento.

Por eso existe la colección `hashesEvidencia`, con un documento por hash e id
determinista: la decisión se toma dentro de una transacción sobre ese documento concreto,
así que el segundo en llegar encuentra el del primero. Repetir la misma foto dentro de la
**misma** visita sigue sin contar como duplicado — fotografiar dos veces el mismo punto es
legítimo.

### 6. Dispositivo no vinculado — `DISPOSITIVO_NO_VINCULADO`

Detecta colusión: un supervisor que le presta sus credenciales a otro para que "haga la
ronda por él". En el primer login se registra un hash del `ANDROID_ID`; después se
compara.

**No bloquea**, sólo alerta: cambiar de teléfono es legítimo. El admin reasigna borrando
el campo. Y sólo alerta ante contradicción demostrable — sin dispositivo registrado o
sin cabecera `X-Device-Id`, no se dice nada. Ausencia de evidencia no es evidencia.

### 7. Checklist alterado — `CHECKLIST_ALTERADO`

Un cliente modificado puede mandar el mismo número de ítems que la visita, pero con otros
`id` y otras preguntas, todas respondidas. Sin comprobación, la visita se cierra guardando
un checklist inventado: se pierde justo lo que el checklist debía probar, que el
supervisor respondió *esas* preguntas.

Se compara el conjunto de `id` contra el asignado (no el orden, que la UI puede cambiar
legítimamente, ni el texto, que reconstruye el servidor). Al cerrar la visita el checklist
se **fusiona**: del cliente se toman sólo `respuesta` y `nota`; las preguntas son siempre
las del servidor.

En el cierre esto es un 409 —la visita no se cierra con un checklist que no es el suyo—;
en la subida de evidencia es sólo una marca, porque ahí el checklist es un adjunto
informativo y la foto no debe perderse por ello.

### 8. Tiempo

No es una regla, es una invariante: `timestampServidor` lo pone el servidor al recibir la
petición. El reloj del dispositivo se guarda en `timestampCliente` y **no decide nada**.

Es el dato que sostendría una decisión disciplinaria, así que la pantalla del admin lo
etiqueta explícitamente como "Hora (servidor)".

## Lo que este modelo NO detecta

Ser honesto sobre los huecos es parte del diseño:

- **Foto de una foto.** Alguien fotografía la pantalla de otro teléfono estando dentro
  del radio. El hash es nuevo, la ubicación es correcta. Detectarlo pide análisis de
  imagen (moiré, detección de pantalla) — fuera del alcance de este MVP.
- **Supervisor que entra al edificio y no sube.** La geocerca es horizontal; el GPS no
  da piso. Mitigable con radios pequeños o con un QR por sede, no con GPS.
- **Colusión con el personal de limpieza.** Nada técnico impide que la foto sea real,
  la ubicación real y el trabajo esté mal hecho igualmente. Eso lo detecta el checklist
  y, en última instancia, el cliente.
- **Dispositivo comprometido con Play Integrity intacto.** Existe, es caro. Por eso las
  reglas se acumulan: pasar todas a la vez es mucho más difícil que pasar una.
- **Un cómplice dentro de la sede.** Alguien físicamente presente toma la foto y se la
  pasa al supervisor. Todo cuadraría salvo el `deviceId`, que es sólo una alerta.
  Detectarlo de verdad exigiría biometría en la captura — otro producto.

Lo que **sí** dejó de ser un hueco en la segunda revisión: reutilizar un token de Play
Integrity legítimo (ahora ligado por nonce), cerrar una visita con un checklist inventado
(ahora contrastado con el asignado), y deducir el veredicto probando y reintentando (el
supervisor ya no lo recibe).

Ninguna regla es concluyente por sí sola. El valor está en que se acumulan y en que el
admin ve **por qué** se marcó cada evidencia, no un semáforo rojo sin explicación.
