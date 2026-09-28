import request from 'supertest'
import { randomUUID } from 'node:crypto'
import { beforeEach, describe, expect, it } from 'vitest'
import {
  comoUsuario,
  crearBanco,
  escenarioBase,
  jpegDePrueba,
  sembrarSitio,
  sembrarUsuario,
  sembrarVisita,
  type Banco,
} from './helpers'

let banco: Banco
let ctx: Awaited<ReturnType<typeof escenarioBase>>

beforeEach(async () => {
  banco = await crearBanco()
  ctx = await escenarioBase(banco)
})

const DENTRO = { lat: 4.70057, lng: -74.06366 }
const LEJOS = { lat: 4.667209, lng: -74.05372 }

const subir = (visitId: string, coords: { lat: number; lng: number }, contenido = randomUUID()) =>
  request(banco.app)
    .post(`/v1/visits/${visitId}/evidence`)
    .set('Authorization', comoUsuario(ctx.supervisor.id))
    .field('clienteEvidenciaId', randomUUID())
    .field('lat', String(coords.lat))
    .field('lng', String(coords.lng))
    .field('integrityToken', 'fake:confiable')
    .attach('foto', jpegDePrueba(contenido), { filename: 'e.jpg', contentType: 'image/jpeg' })

describe('permisos del dashboard', () => {
  it('un supervisor no entra a /v1/admin', async () => {
    const res = await request(banco.app)
      .get('/v1/admin/visits')
      .set('Authorization', comoUsuario(ctx.supervisor.id))
      .expect(403)
    expect(res.body.error.codigo).toBe('PROHIBIDO')
  })

  it('un supervisor no puede crear sedes ni usuarios', async () => {
    await request(banco.app)
      .post('/v1/sites')
      .set('Authorization', comoUsuario(ctx.supervisor.id))
      .send({ clienteId: 'x', nombreCliente: 'X', nombreSede: 'S', direccion: 'D', lat: 0, lng: 0 })
      .expect(403)

    await request(banco.app)
      .post('/v1/users')
      .set('Authorization', comoUsuario(ctx.supervisor.id))
      .send({ nombre: 'Nuevo', email: 'n@x.test', password: 'password123', rol: 'supervisor' })
      .expect(403)
  })
})

describe('GET /v1/admin/visits', () => {
  it('ve las visitas de todos los supervisores', async () => {
    const otro = await sembrarUsuario(banco, { id: 'u_sup_2', rol: 'supervisor' })
    await sembrarVisita(banco, { id: 'visit_otro', siteId: ctx.sitio.id, supervisorId: otro.id })

    const res = await request(banco.app)
      .get('/v1/admin/visits')
      .set('Authorization', comoUsuario(ctx.admin.id))
      .expect(200)

    expect(res.body.items).toHaveLength(2)
  })

  it('filtra sólo las visitas con evidencia sospechosa', async () => {
    const limpia = await sembrarVisita(banco, {
      id: 'visit_limpia',
      siteId: ctx.sitio.id,
      supervisorId: ctx.supervisor.id,
    })
    await subir(limpia.id, DENTRO).expect(201)
    await subir(ctx.visita.id, LEJOS).expect(201)

    const res = await request(banco.app)
      .get('/v1/admin/visits?sospechosas=true')
      .set('Authorization', comoUsuario(ctx.admin.id))
      .expect(200)

    expect(res.body.items).toHaveLength(1)
    expect(res.body.items[0].id).toBe(ctx.visita.id)
  })

  it('trata sospechosas=false como "sin filtro", no como truthy', async () => {
    const res = await request(banco.app)
      .get('/v1/admin/visits?sospechosas=false')
      .set('Authorization', comoUsuario(ctx.admin.id))
      .expect(200)
    expect(res.body.items).toHaveLength(1)
  })

  it('filtra por sede', async () => {
    const otraSede = await sembrarSitio(banco, { id: 'site_2', nombreSede: 'Otra' })
    await sembrarVisita(banco, {
      id: 'visit_otra_sede',
      siteId: otraSede.id,
      supervisorId: ctx.supervisor.id,
    })

    const res = await request(banco.app)
      .get(`/v1/admin/visits?siteId=${otraSede.id}`)
      .set('Authorization', comoUsuario(ctx.admin.id))
      .expect(200)

    expect(res.body.items).toHaveLength(1)
    expect(res.body.items[0].id).toBe('visit_otra_sede')
  })
})

