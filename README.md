# 🎮 QsConnections

**Gestiona tu PS3, PS4 y PS5 directamente desde Android.**

QsConnections es una aplicación para Android desarrollada con **Kotlin y Jetpack Compose** que permite administrar diferentes funciones de red de las consolas PlayStation desde una interfaz moderna, oscura y con inspiración en el ecosistema PlayStation.

Envía payloads, administra archivos mediante FTP y transfiere paquetes PKG a tu PS4 directamente desde el almacenamiento de tu teléfono, sin depender de servidores externos.

> [!IMPORTANT]
> QsConnections está diseñada para utilizarse en una red local. Las funciones disponibles dependen del modelo de consola, su configuración y el software compatible instalado en ella.

## ✨ Características principales

### 🚀 Envío de payloads

* Envío de archivos `.bin` mediante TCP.
* Compatibilidad con puertos configurables, incluidos `9020` y `9090`.
* Selección de archivos desde el almacenamiento del dispositivo.
* Registro de actividad para facilitar el diagnóstico de errores.

### 📦 Instalador de PKG para PS4

Transfiere paquetes PKG desde tu teléfono directamente a la consola mediante un servidor HTTP local.

* Compatibilidad con **etaHEN** y **Remote Package Installer (RPI)**, según la configuración de la consola.
* Validación local de la cabecera del PKG, el `CONTENT_ID` y el tamaño declarado.
* Servidor HTTP integrado con soporte para solicitudes `Range` (`200 OK` y `206 Partial Content`).
* Detección del servicio de instalación disponible en el puerto `12800`.
* Gestión del error `0x80990015`, relacionado con tareas de descarga duplicadas.
* Transferencia desde el almacenamiento del teléfono sin necesidad de alojar el paquete en un servidor externo.

> **Nota:** la validación local comprueba determinados datos del paquete, pero no garantiza que el PKG sea íntegro, legítimo o compatible con la consola.

### 📁 Explorador FTP

Administra archivos de las consolas desde tu dispositivo Android.

* Navegar por directorios y consultar archivos.
* Subir y descargar archivos.
* Renombrar y eliminar archivos.
* Configurar manualmente la dirección IP, el puerto y las credenciales.
* Usar perfiles preconfigurados para facilitar la conexión.

**Puertos predeterminados:**

| Consola | Puerto FTP |
| ------- | ---------: |
| PS3     |       `21` |
| PS4     |     `2121` |
| PS5     |     `2121` |

Los puertos pueden modificarse según la configuración del servidor FTP de cada consola.

### 📊 Gestión de transferencias

* Cola centralizada de transferencias.
* Seguimiento del progreso en tiempo real.
* Velocidad de transferencia y tiempo restante estimado.
* Pausa, reanudación y cancelación cuando la operación lo permita.
* Notificaciones de progreso y finalización.
* Ejecución de operaciones en segundo plano mediante un servicio en primer plano.

> La posibilidad de reanudar una transferencia depende del protocolo y del soporte disponible en el destino.

### 🔌 Perfiles y conexiones

* Guardar perfiles de consola en una base de datos local Room.
* Almacenar direcciones IP, puertos y credenciales FTP.
* Escanear la red local para identificar dispositivos con puertos compatibles abiertos.
* Enviar paquetes Wake-on-LAN a partir de la dirección MAC guardada.
* Reutilizar perfiles para evitar configurar la conexión en cada uso.

### ⚙️ Personalización

* Interfaz oscura con estilo inspirado en PlayStation.
* Historial de actividad copiable.
* Ajustes de sonido y vibración.
* Notificaciones de transferencia.
* Opción para compartir la aplicación.

---

## 🎮 Compatibilidad

| Función                         |         PS3         |         PS4         |         PS5         |
| ------------------------------- | :-----------------: | :-----------------: | :-----------------: |
| Envío de payloads               |          ✓          |          ✓          |          ✓          |
| Explorador FTP                  |          ✓          |          ✓          |          ✓          |
| Instalación de PKG desde la app |          —          |          ✓          |          —          |
| Wake-on-LAN                     | Según configuración | Según configuración | Según configuración |
| Escaneo de red                  |          ✓          |          ✓          |          ✓          |

