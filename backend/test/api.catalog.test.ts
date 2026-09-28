import request from 'supertest'
import { randomUUID } from 'node:crypto'
import { beforeEach, describe, expect, it } from 'vitest'
import { comoUsuario, crearBanco, escenarioBase, type Banco } from './helpers'

let banco: Banco
let ctx: Awaited<ReturnType<typeof escenarioBase>>

beforeEach(async () => {
  banco = await crearBanco()
  ctx = await escenarioBase(banco)
})

const comoAdmin = () => comoUsuario(ctx.admin.id)

describe('POST /v1/sites', () => {
  const sedeValida = {
    clienteId: 'cli_nuevo',
    nombreCliente: 'Cliente Nuevo',
    nombreSede: 'Sede Norte',
    direccion: 'Cra 7 #100-20',
    lat: 4.686,
    lng: -74.045,
  }

  it('crea la sede con radio por defecto de 150 m', async () => {
    const res = await request(banco.app)
      .post('/v1/sites')
      .set('Authorization', comoAdmin())
      .set('Idempotency-Key', randomUUID())
      .send(sedeValida)
      .expect(201)

    expect(res.body.radioPermitidoMetros).toBe(150)
    expect(res.body.activo).toBe(true)
    expect(res.body.id).toBeTruthy()
  })

  it('exige una Idempotency-Key UUID', async () => {
    await request(banco.app)
      .post('/v1/sites')
      .set('Authorization', comoAdmin())
      .send(sedeValida)
      .expect(400)

    const res = await request(banco.app)
      .post('/v1/sites')
      .set('Authorization', comoAdmin())
      .set('Idempotency-Key', 'no-es-un-uuid')
      .send(sedeValida)
      .expect(400)

    expect(res.body.error.detalles).toHaveProperty('idempotencyKey')
  })

  it('devuelve la misma sede al repetir clave y carga', async () => {
    const clave = randomUUID()
    const primera = await request(banco.app)
      .post('/v1/sites')
      .set('Authorization', comoAdmin())
      .set('Idempotency-Key', clave)
      .send(sedeValida)
      .expect(201)
    const repetida = await request(banco.app)
      .post('/v1/sites')
      .set('Authorization', comoAdmin())
      .set('Idempotency-Key', clave)
      .send(sedeValida)
      .expect(200)

    expect(repetida.body).toEqual(primera.body)
    const listado = await request(banco.app)
      .get('/v1/sites?clienteId=cli_nuevo')
      .set('Authorization', comoAdmin())
      .expect(200)
    expect(listado.body.items).toHaveLength(1)
  })

  it('rechaza reutilizar la clave para otra sede', async () => {
    const clave = randomUUID()
    await request(banco.app)
      .post('/v1/sites')
      .set('Authorization', comoAdmin())
      .set('Idempotency-Key', clave)
      .send(sedeValida)
      .expect(201)

    const res = await request(banco.app)
      .post('/v1/sites')
      .set('Authorization', comoAdmin())
      .set('Idempotency-Key', clave)
      .send({ ...sedeValida, nombreSede: 'Otra sede' })
      .expect(409)
    expect(res.body.error.codigo).toBe('CONFLICTO')
  })

  it('impide partir un cliente por id o nombre divergente', async () => {
    await request(banco.app)
      .post('/v1/sites')
      .set('Authorization', comoAdmin())
      .set('Idempotency-Key', randomUUID())
      .send(sedeValida)
      .expect(201)

    const nombreDistinto = await request(banco.app)
      .post('/v1/sites')
      .set('Authorization', comoAdmin())
      .set('Idempotency-Key', randomUUID())
      .send({ ...sedeValida, nombreCliente: 'Otro nombre', nombreSede: 'Sede Sur' })
      .expect(409)
    expect(nombreDistinto.body.error.mensaje).toContain('lista existente')

    const mismoNombreOtroId = await request(banco.app)
      .post('/v1/sites')
      .set('Authorization', comoAdmin())
      .set('Idempotency-Key', randomUUID())
      .send({
        ...sedeValida,
        clienteId: 'cli_duplicado',
        nombreCliente: 'CLIENTE NUEVO',
        nombreSede: 'Sede Occidente',
      })
      .expect(409)
    expect(mismoNombreOtroId.body.error.mensaje).toContain('mantener unidos')
  })

  it('protege dos altas simultáneas con la misma clave', async () => {
    const clave = randomUUID()
    const enviar = () =>
      request(banco.app)
        .post('/v1/sites')
        .set('Authorization', comoAdmin())
        .set('Idempotency-Key', clave)
        .send(sedeValida)

    const respuestas = await Promise.all([enviar(), enviar()])
    expect(respuestas.map((r) => r.status).sort()).toEqual([200, 201])
    expect(respuestas[0].body.id).toBe(respuestas[1].body.id)
  })

  it('rechaza un radio por debajo del mínimo de 50 m', async () => {
    const res = await request(banco.app)
      .post('/v1/sites')
      .set('Authorization', comoAdmin())
      .set('Idempotency-Key', randomUUID())
      .send({ ...sedeValida, radioPermitidoMetros: 10 })
      .expect(400)

    expect(res.body.error.detalles).toHaveProperty('radioPermitidoMetros')
  })

  it('rechaza coordenadas fuera de rango', async () => {
    await request(banco.app)
      .post('/v1/sites')
      .set('Authorization', comoAdmin())
      .set('Idempotency-Key', randomUUID())
      .send({ ...sedeValida, lat: 120 })
      .expect(400)
  })

  it('lista filtrando por cliente', async () => {
    await request(banco.app)
      .post('/v1/sites')
      .set('Authorization', comoAdmin())
      .set('Idempotency-Key', randomUUID())
      .send(sedeValida)
      .expect(201)

    const res = await request(banco.app)
      .get('/v1/sites?clienteId=cli_nuevo')
      .set('Authorization', comoAdmin())
      .expect(200)

    expect(res.body.items).toHaveLength(1)
    expect(res.body.items[0].nombreSede).toBe('Sede Norte')
  })
})