describe('GET /v1/admin/visits/:id', () => {
  it('entrega evidencias completas, URL firmada y alertas agregadas', async () => {
    await subir(ctx.visita.id, LEJOS).expect(201)

    const res = await request(banco.app)
      .get(`/v1/admin/visits/${ctx.visita.id}`)
      .set('Authorization', comoUsuario(ctx.admin.id))
      .expect(200)

    expect(res.body.evidenciasCompletas).toHaveLength(1)
    expect(res.body.evidenciasCompletas[0].hashSHA256).toMatch(/^[a-f0-9]{64}$/)
    expect(res.body.evidenciasCompletas[0].fotoUrl).toContain('firmada=1')

    const alerta = res.body.alertas.find((a: { motivo: string }) => a.motivo === 'FUERA_DE_GEOFENCE')
    expect(alerta.conteo).toBe(1)
    expect(alerta.descripcion).toBeTruthy()
  })

  it('agrupa el mismo motivo repetido en varias evidencias', async () => {
    await subir(ctx.visita.id, LEJOS, 'a').expect(201)
    await subir(ctx.visita.id, LEJOS, 'b').expect(201)

    const res = await request(banco.app)
      .get(`/v1/admin/visits/${ctx.visita.id}`)
      .set('Authorization', comoUsuario(ctx.admin.id))
      .expect(200)

    const alerta = res.body.alertas.find((a: { motivo: string }) => a.motivo === 'FUERA_DE_GEOFENCE')
    expect(alerta.conteo).toBe(2)
  })
})

describe('PATCH /v1/admin/visits/:id/alert-review', () => {
  it('guarda estado, nota y actor fijado por el servidor', async () => {
    await subir(ctx.visita.id, LEJOS).expect(201)

    const res = await request(banco.app)
      .patch(`/v1/admin/visits/${ctx.visita.id}/alert-review`)
      .set('Authorization', comoUsuario(ctx.admin.id))
      .send({ estado: 'justificada', nota: 'La sede operó temporalmente en el acceso norte.' })
      .expect(200)

    expect(res.body.revisionAlerta).toMatchObject({
      estado: 'justificada',
      nota: 'La sede operó temporalmente en el acceso norte.',
      revisadaPorId: ctx.admin.id,
      revisadaPorNombre: ctx.admin.nombre,
    })
    expect(Date.parse(res.body.revisionAlerta.revisadaEn)).not.toBeNaN()

    const guardada = await banco.db.visitas.obtener(ctx.visita.id)
    expect(guardada?.revisionAlerta.revisadaPorId).toBe(ctx.admin.id)
  })

  it.each(['justificada', 'escalada'])('%s exige una nota no vacía', async (estado) => {
    const res = await request(banco.app)
      .patch(`/v1/admin/visits/${ctx.visita.id}/alert-review`)
      .set('Authorization', comoUsuario(ctx.admin.id))
      .send({ estado, nota: '   ' })
      .expect(400)

    expect(res.body.error.codigo).toBe('SOLICITUD_INVALIDA')
  })

  it('no acepta que el cliente suplante actor u hora', async () => {
    await request(banco.app)
      .patch(`/v1/admin/visits/${ctx.visita.id}/alert-review`)
      .set('Authorization', comoUsuario(ctx.admin.id))
      .send({
        estado: 'revisada',
        revisadaPorId: 'u_inyectado',
        revisadaEn: '2020-01-01T00:00:00.000Z',
      })
      .expect(400)
  })

  it('pendiente reabre el caso y limpia la memoria del cierre anterior', async () => {
    await subir(ctx.visita.id, LEJOS).expect(201)
    await request(banco.app)
      .patch(`/v1/admin/visits/${ctx.visita.id}/alert-review`)
      .set('Authorization', comoUsuario(ctx.admin.id))
      .send({ estado: 'revisada', nota: 'Revisión inicial.' })
      .expect(200)

    const res = await request(banco.app)
      .patch(`/v1/admin/visits/${ctx.visita.id}/alert-review`)
      .set('Authorization', comoUsuario(ctx.admin.id))
      .send({ estado: 'pendiente' })
      .expect(200)

    expect(res.body.revisionAlerta).toEqual({
      estado: 'pendiente',
      nota: null,
      revisadaPorId: null,
      revisadaPorNombre: null,
      revisadaEn: null,
    })
  })

  it('una evidencia sospechosa nueva reabre atómicamente un caso cerrado', async () => {
    await subir(ctx.visita.id, LEJOS, 'alerta-inicial').expect(201)
    await request(banco.app)
      .patch(`/v1/admin/visits/${ctx.visita.id}/alert-review`)
      .set('Authorization', comoUsuario(ctx.admin.id))
      .send({ estado: 'escalada', nota: 'Remitido a operaciones.' })
      .expect(200)

    await subir(ctx.visita.id, LEJOS, 'alerta-nueva').expect(201)

    const guardada = await banco.db.visitas.obtener(ctx.visita.id)
    expect(guardada?.revisionAlerta).toEqual({
      estado: 'pendiente',
      nota: null,
      revisadaPorId: null,
      revisadaPorNombre: null,
      revisadaEn: null,
    })
  })

  it('una evidencia limpia no reabre un caso cerrado', async () => {
    await subir(ctx.visita.id, LEJOS, 'alerta-inicial-antes-de-limpia').expect(201)
    await request(banco.app)
      .patch(`/v1/admin/visits/${ctx.visita.id}/alert-review`)
      .set('Authorization', comoUsuario(ctx.admin.id))
      .send({ estado: 'revisada' })
      .expect(200)

    await subir(ctx.visita.id, DENTRO, 'evidencia-limpia').expect(201)

    const guardada = await banco.db.visitas.obtener(ctx.visita.id)
    expect(guardada?.revisionAlerta.estado).toBe('revisada')
  })

  it('un supervisor no puede revisar alertas ni recibe la memoria en sus DTO', async () => {
    await request(banco.app)
      .patch(`/v1/admin/visits/${ctx.visita.id}/alert-review`)
      .set('Authorization', comoUsuario(ctx.supervisor.id))
      .send({ estado: 'revisada' })
      .expect(403)

    const lista = await request(banco.app)
      .get('/v1/visits')
      .set('Authorization', comoUsuario(ctx.supervisor.id))
      .expect(200)
    expect(lista.body.items[0]).not.toHaveProperty('revisionAlerta')

    const detalle = await request(banco.app)
      .get(`/v1/visits/${ctx.visita.id}`)
      .set('Authorization', comoUsuario(ctx.supervisor.id))
      .expect(200)
    expect(detalle.body).not.toHaveProperty('revisionAlerta')
  })
})