La compatibilidad efectiva depende del firmware, los servicios activos, los permisos y las herramientas instaladas en cada consola.

## 🌐 Puertos utilizados

| Puerto  | Servicio | Descripción                                     |
| ------- | -------- | ----------------------------------------------- |
| `9020`  | TCP      | Envío de payloads                               |
| `9090`  | TCP      | Puerto alternativo para payloads                |
| `21`    | FTP      | Puerto predeterminado para PS3                  |
| `2121`  | FTP      | Puerto predeterminado para PS4 y PS5            |
| `12800` | HTTP/API | Servicio de instalación en la consola           |
| `9898`  | HTTP     | Servidor local del teléfono para transferir PKG |

Los puertos indicados son valores predeterminados o habituales. Debes verificar los que utiliza realmente tu consola.

## 📋 Requisitos

* Android 7.0 o superior (API 24).
* Conexión Wi-Fi o de red local compartida entre el teléfono y la consola.
* Permisos de Android necesarios para acceder a los archivos y ejecutar transferencias.
* Un servicio FTP habilitado en la consola para utilizar el explorador.
* Un entorno compatible con la recepción de payloads para utilizar esa función.
* En PS4, etaHEN o Remote Package Installer para instalar paquetes mediante la aplicación.

**Importante para RPI:** mantén la aplicación en primer plano hasta que la consola haya iniciado la descarga si tu configuración requiere que el servidor HTTP del teléfono permanezca activo.

## 🛠️ Tecnologías utilizadas

| Tecnología               | Uso                                                 |
| ------------------------ | --------------------------------------------------- |
| Kotlin `2.0.21`          | Lenguaje principal                                  |
| Jetpack Compose          | Interfaz de usuario                                 |
| Material 3               | Componentes visuales                                |
| Compose BOM `2025.02.00` | Gestión de versiones de Compose                     |
| Room                     | Persistencia local de perfiles                      |
| Coroutines               | Operaciones asíncronas                              |
| ViewModel                | Gestión del estado de las pantallas                 |
| Apache Commons Net       | Cliente FTP                                         |
| Sockets TCP              | Envío de payloads                                   |
| Servidor HTTP propio     | Transferencia de archivos PKG                       |
| Foreground Service       | Transferencias en segundo plano                     |
| Wake lock                | Mantener activo el procesamiento cuando corresponda |

## 📂 Estructura del proyecto

```text
app/src/main/java/com/example/qsconnection/
├── MainActivity.kt
├── MainScreen.kt
├── MainViewModel.kt
├── PayloadSenderScreen.kt
├── FtpScreen.kt
├── InstallerScreen.kt
├── TransfersScreen.kt
├── ConnectionsScreen.kt
├── PkgHttpServer.kt
├── PkgInspector.kt
├── TransferService.kt
└── TransferManager.kt
```

### Responsabilidades principales

* `MainActivity.kt`: punto de entrada de la aplicación.
* `MainScreen.kt`: pantalla principal y navegación.
* `MainViewModel.kt`: lógica de conexiones, perfiles, payloads e instalación.
* `PayloadSenderScreen.kt`: interfaz de envío de payloads.
* `FtpScreen.kt`: explorador y operaciones FTP.
* `InstallerScreen.kt`: instalación de paquetes PKG en PS4.
* `TransfersScreen.kt`: cola y seguimiento de transferencias.
* `ConnectionsScreen.kt`: perfiles, escaneo de red y Wake-on-LAN.
* `PkgHttpServer.kt`: servidor HTTP local con soporte para rangos.
* `PkgInspector.kt`: inspección de la cabecera y metadatos del PKG.
* `TransferService.kt`: servicio de transferencias en primer plano.
* `TransferManager.kt`: coordinación de la cola de transferencias.

## 🚀 Compilación e instalación

### Requisitos de desarrollo