describe('POST /v1/users', () => {
  const usuarioValido = {
    nombre: 'Nueva Supervisora',
    email: 'Nueva@Verificlean.test',
    password: 'password-larga',
    rol: 'supervisor' as const,
  }

  it('crea el usuario y normaliza el email a minúsculas', async () => {
    const res = await request(banco.app)
      .post('/v1/users')
      .set('Authorization', comoAdmin())
      .send(usuarioValido)
      .expect(201)

    expect(res.body.email).toBe('nueva@verificlean.test')
    expect(res.body.rol).toBe('supervisor')
    expect(res.body.activo).toBe(true)
    expect(res.body).not.toHaveProperty('password')
  })

  it('devuelve 409 si el email ya existe', async () => {
    await request(banco.app).post('/v1/users').set('Authorization', comoAdmin()).send(usuarioValido).expect(201)
    const res = await request(banco.app)
      .post('/v1/users')
      .set('Authorization', comoAdmin())
      .send(usuarioValido)
      .expect(409)

    expect(res.body.error.codigo).toBe('CONFLICTO')
  })

  it('rechaza contraseñas cortas', async () => {
    await request(banco.app)
      .post('/v1/users')
      .set('Authorization', comoAdmin())
      .send({ ...usuarioValido, password: 'corta' })
      .expect(400)
  })

  it('rechaza un rol inventado', async () => {
    await request(banco.app)
      .post('/v1/users')
      .set('Authorization', comoAdmin())
      .send({ ...usuarioValido, rol: 'superadmin' })
      .expect(400)
  })
})

