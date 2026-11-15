---
name: bmwobd
description: Como trabajar en BmwObd (app Android que lee la DDE de BMW con un ELM327 clon) sin romper lo que funciona en el coche. Usar antes de cambiar cualquier cosa de obd/ (ObdSession, Pids, BmwUds, transporte, tiempos, formas de conectar) y antes de analizar un trayecto o un registro del coche.
---

# BmwObd: lo comprobado y como cambiar sin romper

La app habla con dos coches reales a traves de un ELM327 v1.5 clon por Bluetooth:

- **E87 118d, N47D20C, DDE 7.0 (EDC17C06)**, descripcion `d70n47b0`. Es el coche del dueño.
- **F20 118d 2017, B47, descripcion `d70bx7a0`** (indice 22 F150 = `0F1F90`, resuelto con `G_MOTOR.grp`). No es suyo: solo se prueba de visita.

Cada visita al coche cuesta tiempo y gasoil. Lo que se puede saber sin coche se averigua antes.

## Antes de cambiar nada (obligatorio)

1. **Historial.** `git log -S"<lo que se va a tocar>"` y leer los commits. Varias ideas ya se probaron en el coche y fallaron (lista abajo). Si una idea esta en "No funciona", no se propone otra vez sin un dato nuevo.
2. **Fuente de BMW.** Lo que pregunta la app sale de la descripcion de la centralita (`docs/`, local, fuera de git): tabla de medidas, trabajos, textos de averia. Para ver los bytes exactos de un trabajo: `docs/jobtrace` (EdiabasLib contra un coche falso). La documentacion dice lo que la centralita deberia hacer; el coche manda.
3. **Registros reales.** Los registros `registro_bmwobd_*.txt` tienen cada pregunta (`>>`) y la respuesta (`<<`). Hay tests que los reproducen (`ReplayTransport`); un cambio en como se pregunta o se decodifica tiene que pasar contra ellos.
4. **Adaptador que falla.** Todo cambio en la forma de conectar se prueba tambien contra un adaptador que se calla (tiempo agotado), que contesta tarde (la respuesta sale en la consulta siguiente) y que contesta otra cosa. Un coche simulado que siempre contesta bien no prueba nada.
5. **Sin coche no se decide.** Lo que no esta en un registro ni en la descripcion se pregunta en una sola sesion con el coche parado, junto con las demas dudas, y esa sesion pasa a ser un registro mas.
6. **Version.** Antes de analizar un registro o un CSV, mirar que version de la app lo grabo (primera linea del registro). Si falta una columna nueva, la APK instalada es vieja.

## Defectos del clon, vistos en registros reales

- **Lectura rapida (sufijo `1`) solo para consultas de 2 bytes del modo 01.** Con `2C10XXYY1` el clon manda la direccion sin el ultimo byte y la centralita contesta por `XX00` (escaneo del 10-10-2026).
- **Respuestas tardias cruzadas.** Si la DDE tarda, el clon da `NO DATA` y la respuesta sale como la de la siguiente consulta BMW. Por eso tras una consulta BMW sin dato se vacia con una del OBD (`flushLate`) y las medidas BMW exigen su tamaño exacto (`decodeOrNull`).
- **Mensajes largos con lectura rapida** dejan tramas pendientes que desplazan todo (PID 4C del E87): se marcan y se leen sin sufijo.
- **Pasarela de BMW 6F1 (ATSH6F1/ATCEA12/ATCRA612 con ATSP6) muda** en el E87 y en el F20: acepta los comandos y no llega. Con `ATSPB` dio `CAN ERROR` (sin `ATBI`). La forma de Deep OBD/live-obd (PB C1 01, ATSPB, ATBI, FC SD xx 30 00 02) esta sin probar en el coche.
- **Las formas que el clon no admite se quedan mudas** (tiempo agotado). Buscando la forma de conectar, un silencio descarta esa forma y se reinicia el adaptador; no cuenta como adaptador perdido (si contara, corta la conexion: paso el 13-11-2026).
- **Unos 80 ms por consulta**, unos 1,5 s por vuelta normal.

## Como lo hace Deep OBD (EdiabasLib, EdElmInterface.cs) con un ELM327