* Android Studio.
* Android SDK compatible con el proyecto.
* JDK 11 o superior, siempre que sea compatible con la configuración de Gradle y Android Gradle Plugin utilizada.
* Git.

### 1. Clonar el repositorio

```bash
git clone https://github.com/<tu-usuario>/qsconnections.git
cd qsconnections
```

Sustituye `<tu-usuario>` por el nombre de usuario o la organización que aloje el repositorio.

### 2. Compilar la versión de depuración

En Windows:

```powershell
.\gradlew.bat assembleDebug
```

En Linux o macOS:

```bash
./gradlew assembleDebug
```

### 3. Encontrar el APK

Si la compilación finaliza correctamente, el APK de depuración se encontrará normalmente en:

```text
app/build/outputs/apk/debug/app-debug.apk
```

Puedes instalarlo en un dispositivo compatible mediante Android Studio o ADB.

### 4. Generar una versión de lanzamiento

Para preparar una versión de distribución:

1. Crea un keystore de firma.
2. Configura la firma de lanzamiento en `app/build.gradle.kts`.
3. Protege las credenciales y evita subirlas al repositorio.
4. Genera el APK o el paquete correspondiente a tu método de distribución.

El identificador de aplicación inicial es:

```text
com.example.qsconnection
```

Puedes cambiarlo por un identificador propio antes de distribuir tu versión.

## 🔐 Privacidad y seguridad

QsConnections está diseñada para realizar sus operaciones de conexión y transferencia dentro de la red local.

* No necesita un servidor central para enviar archivos a la consola.
* Los paquetes PKG se sirven directamente desde el teléfono.
* Los perfiles se guardan localmente mediante Room.
* Las conexiones deben dirigirse únicamente a dispositivos autorizados.
* El escaneo de red debe limitarse a redes donde tengas permiso para realizarlo.

**Recomendaciones:**

* Utiliza redes de confianza.
* Evita exponer los puertos de la consola o del teléfono directamente a Internet.
* No compartas perfiles que contengan contraseñas.
* Ten en cuenta que FTP tradicional no cifra las credenciales ni los datos.
* Revisa los permisos de Android y las reglas de acceso de tu red.

La ausencia de un servidor externo para las transferencias no implica que todas las dependencias de compilación sean independientes de Internet.

## ❤️ Apoya el desarrollo

QsConnections es un proyecto de desarrollo independiente. Si la aplicación te resulta útil y quieres ayudar a mantener el proyecto, financiar nuevas funciones o apoyar futuras mejoras, puedes realizar una donación voluntaria.

**[💙 Donar mediante PayPal](https://www.paypal.com/paypalme/AngelOrellanaO?country.x=EC&locale.x=es_XC)**

Toda contribución es bienvenida y se agradece enormemente. Las donaciones son voluntarias y no son necesarias para utilizar el proyecto.

## ⚖️ Aviso legal

QsConnections es una herramienta independiente destinada a la administración de dispositivos propios y a la transferencia de archivos en redes autorizadas.

* No incluye ni distribuye firmware, payloads, juegos, paquetes PKG ni software propietario de PlayStation.
* El usuario es responsable de los archivos que seleccione y de las operaciones que realice.
* La compatibilidad depende de la configuración de cada consola y de las herramientas de terceros instaladas.
* PlayStation, PS3, PS4 y PS5 son marcas de sus respectivos titulares.
* Este proyecto no está afiliado, patrocinado ni respaldado por Sony Interactive Entertainment.

Utiliza la aplicación de manera responsable y respeta la legislación aplicable y los derechos de terceros.

## 📄 Licencia

Este proyecto se distribuye bajo la **licencia MIT**.

Puedes utilizar, copiar, modificar, fusionar, publicar, distribuir, sublicenciar y vender copias del software, siempre que conserves el aviso de copyright y el texto de la licencia.

Consulta el archivo [`LICENSE`](LICENSE) para conocer los términos completos.

---

**Desarrollado con Kotlin, Jetpack Compose y ❤️ para la comunidad.**

Si QsConnections te resulta útil, considera dejar una estrella ⭐ en GitHub y compartir el proyecto con otros usuarios.