describe('POST /v1/visits/assign', () => {
  const manana = new Date(Date.now() + 86_400_000).toISOString()

  const asignacionValida = () => ({
    siteId: ctx.sitio.id,
    supervisorId: ctx.supervisor.id,
    fechaAsignada: manana,
  })

  it('asigna la visita denormalizando los datos de la sede', async () => {
    const res = await request(banco.app)
      .post('/v1/visits/assign')
      .set('Authorization', comoAdmin())
      .set('Idempotency-Key', randomUUID())
      .send({ siteId: ctx.sitio.id, supervisorId: ctx.supervisor.id, fechaAsignada: manana })
      .expect(201)

    expect(res.body.estado).toBe('pendiente')
    expect(res.body.siteNombreSede).toBe(ctx.sitio.nombreSede)
    expect(res.body.supervisorNombre).toBe(ctx.supervisor.nombre)
    expect(res.body.checklist.length).toBeGreaterThan(0)
    expect(res.body.evidenciaCount).toBe(0)
  })

  it('exige una Idempotency-Key UUID', async () => {
    await request(banco.app)
      .post('/v1/visits/assign')
      .set('Authorization', comoAdmin())
      .send(asignacionValida())
      .expect(400)

    await request(banco.app)
      .post('/v1/visits/assign')
      .set('Authorization', comoAdmin())
      .set('Idempotency-Key', 'clave-invalida')
      .send(asignacionValida())
      .expect(400)
  })

  it('devuelve la misma visita al repetir clave y carga', async () => {
    const clave = randomUUID()
    const primera = await request(banco.app)
      .post('/v1/visits/assign')
      .set('Authorization', comoAdmin())
      .set('Idempotency-Key', clave)
      .send(asignacionValida())
      .expect(201)
    const repetida = await request(banco.app)
      .post('/v1/visits/assign')
      .set('Authorization', comoAdmin())
      .set('Idempotency-Key', clave)
      .send(asignacionValida())
      .expect(200)

    expect(repetida.body).toEqual(primera.body)
    expect(repetida.body.id).toBe(clave)
  })

  it('rechaza reutilizar la clave para otra asignación', async () => {
    const clave = randomUUID()
    await request(banco.app)
      .post('/v1/visits/assign')
      .set('Authorization', comoAdmin())
      .set('Idempotency-Key', clave)
      .send(asignacionValida())
      .expect(201)

    const res = await request(banco.app)
      .post('/v1/visits/assign')
      .set('Authorization', comoAdmin())
      .set('Idempotency-Key', clave)
      .send({ ...asignacionValida(), supervisorId: ctx.admin.id })
      .expect(409)
    expect(res.body.error.codigo).toBe('CONFLICTO')
  })

  it('reconoce el reenvío aunque la sede se haya desactivado después', async () => {
    const clave = randomUUID()
    await request(banco.app)
      .post('/v1/visits/assign')
      .set('Authorization', comoAdmin())
      .set('Idempotency-Key', clave)
      .send(asignacionValida())
      .expect(201)
    await request(banco.app)
      .patch(`/v1/sites/${ctx.sitio.id}`)
      .set('Authorization', comoAdmin())
      .send({ activo: false })
      .expect(200)

    const repetida = await request(banco.app)
      .post('/v1/visits/assign')
      .set('Authorization', comoAdmin())
      .set('Idempotency-Key', clave)
      .send(asignacionValida())
      .expect(200)
    expect(repetida.body.id).toBe(clave)
  })

  it('protege dos asignaciones simultáneas con la misma clave', async () => {
    const clave = randomUUID()
    const enviar = () =>
      request(banco.app)
        .post('/v1/visits/assign')
        .set('Authorization', comoAdmin())
        .set('Idempotency-Key', clave)
        .send(asignacionValida())

    const respuestas = await Promise.all([enviar(), enviar()])
    expect(respuestas.map((r) => r.status).sort()).toEqual([200, 201])
    expect(respuestas[0].body.id).toBe(respuestas[1].body.id)
  })

  it('congela el radio: cambiarlo después no altera la visita ya asignada', async () => {
    const res = await request(banco.app)
      .post('/v1/visits/assign')
      .set('Authorization', comoAdmin())
      .set('Idempotency-Key', randomUUID())
      .send({ siteId: ctx.sitio.id, supervisorId: ctx.supervisor.id, fechaAsignada: manana })
      .expect(201)

    await banco.db.sitios.crear({ ...ctx.sitio, radioPermitidoMetros: 2000 })

    const visita = await banco.db.visitas.obtener(res.body.id)
    expect(visita?.siteRadioPermitidoMetros).toBe(150)
  })

  it('rechaza asignarle una visita a un admin', async () => {
    const res = await request(banco.app)
      .post('/v1/visits/assign')
      .set('Authorization', comoAdmin())
      .set('Idempotency-Key', randomUUID())
      .send({ siteId: ctx.sitio.id, supervisorId: ctx.admin.id, fechaAsignada: manana })
      .expect(400)

    expect(res.body.error.mensaje).toContain('rol supervisor')
  })

  it('devuelve 404 si la sede no existe', async () => {
    await request(banco.app)
      .post('/v1/visits/assign')
      .set('Authorization', comoAdmin())
      .set('Idempotency-Key', randomUUID())
      .send({ siteId: 'site_inexistente', supervisorId: ctx.supervisor.id, fechaAsignada: manana })
      .expect(404)
  })

  it('rechaza una fecha que no es ISO-8601', async () => {
    await request(banco.app)
      .post('/v1/visits/assign')
      .set('Authorization', comoAdmin())
      .set('Idempotency-Key', randomUUID())
      .send({ siteId: ctx.sitio.id, supervisorId: ctx.supervisor.id, fechaAsignada: 'mañana' })
      .expect(400)
  })
})