describe('GET /v1/admin/reports', () => {
  it('resume cumplimiento y alertas del cliente en el rango', async () => {
    await subir(ctx.visita.id, LEJOS).expect(201)
    const visita = await banco.db.visitas.obtener(ctx.visita.id)
    await request(banco.app)
      .patch(`/v1/visits/${ctx.visita.id}/complete`)
      .set('Authorization', comoUsuario(ctx.supervisor.id))
      .send({ checklist: visita!.checklist.map((i) => ({ ...i, respuesta: 'si' })) })
      .expect(200)

    await sembrarVisita(banco, {
      id: 'visit_pendiente',
      siteId: ctx.sitio.id,
      supervisorId: ctx.supervisor.id,
    })

    const res = await request(banco.app)
      .get(
        `/v1/admin/reports?clienteId=${ctx.sitio.clienteId}` +
          '&desde=2026-01-01T00:00:00.000Z&hasta=2026-12-31T23:59:59.000Z',
      )
      .set('Authorization', comoUsuario(ctx.admin.id))
      .expect(200)

    expect(res.body.resumen.visitasAsignadas).toBe(2)
    expect(res.body.resumen.visitasCompletadas).toBe(1)
    expect(res.body.resumen.cumplimientoPorcentaje).toBe(50)
    expect(res.body.resumen.evidenciasSospechosas).toBe(1)
    expect(res.body.resumen.motivosFrecuentes[0].motivo).toBe('FUERA_DE_GEOFENCE')
    expect(res.body.porSede).toHaveLength(1)
    expect(res.body.porSede[0].evidenciasSospechosas).toBe(1)
  })

  it('devuelve 0 % de cumplimiento —no NaN— cuando no hay visitas en el rango', async () => {
    const res = await request(banco.app)
      .get('/v1/admin/reports?clienteId=cli_vacio&desde=2020-01-01T00:00:00.000Z&hasta=2020-02-01T00:00:00.000Z')
      .set('Authorization', comoUsuario(ctx.admin.id))
      .expect(200)

    expect(res.body.resumen.cumplimientoPorcentaje).toBe(0)
    expect(res.body.resumen.visitasAsignadas).toBe(0)
  })

  it('rechaza un rango invertido', async () => {
    const res = await request(banco.app)
      .get('/v1/admin/reports?clienteId=x&desde=2026-06-01T00:00:00.000Z&hasta=2026-01-01T00:00:00.000Z')
      .set('Authorization', comoUsuario(ctx.admin.id))
      .expect(400)
    expect(res.body.error.codigo).toBe('SOLICITUD_INVALIDA')
  })

  it('exige clienteId', async () => {
    await request(banco.app)
      .get('/v1/admin/reports?desde=2026-01-01T00:00:00.000Z&hasta=2026-02-01T00:00:00.000Z')
      .set('Authorization', comoUsuario(ctx.admin.id))
      .expect(400)
  })
})
