# QsConnections

App Android (Kotlin + Jetpack Compose) para gestionar consolas **PS3, PS4 y PS5** desde el móvil:
envía **payloads**, explora archivos por **FTP** e instala **PKG** en la PS4 sirviendo el paquete
directamente desde el teléfono. Todo con una interfaz oscura estilo PlayStation.

> Todo se realiza en tu **red local**. No usa servidores externos.

---

## Características

- **Enviar payloads** — envía archivos `.bin` por TCP a la consola (puerto típico 9020/9090).
- **Instalador de PKG (PS4)** — el móvil hace de servidor HTTP y la PS4 descarga el `.pkg`.
  Compatible con **etaHEN** y con **Remote Package Installer (RPI)**.
  - Validación local del PKG (cabecera, `CONTENT_ID`, tamaño) antes de enviarlo.
  - Detección automática del servicio disponible en el puerto 12800.
  - Recuperación automática del error `0x80990015` (tarea de descarga duplicada).
- **Explorador FTP** — navega, sube, descarga, renombra y elimina archivos.
  - Presets: **PS3 → 21**, **PS4/PS5 → 2121**.
- **Transferencias** — cola con progreso real, velocidad, tiempo restante, **pausa**, reanudación y cancelación.
- **Segundo plano** — las operaciones continúan aunque salgas de la app, con notificación de progreso
  y aviso al finalizar (servicio en primer plano + wakelock).
- **Perfiles** — guarda IP, puerto y credenciales FTP (base de datos Room).
- **Escaneo de red** — detecta consolas por puertos abiertos.
- **Wake-on-LAN** — enciende la consola por MAC (desde un perfil guardado).
- **Extras** — registrar actividad copiable, ajustes de sonido/vibración y compartir la app.

---

## Compatibilidad

| Consola | Payload | FTP | Instalar PKG | Puertos |
|--------|:------:|:---:|:------------:|---------|
| PS3    |   ✓    |  ✓  |      —       | Payload 9020 · FTP 21 |
| PS4    |   ✓    |  ✓  |      ✓       | Payload 9020/9090 · FTP 2121 · Instalar 12800 |
| PS5    |   ✓    |  ✓  |      —       | Payload 9020/9090 · FTP 2121 |

### Puertos usados

| Puerto | Uso |
|-------:|-----|
| `9020` / `9090` | Envío de payloads |
| `21` / `2121` | FTP (PS3 / PS4-PS5) |
| `12800` | API de instalación en la consola (etaHEN / RPI) |
| `9898` | Servidor HTTP **del teléfono** que sirve el `.pkg` (fijo por defecto) |

---

## Requisitos

- Android **7.0 (API 24)** o superior.
- El móvil y la consola en la **misma red WiFi**.
- Para instalar PKG en PS4: tener activo **etaHEN** o **Remote Package Installer** en la consola.
  Si usas RPI, deja la app en primer plano hasta que empiece la descarga.

---

## Compilar

Requisitos: **Android Studio** (o el SDK de Android) y **JDK 11+**.

```bash
git clone https://github.com/<tu-usuario>/qsconnection.git
cd qsconnection
./gradlew assembleDebug        # Windows: gradlew.bat assembleDebug
```

El APK se genera en:

```
app/build/outputs/apk/debug/app-debug.apk
```

Para generar un release firmado crea tu `keystore` y configura `signingConfigs`
en `app/build.gradle.kts`.

> El `applicationId` es `com.example.qsconnection`. Cámbialo por el tuyo en
> `app/build.gradle.kts` si vas a publicar tu propia versión.

---

## Uso rápido

1. **Perfil** → guarda la IP de tu consola (y puerto/credenciales FTP si las usas).
2. **Enviar payload** → selecciona el `.bin` y envíalo a 9020/9090.
3. **Explorador FTP** → elige preset (PS3 o PS4/PS5) o escribe el puerto y conecta.
4. **Instalador PKG (PS4)** → selecciona un perfil, pulsa **Probar conexión** y luego
   **Seleccionar archivo .pkg** → **Instalar en la PS4**. Deja que la consola descargue;
   al terminar se instala sola.

---

## Stack técnico

- **Kotlin** 2.0.21 · **Jetpack Compose** (Material 3, Compose BOM 2025.02.00)
- **Room** (perfiles) · **Coroutines** · **ViewModel**
- **Apache Commons Net** (cliente FTP)
- `PkgHttpServer` — servidor HTTP propio con soporte `Range` (200/206) para servir el PKG
- `TransferService` — servicio en primer plano con notificación de progreso
- Servidor **sin dependencias de red externas**: todo por sockets/HTTP locales

### Estructura

```
app/src/main/java/com/example/qsconnection/
├─ MainActivity.kt          # Punto de entrada
├─ MainScreen.kt            # Navegación + pantalla de inicio
├─ MainViewModel.kt         # Lógica: payload, FTP, instalación PKG, perfiles
├─ PayloadSenderScreen.kt   # Pantalla de envío de payloads
├─ FtpScreen.kt             # Explorador FTP
├─ InstallerScreen.kt       # Instalador de PKG
├─ TransfersScreen.kt       # Cola de transferencias
├─ ConnectionsScreen.kt     # Perfiles, escaneo de red, Wake-on-LAN
├─ PkgHttpServer.kt         # Servidor HTTP local que sirve el PKG
├─ PkgInspector.kt          # Validación de la cabecera de PKG
├─ TransferService.kt       # Servicio en primer plano (notificación)
└─ TransferManager.kt       # Cola global de transferencias
```

---

## Aviso legal

Esta aplicación es una herramienta de red de uso **personal**. No incluye ni distribuye
contenido de PlayStation, firmwares, payloads ni software propietario. Úsala solo en
consolas **de tu propiedad**. El proyecto no está afiliado a Sony Interactive Entertainment.

---

## Licencia

Este proyecto no incluye licencia por defecto. Si quieres hacerlo abierto, añade un archivo
`LICENSE` (por ejemplo [MIT](https://choosealicense.com/licenses/mit/)).
