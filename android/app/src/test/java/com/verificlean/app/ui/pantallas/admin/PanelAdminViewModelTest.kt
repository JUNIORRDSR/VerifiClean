package com.verificlean.app.ui.pantallas.admin

import com.verificlean.app.core.Resultado
import com.verificlean.app.domain.model.Rol
import com.verificlean.app.domain.repository.AdminRepository
import com.verificlean.app.domain.repository.PaginaVisitas
import com.verificlean.app.domain.repository.SesionRepository
import com.verificlean.app.dobles.CatalogoFalso
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

@OptIn(ExperimentalCoroutinesApi::class)
class PanelAdminViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val admin = mockk<AdminRepository>()

    @Before
    fun before() {
        Dispatchers.setMain(dispatcher)
        coEvery { admin.listarVisitas(any(), any(), any(), any(), any(), any()) } returns
            Resultado.Exito(PaginaVisitas(emptyList(), null))
    }

    @After
    fun after() = Dispatchers.resetMain()

    @Test
    fun `abre en ayer y permite cambiar a hoy o todo el historial`() = runTest(dispatcher) {
        val vm = PanelAdminViewModel(admin, sesion())
        advanceUntilIdle()

        val zona = ZoneId.systemDefault()
        val ayer = LocalDate.now(zona).minusDays(1)
        coVerify {
            admin.listarVisitas(
                false,
                null,
                null,
                match { it?.atZone(zona)?.toLocalDate() == ayer },
                match { it?.atZone(zona)?.toLocalDate() == ayer },
                null,
            )
        }

        vm.filtrarPorPeriodo(PeriodoPanel.HOY)
        advanceUntilIdle()
        val hoy = LocalDate.now(zona)
        coVerify {
            admin.listarVisitas(
                false,
                null,
                null,
                match { it?.atZone(zona)?.toLocalDate() == hoy },
                match { it?.atZone(zona)?.toLocalDate() == hoy },
                null,
            )
        }

        vm.filtrarPorPeriodo(PeriodoPanel.TODO)
        advanceUntilIdle()
        coVerify { admin.listarVisitas(false, null, null, null, null, null) }
    }

    private fun sesion() = object : SesionRepository {
        override val usuarioEnSesion: Flow<com.verificlean.app.domain.model.Usuario?> =
            MutableStateFlow(CatalogoFalso.usuario(id = "u_admin", rol = Rol.ADMIN))

        override suspend fun iniciarSesion(email: String, password: String) =
            Resultado.Exito(CatalogoFalso.usuario(id = "u_admin", rol = Rol.ADMIN))

        override suspend fun cerrarSesion() = Unit
    }
}