describe('PATCH /v1/users/:id', () => {
  it('desactiva a un supervisor y le corta el acceso en la siguiente peticion', async () => {
    await request(banco.app)
      .patch(`/v1/users/${ctx.supervisor.id}`)
      .set('Authorization', comoAdmin())
      .send({ activo: false })
      .expect(200)

    // Lo que hace real la baja: `exigirAuth` lee `activo` en cada peticion, asi que no
    // hay que esperar a que caduque su token.
    const res = await request(banco.app)
      .get('/v1/visits')
      .set('Authorization', comoUsuario(ctx.supervisor.id))
      .expect(403)

    expect(res.body.error.mensaje).toContain('inactivo')
  })

  it('conserva el historico del supervisor dado de baja', async () => {
    await request(banco.app)
      .patch(`/v1/users/${ctx.supervisor.id}`)
      .set('Authorization', comoAdmin())
      .send({ activo: false })
      .expect(200)

    // Dar de baja a una persona no es rehacer lo que hizo mientras trabajaba: la visita
    // que tenia asignada sigue existiendo y contando para el cumplimiento del cliente.
    const res = await request(banco.app)
      .get('/v1/admin/visits')
      .set('Authorization', comoAdmin())
      .expect(200)

    expect(res.body.items.map((v: { id: string }) => v.id)).toContain(ctx.visita.id)
  })

  it('el admin no puede desactivarse a si mismo', async () => {
    // Si ademas fuera el unico admin, no quedaria nadie capaz de reactivar a nadie y el
    // sistema solo se recuperaria escribiendo a mano en Firestore.
    const res = await request(banco.app)
      .patch(`/v1/users/${ctx.admin.id}`)
      .set('Authorization', comoAdmin())
      .send({ activo: false })
      .expect(400)

    expect(res.body.error.mensaje).toContain('tu propia cuenta')
  })

  it('reactiva a un supervisor dado de baja', async () => {
    await request(banco.app)
      .patch(`/v1/users/${ctx.supervisor.id}`)
      .set('Authorization', comoAdmin())
      .send({ activo: false })
      .expect(200)

    await request(banco.app)
      .patch(`/v1/users/${ctx.supervisor.id}`)
      .set('Authorization', comoAdmin())
      .send({ activo: true })
      .expect(200)

    await request(banco.app)
      .get('/v1/visits')
      .set('Authorization', comoUsuario(ctx.supervisor.id))
      .expect(200)
  })

  it('un supervisor no puede desactivar a nadie', async () => {
    await request(banco.app)
      .patch(`/v1/users/${ctx.admin.id}`)
      .set('Authorization', comoUsuario(ctx.supervisor.id))
      .send({ activo: false })
      .expect(403)
  })

  it('devuelve 404 si el usuario no existe', async () => {
    await request(banco.app)
      .patch('/v1/users/u_inexistente')
      .set('Authorization', comoAdmin())
      .send({ activo: false })
      .expect(404)
  })

  it('rechaza un cuerpo sin `activo`', async () => {
    await request(banco.app)
      .patch(`/v1/users/${ctx.supervisor.id}`)
      .set('Authorization', comoAdmin())
      .send({})
      .expect(400)
  })
})

