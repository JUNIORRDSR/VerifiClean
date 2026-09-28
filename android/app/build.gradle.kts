plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

// El plugin de Google Services exige `google-services.json`, que no está en el repo
// (identifica el proyecto GCP y va en .gitignore). Se aplica sólo si el archivo existe,
// para que el proyecto compile antes de tener GCP configurado.
if (file("google-services.json").exists()) {
    apply(plugin = libs.plugins.google.services.get().pluginId)
}

android {
    namespace = "com.verificlean.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.verificlean.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // minSdk 26 y no 23: `EncryptedFile` de Jetpack Security necesita el keystore
        // respaldado por hardware que llega con API 23, pero por debajo de 26 el
        // manejo de claves AES-GCM en Keystore tiene fallos conocidos en varios
        // fabricantes. 26 cubre >95 % del parque y quita todas las ramas por versión.
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            // 10.0.2.2 es el host de la máquina anfitriona vista desde el emulador.
            buildConfigField("String", "API_BASE_URL", "\"http://10.0.2.2:8080/\"")
            // Con el backend en AUTH_MODE=fake, la app manda `dev:<uid>` en vez de un
            // ID token de Firebase. Permite desarrollar la app entera sin GCP.
            buildConfigField("boolean", "USA_AUTH_FALSO", "true")
            isMinifyEnabled = false
        }
        release {
            buildConfigField("String", "API_BASE_URL", "\"https://verificlean-api.example.run.app/\"")
            buildConfigField("boolean", "USA_AUTH_FALSO", "false")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
            // Kotlin 2.2 avisa en cada `@Inject constructor(@ApplicationContext ...)` de
            // que el destino por defecto de las anotaciones cambiará. Fijarlo ahora
            // adopta el comportamiento futuro y calla ~15 avisos que sólo son ruido.
            freeCompilerArgs.add("-Xannotation-default-target=param-property")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources.excludes += setOf(
            "/META-INF/{AL2.0,LGPL2.1}",
            "META-INF/LICENSE.md",
            "META-INF/LICENSE-notice.md",
        )
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
        // Robolectric necesita los recursos y el manifiesto compilados para levantar el
        // contexto de Android dentro de la JVM.
        unitTests.isIncludeAndroidResources = true
    }
}

// El esquema exportado de Room se versiona: sin él, Room no puede generar ni verificar
// migraciones y cada cambio de entidad obliga a borrar los datos del dispositivo.
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)
    implementation(libs.hilt.work)
    ksp(libs.hilt.work.compiler)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    implementation(libs.work.runtime.ktx)
    implementation(libs.androidx.datastore.preferences)

    implementation(libs.camera.core)
    implementation(libs.camera.camera2)
    implementation(libs.camera.lifecycle)
    implementation(libs.camera.view)

    implementation(libs.play.services.location)
    implementation(libs.play.integrity)

    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.auth)

    implementation(libs.retrofit)
    implementation(libs.retrofit.serialization)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    // `implementation` y no `debugImplementation`: el interceptor se referencia en
    // código común, guardado por `BuildConfig.DEBUG`. Ese guard es de ejecución, así que
    // en release la clase debe existir para compilar. R8 la elimina igualmente, porque
    // con DEBUG=false la rama es código muerto constante.
    implementation(libs.okhttp.logging)

    implementation(libs.coil.compose)

    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.turbine)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.room.testing)
    // Robolectric levanta el framework de Android dentro de la JVM. Es lo que permite
    // probar los DAO de Room contra SQLite real y el Worker con su ciclo de vida, sin
    // emulador — cosa que importa porque en esta máquina no hay ninguno y porque en CI
    // arrancar uno multiplicaría por diez el tiempo de la suite.
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.work.testing)

    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.espresso)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.work.testing)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
