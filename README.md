# BMW OBD

App Android propia para leer un BMW E87 118d (N47D20C) con un ELM327 Bluetooth básico.

## Qué hace

- Arranque animado: el cuentarrevoluciones se dibuja y la aguja barre, como el chequeo del cuadro.
- Conecta con el ELM327 (SPP, con reintento por canal 1), descubre qué PIDs del modo 01 responde el coche y los lee en bucle. RPM, velocidad, carga y presión de admisión se leen en cada vuelta; el resto, uno por vuelta.
- Panel con relojes (RPM con velocidad, turbo, refrigerante) y tarjetas con semáforo para el resto. Consumo instantáneo en L/100 km calculado.
- Cada conexión se graba como trayecto en un CSV. La lista de trayectos enseña duración, distancia y consumo; el detalle, máximos y una gráfica por medida. Se puede exportar el CSV.
- Con adaptador real, un servicio en primer plano mantiene la lectura y la grabación con la pantalla apagada.
- Unas sesenta medidas del modo 01, incluidas las de diésel (turbo, geometría variable, gases de escape, filtro de partículas) y contadores. Solo salen las que el coche anuncia; el Registro lista las que anuncia y la app no sabe leer.
- Averías: guardadas, pendientes y permanentes con descripción, testigo, foto de la avería, bastidor y versión de software, y borrado con confirmación.
- Indicios: reglas que miran las lecturas en su contexto (ralentí, en caliente, acelerando a fondo) y sacan recomendaciones para investigar, en el panel y en el repaso de cada trayecto. No son un diagnóstico; el rojo queda para lo que se aparta mucho. Los umbrales están en `advice/Advisor.kt` (`Limits`) y son de partida.
- Burbuja flotante (`Bubble.kt`) con turbo, temperaturas y el indicio más grave, para verla encima del navegador. Sale cuando hay conexión y la app no está en pantalla. Pide el permiso de mostrar sobre otras aplicaciones.
- Perfil por coche, identificado por el bastidor y creado al conectar; el usuario completa nombre y cilindrada. La app ya no es solo del 118d.
- Informe de cada trayecto (`report/TripReport.kt`): calentamiento, ralentí en caliente, aceleraciones a fondo con turbo y raíl pedidos frente a reales, crucero, tensión, escape e indicios. Se comparte como texto.
- Registro con el diálogo crudo con el adaptador, consola para mandar comandos a mano y botón de compartir.
- Coche simulado para trabajar la app sin estar en el coche.

## Estructura

- `obd/` protocolo, sin dependencias de interfaz: `ObdTransport` (canal), `Elm327` (comandos), `ObdParser`, `Pids` (fórmulas), `Ranges`, `ObdSession`. `SimulatedTransport` y `SimulatedEngine` son el adaptador y el coche de mentira.
- `trips/` grabación: `TripCsv` (formato y estadísticas), `TripStore` (ficheros), `TripRecorder`.
- `ObdController` dueño de la conexión y del bucle de lectura; vive en `BmwObdApp`. `ObdService` solo mantiene viva la app.
- `ui/` pantallas de Compose. `Theme.kt` tiene la paleta y `Gauge.kt` el reloj de arco.

## Uso

1. Para probar en el ordenador: botón "Coche simulado".
2. En el coche: empareja el ELM327 en los ajustes de Bluetooth (PIN 1234 o 0000), contacto puesto, y elígelo en la lista.

## Siguiente

- Tras la primera lectura real: quitar lo que el coche no da o crear perfiles de panel.
- Ajustes: rangos editables, alertas, elegir qué se ve en el panel.
- Comparar trayectos y rangos aprendidos de trayectos propios.
- Datos propios de BMW (DDE) cuando haya un adaptador que los soporte.