describe('PATCH /v1/sites/:id', () => {
  it('desactiva una sede y deja de poder asignarsele visitas', async () => {
    await request(banco.app)
      .patch(`/v1/sites/${ctx.sitio.id}`)
      .set('Authorization', comoAdmin())
      .send({ activo: false })
      .expect(200)

    const res = await request(banco.app)
      .post('/v1/visits/assign')
      .set('Authorization', comoAdmin())
      .set('Idempotency-Key', randomUUID())
      .send({
        siteId: ctx.sitio.id,
        supervisorId: ctx.supervisor.id,
        fechaAsignada: new Date('2026-09-01T13:00:00.000Z').toISOString(),
      })
      .expect(400)

    expect(res.body.error.mensaje).toContain('inactiva')
  })

  it('la visita ya asignada a una sede desactivada sigue viva', async () => {
    await request(banco.app)
      .patch(`/v1/sites/${ctx.sitio.id}`)
      .set('Authorization', comoAdmin())
      .send({ activo: false })
      .expect(200)

    // El supervisor puede estar de camino. Cancelarle el trabajo desde el servidor sin
    // avisarle seria peor que la sede sobrante en el catalogo.
    const res = await request(banco.app)
      .get(`/v1/visits/${ctx.visita.id}`)
      .set('Authorization', comoUsuario(ctx.supervisor.id))
      .expect(200)

    expect(res.body.id).toBe(ctx.visita.id)
  })

  it('la sede desactivada desaparece del listado de activas pero no del completo', async () => {
    await request(banco.app)
      .patch(`/v1/sites/${ctx.sitio.id}`)
      .set('Authorization', comoAdmin())
      .send({ activo: false })
      .expect(200)

    const activas = await request(banco.app)
      .get('/v1/sites?activo=true')
      .set('Authorization', comoAdmin())
      .expect(200)
    expect(activas.body.items).toHaveLength(0)

    const todas = await request(banco.app)
      .get('/v1/sites')
      .set('Authorization', comoAdmin())
      .expect(200)
    expect(todas.body.items).toHaveLength(1)
    expect(todas.body.items[0].activo).toBe(false)
  })

  it('un supervisor no puede desactivar una sede', async () => {
    await request(banco.app)
      .patch(`/v1/sites/${ctx.sitio.id}`)
      .set('Authorization', comoUsuario(ctx.supervisor.id))
      .send({ activo: false })
      .expect(403)
  })

  it('devuelve 404 si la sede no existe', async () => {
    await request(banco.app)
      .patch('/v1/sites/site_inexistente')
      .set('Authorization', comoAdmin())
      .send({ activo: false })
      .expect(404)
  })
})

describe('rutas inexistentes', () => {
  it('devuelven 401 sin token: la existencia de una ruta no se revela antes de autenticar', async () => {
    const res = await request(banco.app).get('/v1/no-existe').expect(401)
    expect(res.body.error.codigo).toBe('NO_AUTORIZADO')
  })

  it('devuelven 404 con el envelope de la API una vez autenticado', async () => {
    const res = await request(banco.app)
      .get('/v1/no-existe')
      .set('Authorization', comoAdmin())
      .expect(404)
    expect(res.body.error.codigo).toBe('NO_ENCONTRADO')
  })

  it('las rutas públicas siguen respondiendo sin token', async () => {
    await request(banco.app).get('/v1/health').expect(200)
  })
})
