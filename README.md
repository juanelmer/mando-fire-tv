# Mando Fire TV (app Android)

App para Android que controla un Fire Stick por WiFi. Habla ADB directamente con el Fire Stick, sin PC ni servidor de por medio.

## 1. Preparar el Fire Stick (una sola vez)
1. **Ajustes → Mi Fire TV → Acerca de** → pulsa 7 veces sobre el nombre del dispositivo si no ves "Opciones para desarrolladores".
2. **Ajustes → Mi Fire TV → Opciones para desarrolladores → Depuración ADB: ON**.
3. Apunta la IP: **Ajustes → Mi Fire TV → Acerca de → Red**.

## 2. Compilar la APK con GitHub (sin Android Studio)
1. En GitHub crea un repositorio nuevo, por ejemplo `mando-fire-tv`.
2. Desde el PC: **Add file → Upload files** y arrastra **todo el contenido** de esta carpeta, incluida la carpeta `.github`. Pulsa **Commit changes**.
   - Si Windows no te muestra `.github`, activa *Ver → Elementos ocultos*.
3. Entra en la pestaña **Actions**: verás "Compilar APK" en marcha (tarda unos 3–5 minutos).
4. Cuando termine con ✅, la APK aparece en **Releases** (columna derecha de la página del repo) como `MandoFireTV.apk`.

## 3. Instalar en el móvil
1. Abre el repo en el móvil → **Releases** → descarga `MandoFireTV.apk`.
2. Ábrela. Android te pedirá permitir "instalar apps de origen desconocido" para el navegador: acéptalo.
3. Abre **Mando Fire TV** → ⚙️ → escribe la IP del Fire TV → **Conectar**.
4. En la tele aparece **"¿Permitir depuración?"** → marca **Permitir siempre** → Aceptar → vuelve a pulsar **Conectar**.

## Funciones
- Cruceta, OK, atrás, inicio y menú. Las flechas, el volumen y el avance se repiten si mantienes pulsado.
- Play/pausa, avance y retroceso, volumen, silencio y encendido.
- **Los botones de volumen del móvil cambian el volumen de la tele** mientras la app está abierta y conectada.
- Botones para abrir apps. En ⚙️ → "Ver apps instaladas" añades cualquier app del Fire Stick.
- Escribir texto en buscadores (sin ñ ni tildes: el sistema de la tele no las acepta por esta vía).

## Actualizar la app
Cambia cualquier archivo en GitHub y Actions genera una versión nueva en Releases. Se instala encima de la anterior sin perder los ajustes.

## Problemas típicos
- **Falla la compilación en Actions** → abre el paso en rojo y copia el error. Se corrige rápido.
- **No conecta** → revisa la IP (conviene fijarla en el router), que el móvil esté en la misma WiFi y que la Depuración ADB siga activada.
- **El volumen no responde** → depende de la tele o la barra de sonido (HDMI-CEC). Revísalo en *Ajustes → Equipo y dispositivos* del Fire TV.