Es la referencia que funciona con ELM327 y la pasarela de BMW. Con un ELM normal (no Carly ni WGSoft):

- Arranque: `ATD ATE0 ATSH6F1 ATCF600 ATCM700 ATPBC001 ATSPB ATAT0 ATSTFF ATAL ATH1 ATS0 ATL0 ATCSM0 ATCTM5 ATJE`, despues `AT@1`, `AT#1` y `STI` para saber que adaptador es.
- **Sin `ATCEA` ni `ATBI`**: tramas crudas de 8 bytes `[destino, PCI, datos, relleno]` con cabeceras a la vista; recibe todo lo que llega por 0x6xx (filtro CF600/CM700).
- ISO-TP lo hace la app: al recibir una primera trama manda el control de flujo `[ecu] 30 03 00` (bloques de 3) y repite el control cada 3 tramas consecutivas. Para no esperar respuesta a ese control pone `ATST00`; para las tramas que esperan respuesta, `ATSTFF`. Las tramas consecutivas las recoge en modo monitor (`ATMA`).
- `ATCEA` + `ATPBC101` + `ATBI` (longitud variable con formato) solo para adaptadores Carly y WGSoft, que hacen el ISO-TP ellos.
- Su documentacion: valen ELM327 1.4b/1.5/2.1 originales con PIC18F25K80 sin MCP2515; los PIC falsos que dicen "1.5" no valen.

## N47 (E87): como contesta

- Medidas BMW: `2C10` + direccion de 2 bytes por la direccion OBD normal; respuesta `6C10` + valor, byte alto primero, sin repetir la direccion. Tabla MESSWERTETAB de `d70n47b0`, 1.002 direcciones; contestan 995 con el tamaño de la tabla.
- Identificacion `1A80`, averias propias `1802FFFF` (58 + numero + codigo 2 bytes + estado), historial `222100` (622100: numero y codigo + estado por entrada).
- **No funciona: dos direcciones en una consulta** (`2C10` + 2 direcciones). La DDE da bien solo la primera; la segunda trae un numero ajeno (9-10-2026, b266b6b, revertido en 015e035). El trabajo `STATUS_MESSWERTBLOCK_LESEN` del `.prg` si lo hace, pero por la pasarela, que el clon no alcanza.
- Sin sensor diferencial del filtro: 043B es absoluto delante y 0C81 es la presion de detras calculada. Cero aprendido 0428 normalmente entre -11 y -3 hPa.
- El PID 0B del OBD es el sensor de admision **antes** de la correccion; el regulador usa 076D. 01F9 es la desviacion del regulador del turbo vista por la propia centralita.
- Averias presentes de serie en este coche: sensores de aceite 498E y 3EFC (la temperatura de aceite es de sustitucion); sensor de temperatura antes del filtro sin montar (-40,05 fijo).

## B47 (F20): como contesta

- La pasarela 6F1 -> 12 no llega con el clon. Por la direccion OBD (7DF y 7E0) contesta la identificacion `22F150` -> `0F1F90`.
- Las medidas sueltas de `d70bx7a0` (42xx-5xxx) no contestan por la direccion OBD. Si contestan bloques 40xx que son trabajos de `d70bx7a0`: 4050 CBS_RESET_DATEN_LESEN (km del cuadro en el byte 9), 402B STATUS_MESSWERTE_IBS, 404F STATUS_CBSMOTOROELHIST, 4016, 4017, 401D, 4022, 4023, 402E, 4092, 4156.
- Sin probar: sesion ampliada (10 03) e identificador dinamico (2C 01 F300).

## Analizar un trayecto

- Referencia de fuera de los trayectos: la propia centralita (su consigna, su modelo, su vigilancia y sus averias), la descripcion de BMW, los boletines (SI B18 03 10 del filtro) y valores de taller con fuente. No dar por bueno un trayecto y comparar el resto con el: el coche tiene mas de 380.000 km.
- En el CSV cada fila repite el ultimo valor leido. Antes de comparar dos columnas mirar cada cuanto se actualiza cada una; solo cuentan las filas donde cambia.
- Cada afirmacion con su fuente; lo deducido se dice como "creo".
