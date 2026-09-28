import { getStorage } from 'firebase-admin/storage'
import { logger } from '../lib/logger'

/**
 * Almacenamiento de las fotos de evidencia.
 *
 * Sube el servidor, no el cliente. Si el cliente subiera directo a Cloud Storage con
 * una URL firmada, el SHA-256 quedaría bajo su control y la detección de imagen
 * duplicada dejaría de significar algo. Aquí los bytes pasan por el servidor, que los
 * hashea y luego los guarda.
 *
 * La metadata EXIF de la foto viaja intacta: se guarda el JPEG tal cual llegó, sin
 * recomprimir ni redimensionar. Reprocesar la imagen borraría el EXIF y además
 * cambiaría el hash respecto al que calculó el dispositivo.
 */

export interface ArchivoGuardado {
  storagePath: string
  storageUrl: string
}

export interface ServicioAlmacenamiento {
  guardar(params: {
    ruta: string
    bytes: Buffer
    contentType: string
    metadata: Record<string, string>
  }): Promise<ArchivoGuardado>

  /** URL de lectura temporal para el admin. `null` si el objeto ya no existe. */
  urlFirmada(storagePath: string, minutosVigencia: number): Promise<string | null>
}

export class ServicioAlmacenamientoGcs implements ServicioAlmacenamiento {
  constructor(private readonly bucket: string) {}

  async guardar(params: {
    ruta: string
    bytes: Buffer
    contentType: string
    metadata: Record<string, string>
  }): Promise<ArchivoGuardado> {
    const archivo = getStorage().bucket(this.bucket).file(params.ruta)
    await archivo.save(params.bytes, {
      contentType: params.contentType,
      resumable: false, // un JPEG de pocos MB en una sola petición; resumable añade viajes
      metadata: { metadata: params.metadata },
    })
    return { storagePath: params.ruta, storageUrl: `gs://${this.bucket}/${params.ruta}` }
  }

  async urlFirmada(storagePath: string, minutosVigencia: number): Promise<string | null> {
    try {
      const archivo = getStorage().bucket(this.bucket).file(storagePath)
      const [url] = await archivo.getSignedUrl({
        action: 'read',
        expires: Date.now() + minutosVigencia * 60_000,
      })
      return url
    } catch (e) {
      // Que falle una URL firmada no debe tumbar el detalle completo de la visita.
      logger.warn('No se pudo firmar URL de evidencia', { storagePath, error: String(e) })
      return null
    }
  }
}

/**
 * Almacén en memoria para desarrollo y tests. Guarda los bytes para que los tests
 * puedan comprobar que se subió exactamente lo que llegó.
 */
export class ServicioAlmacenamientoMemoria implements ServicioAlmacenamiento {
  readonly archivos = new Map<string, { bytes: Buffer; contentType: string; metadata: Record<string, string> }>()

  async guardar(params: {
    ruta: string
    bytes: Buffer
    contentType: string
    metadata: Record<string, string>
  }): Promise<ArchivoGuardado> {
    this.archivos.set(params.ruta, {
      bytes: Buffer.from(params.bytes),
      contentType: params.contentType,
      metadata: params.metadata,
    })
    return { storagePath: params.ruta, storageUrl: `memoria://${params.ruta}` }
  }

  async urlFirmada(storagePath: string): Promise<string | null> {
    return this.archivos.has(storagePath) ? `memoria://${storagePath}?firmada=1` : null
  }
}
