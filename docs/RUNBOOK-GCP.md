# Puesta en marcha en GCP

Nada de esto se ha ejecutado. El proyecto funciona entero en local con adaptadores
falsos; esta es la lista de lo que hay que hacer manualmente cuando toque subirlo, en
orden de dependencia.

## 1. Proyecto y APIs

Habilitar: Cloud Run, Firestore, Cloud Storage, Firebase Authentication, Play Integrity
API, Artifact Registry.

Anotar el **número de proyecto** (no el ID): lo necesita Play Integrity en la app.

## 2. Firestore

Crear la base en **modo nativo**, región `southamerica-east1` o `us-central1` según dónde
vaya Cloud Run — que estén juntas ahorra latencia en cada petición.

```bash
firebase deploy --only firestore:rules,firestore:indexes
```

Los archivos son `contracts/firestore.rules` y `contracts/firestore.indexes.json`. Los
índices tardan minutos en construirse; hasta que terminen, las consultas con filtros
devuelven error.

`firebase.json` mínimo:

```json
{ "firestore": { "rules": "contracts/firestore.rules", "indexes": "contracts/firestore.indexes.json" },
  "storage": { "rules": "contracts/storage.rules" } }
```

## 3. Cloud Storage

Un bucket para evidencias, misma región. Recomendado:

- **Uniform bucket-level access** activado.
- **Sin acceso público.** Las fotos se sirven con URLs firmadas de 15 minutos que genera
  la API.
- Regla de ciclo de vida que borre objetos huérfanos: si una transacción falla después de
  subir el archivo, queda un blob sin documento en Firestore.

## 4. Firebase Authentication

Activar el proveedor Email/Password. **No** activar el registro abierto: los usuarios los
crea el admin por `POST /v1/users`, que crea la cuenta en Auth y el documento en `users`
en el mismo paso.

Crear el primer admin a mano (consola de Firebase) y su documento en `users/{uid}` con
`rol: "admin"` y `activo: true`. Sin ese primer admin no hay quien llame a `/v1/users`.

## 5. Cuenta de servicio de Cloud Run

Una cuenta dedicada, no la de Compute por defecto. Roles mínimos:

- `roles/datastore.user` — Firestore
- `roles/storage.objectAdmin` sobre el bucket de evidencias, no sobre el proyecto
- `roles/iam.serviceAccountTokenCreator` sobre sí misma — **hace falta para firmar
  URLs sin clave privada descargada**; es el paso que más se olvida
- Play Integrity: vincular el proyecto de Cloud a la app en Play Console

## 6. Desplegar la API

```bash
cd backend && gcloud run deploy verificlean-api --source . --region us-central1 --min-instances 0 --max-instances 3 --memory 512Mi --concurrency 40 --service-account verificlean-api@PROJECT.iam.gserviceaccount.com --set-env-vars NODE_ENV=production,AUTH_MODE=firebase,DB_MODE=firestore,STORAGE_MODE=gcs,INTEGRITY_MODE=playintegrity,GCP_PROJECT_ID=PROJECT,STORAGE_BUCKET=BUCKET,ANDROID_PACKAGE_NAME=com.verificlean.app
```

`min-instances=0` es lo que hace que cueste casi nada sin uso; el precio es un arranque
en frío tras un rato inactivo. `max-instances 3` es un tope de gasto deliberado: sin él,
un bucle de reintentos podría escalar a decenas de instancias.

`cargarEnv` aborta el arranque si `NODE_ENV=production` va con algún adaptador en modo
desarrollo. Si el despliegue falla nada más arrancar, mirar ahí primero.

**No** desplegar con `--allow-unauthenticated=false`: la app se autentica con Firebase,
no con IAM. El servicio es público y la autorización la hace el middleware.

## 7. App Android

1. Descargar `google-services.json` de la consola de Firebase a `android/app/`. El plugin
   de Google Services se aplica solo cuando ese archivo existe (ver `app/build.gradle.kts`).
2. En `di/Modulos.kt`, poner `NUMERO_PROYECTO_CLOUD` con el número real. Con 0 la llamada
   a Play Integrity falla y toda evidencia sale como `INTEGRIDAD_NO_EVALUADA`.
3. En `app/build.gradle.kts`, cambiar `API_BASE_URL` del bloque `release` por la URL de
   Cloud Run.
4. Registrar la huella SHA-256 de la clave de firma en Firebase, o la autenticación
   fallará sólo en release.
5. Play Integrity exige que la app esté distribuida por Play (interno vale). En un APK
   instalado por `adb`, `appRecognitionVerdict` será `UNRECOGNIZED_VERSION` y **toda
   evidencia saldrá marcada** — es el comportamiento correcto, pero conviene saberlo
   antes de asustarse con el primer panel lleno de alertas.

## 8. Comprobación

```bash
curl https://verificlean-api-XXXX.run.app/v1/health
```

Después, un login real desde la app y una evidencia de punta a punta. Revisar en Cloud
Logging que aparezca la línea `evidencia marcada como sospechosa` con sus motivos cuando
corresponda: es el log que sostiene la auditoría.

## Coste esperado

Con `min-instances=0`, sin uso el gasto es prácticamente el almacenamiento. Los tramos
gratuitos de Cloud Run y Firestore cubren de sobra una operación de decenas de
supervisores. Lo que sí crece es Cloud Storage: cada evidencia son varios MB. Conviene
decidir pronto una política de retención — por ejemplo, mover a Nearline a los 90 días.
